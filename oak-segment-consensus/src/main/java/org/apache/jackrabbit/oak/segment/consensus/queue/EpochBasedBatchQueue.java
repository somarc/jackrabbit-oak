/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.queue;

import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Epoch-based batch queue that organizes proposals by Ethereum epoch and wallet address.
 * 
 * <p><strong>Ethereum Finality Model:</strong>
 * <ul>
 *   <li>Epoch Duration: ~6.4 minutes (384 seconds)</li>
 *   <li>Finality: 2 epochs (~12.8 minutes)</li>
 *   <li>Proposals are seen immediately but queued until finality</li>
 * </ul>
 * 
 * <p><strong>Batching Strategy:</strong>
 * <ol>
 *   <li>Proposals arrive tagged with their origin epoch</li>
 *   <li>Queue holds proposals for 2 epochs (finality period)</li>
 *   <li>When epoch N+2 finalizes, process all epoch N proposals</li>
 *   <li>Group proposals by wallet address (0x...)</li>
 *   <li>Sort within each wallet group (by path, then timestamp)</li>
 *   <li>Create optimally-sized batches</li>
 * </ol>
 * 
 * <p><strong>Storage Benefits:</strong>
 * <ul>
 *   <li>Co-located Segments: Wallet A's writes are in segments 1,2,5 (not interleaved)</li>
 *   <li>Efficient GC: Deleting a wallet removes contiguous TAR files</li>
 *   <li>Accurate Metrics: Per-wallet fragmentation and storage usage</li>
 *   <li>Tighter DAG: Less interleaving means fewer "holes" during compaction</li>
 * </ul>
 * 
 * <p><strong>Example Timeline:</strong>
 * <pre>
 * Epoch 100 (t=0):     Tx1, Tx2, Tx3 arrive → Queue in epoch 100
 * Epoch 101 (t=6.4m):  Tx4, Tx5 arrive → Queue in epoch 101
 * Epoch 102 (t=12.8m): ✅ Finalize epoch 100 → Batch and send Tx1,Tx2,Tx3
 * Epoch 103 (t=19.2m): ✅ Finalize epoch 101 → Batch and send Tx4,Tx5
 * </pre>
 */
public class EpochBasedBatchQueue {
    
    private static final Logger log = LoggerFactory.getLogger(EpochBasedBatchQueue.class);
    
    // Configuration: Ethereum Finality Parameters
    private static final int FINALITY_EPOCHS = 2; // 2 epochs for STANDARD tier
    private static final int OPTIMAL_BATCH_SIZE = 25; // Proposals per batch
    
    /**
     * Get finality delay (in epochs) for a given payment tier.
     * 
     * @param tier Payment tier
     * @return Number of epochs to wait before finalizing
     */
    private static int getFinalityDelay(org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier tier) {
        if (tier == null) {
            return FINALITY_EPOCHS; // Default to STANDARD
        }
        
        if (tier == org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY) {
            return 0;  // Immediate (handled via fast-path, shouldn't reach here)
        } else if (tier == org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.EXPRESS) {
            return 1;   // 1 epoch (~6.4 minutes)
        } else {
            return 2;  // STANDARD: 2 epochs (~12.8 minutes)
        }
    }
    
    // Ethereum Beacon Chain client (provides real-time epoch data)
    private final BeaconChainClient beaconClient;
    
    // Data Structure: epoch → (walletAddress → [proposals])
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, List<QueuedProposal>>> pendingEpochWrites;
    
    // Track epoch lifecycle
    private volatile long genesisEpoch = -1; // Ethereum epoch when this system was deployed
    private volatile long lastFinalizedEpoch = -1;
    
    // Statistics
    private volatile long totalProposalsQueued = 0;
    private volatile long totalBatchesCreated = 0;
    private volatile long totalProposalsFinalized = 0;
    
    /**
     * Create epoch-based batch queue.
     * 
     * <p><strong>Genesis Epoch:</strong> The Ethereum epoch when this validator network was deployed.
     * This aligns with the smart contract deployment epoch and becomes the reference point for
     * the system's lifecycle. All writes are tagged with real Ethereum epochs starting from genesis.
     * 
     * @param beaconClient Client for fetching current Ethereum epoch from Beacon Chain
     */
    public EpochBasedBatchQueue(BeaconChainClient beaconClient) {
        this.beaconClient = beaconClient;
        this.pendingEpochWrites = new ConcurrentHashMap<>();
        
        try {
            long currentEpoch = beaconClient.getLatestFinalizedEpoch().epochNumber + FINALITY_EPOCHS;
            this.genesisEpoch = currentEpoch; // Genesis = current epoch at system start
            
            log.info("📅 EpochBasedBatchQueue initialized");
            log.info("   - Genesis Ethereum Epoch: {} (network deployment epoch)", genesisEpoch);
            log.info("   - Current Ethereum Epoch: {}", currentEpoch);
            log.info("   - Finalized Epoch: {}", currentEpoch - FINALITY_EPOCHS);
            log.info("   - Finality Delay: {} epochs (~12.8 minutes)", FINALITY_EPOCHS);
            log.info("   - Optimal Batch Size: {}", OPTIMAL_BATCH_SIZE);
            log.info("   - 📡 Tracking: beaconscan.com (real-time Ethereum mainnet)");
        } catch (Exception e) {
            log.warn("⚠️  Could not fetch initial epoch from Beacon Chain: {}", e.getMessage());
            log.info("📅 EpochBasedBatchQueue initialized (epoch query failed, will retry)");
            this.genesisEpoch = 0; // Fallback
        }
    }
    
    /**
     * Get the genesis epoch (the Ethereum epoch when this system was deployed).
     * 
     * @return Genesis epoch number
     */
    public long getGenesisEpoch() {
        return genesisEpoch;
    }
    
    /**
     * Add a proposal to the queue for a specific epoch.
     * 
     * @param proposal The proposal to queue
     * @param epoch The Ethereum epoch when this proposal's transaction was seen
     */
    public void addProposal(QueuedProposal proposal, long epoch) {
        // Get or create epoch map
        ConcurrentHashMap<String, List<QueuedProposal>> epochMap = 
            pendingEpochWrites.computeIfAbsent(epoch, k -> new ConcurrentHashMap<>());
        
        // Get or create wallet list
        String walletAddress = proposal.getWalletAddress();
        List<QueuedProposal> walletProposals = epochMap.computeIfAbsent(walletAddress, k -> 
            Collections.synchronizedList(new ArrayList<>()));
        
        // Add proposal to wallet's list
        walletProposals.add(proposal);
        totalProposalsQueued++;
        
        log.debug("📥 Queued proposal {} for wallet {} in epoch {} (total queued: {})",
            proposal.getProposalId(), walletAddress, epoch, totalProposalsQueued);
    }
    
    /**
     * Get the current Ethereum epoch from Beacon Chain.
     * 
     * <p>This queries the real Ethereum Beacon Chain to get the current epoch number.
     * The current epoch is typically 2 epochs ahead of the finalized epoch due to
     * the Casper FFG finality gadget requiring 2/3 validator attestations.
     * 
     * @return Current epoch number from Ethereum mainnet
     */
    public long getCurrentEpoch() {
        try {
            // Beacon client returns finalized epoch, current is +2
            long finalizedEpoch = beaconClient.getLatestFinalizedEpoch().epochNumber;
            return finalizedEpoch + FINALITY_EPOCHS;
        } catch (Exception e) {
            log.warn("⚠️  Failed to fetch current epoch from Beacon Chain: {}", e.getMessage());
            // Fallback: Use last known finalized + 2
            return lastFinalizedEpoch >= 0 ? lastFinalizedEpoch + FINALITY_EPOCHS : 0;
        }
    }
    
    /**
     * Get the current finalized epoch from Ethereum Beacon Chain.
     * 
     * @return Finalized epoch number
     */
    public long getFinalizedEpoch() {
        try {
            return beaconClient.getLatestFinalizedEpoch().epochNumber;
        } catch (Exception e) {
            log.warn("⚠️  Failed to fetch finalized epoch from Beacon Chain: {}", e.getMessage());
            return lastFinalizedEpoch >= 0 ? lastFinalizedEpoch : 0;
        }
    }
    
    /**
     * Get epochs that are ready for finalization.
     * An epoch is finalizable if proposals within it have passed their tier-based finality delay.
     * 
     * <p><strong>Tier-based Finality:</strong>
     * <ul>
     *   <li>PRIORITY: 0 epochs (immediate, handled via fast-path)</li>
     *   <li>EXPRESS: 1 epoch (~6.4 minutes)</li>
     *   <li>STANDARD: 2 epochs (~12.8 minutes)</li>
     * </ul>
     * 
     * @return List of epoch numbers ready for finalization
     */
    public List<Long> getFinalizableEpochs() {
        long currentEpoch = getCurrentEpoch();
        
        return pendingEpochWrites.keySet().stream()
            .filter(epoch -> {
                // Check if ANY proposals in this epoch have passed their finality delay
                ConcurrentHashMap<String, List<QueuedProposal>> epochMap = pendingEpochWrites.get(epoch);
                if (epochMap == null) return false;
                
                // Find minimum finality delay required for proposals in this epoch
                int minDelay = epochMap.values().stream()
                    .flatMap(List::stream)
                    .mapToInt(p -> getFinalityDelay(p.getTier()))
                    .min()
                    .orElse(FINALITY_EPOCHS);
                
                // Epoch is finalizable if current epoch >= (proposal epoch + required delay)
                return currentEpoch >= (epoch + minDelay);
            })
            .filter(epoch -> epoch > lastFinalizedEpoch) // Don't re-finalize
            .sorted()
            .collect(Collectors.toList());
    }
    
    /**
     * Finalize an epoch and return optimally-batched proposals.
     * 
     * <p><strong>Tier-Aware Batching Algorithm:</strong>
     * <ol>
     *   <li>Filter proposals by tier-based finality delay</li>
     *   <li>Group all proposals by wallet address</li>
     *   <li>Sort each wallet's proposals by path, then timestamp</li>
     *   <li>Create batches of optimal size (25 proposals each)</li>
     *   <li>Prioritize keeping wallet writes together in same batch</li>
     * </ol>
     * 
     * @param epoch The epoch to finalize
     * @return List of batches, where each batch is a list of proposals
     */
    public List<List<QueuedProposal>> finalizeEpoch(long epoch) {
        if (epoch <= lastFinalizedEpoch) {
            log.warn("⚠️  Attempted to finalize epoch {} but last finalized was {}", epoch, lastFinalizedEpoch);
            return Collections.emptyList();
        }
        
        // Get epoch map (don't remove yet - might have proposals with longer delays)
        ConcurrentHashMap<String, List<QueuedProposal>> epochMap = pendingEpochWrites.get(epoch);
        if (epochMap == null || epochMap.isEmpty()) {
            log.debug("📭 Epoch {} had no proposals to finalize", epoch);
            lastFinalizedEpoch = epoch;
            return Collections.emptyList();
        }
        
        // Calculate current epoch for tier-based finality checks
        long currentEpoch = getCurrentEpoch();
        
        // Filter proposals by tier-based finality delay
        // Copy and filter proposals that have met their finality requirement
        ConcurrentHashMap<String, List<QueuedProposal>> readyProposals = new ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger expressCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.atomic.AtomicInteger standardCount = new java.util.concurrent.atomic.AtomicInteger(0);
        
        for (Map.Entry<String, List<QueuedProposal>> entry : epochMap.entrySet()) {
            String wallet = entry.getKey();
            List<QueuedProposal> proposals = entry.getValue();
            
            List<QueuedProposal> ready = proposals.stream()
                .filter(p -> {
                    int requiredDelay = getFinalityDelay(p.getTier());
                    boolean isReady = currentEpoch >= (epoch + requiredDelay);
                    if (isReady) {
                        if (p.getTier() == org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.EXPRESS) {
                            expressCount.incrementAndGet();
                        } else {
                            standardCount.incrementAndGet();
                        }
                    }
                    return isReady;
                })
                .collect(Collectors.toList());
            
            if (!ready.isEmpty()) {
                readyProposals.put(wallet, ready);
                
                // Remove finalized proposals from original epoch map
                proposals.removeAll(ready);
            }
        }
        
        // Clean up empty wallet lists and remove epoch if completely processed
        epochMap.entrySet().removeIf(entry -> entry.getValue().isEmpty());
        if (epochMap.isEmpty()) {
            pendingEpochWrites.remove(epoch);
        }
        
        if (readyProposals.isEmpty()) {
            log.debug("📭 Epoch {} had no proposals ready for finalization yet (waiting for tier delays)", epoch);
            return Collections.emptyList();
        }
        
        log.info("📦 Finalizing epoch {} with {} proposals (EXPRESS: {}, STANDARD: {})",
            epoch, expressCount.get() + standardCount.get(), expressCount.get(), standardCount.get());
        
        long startTime = System.nanoTime();
        List<List<QueuedProposal>> batches = new ArrayList<>();
        
        // Sort wallets by address for deterministic ordering
        List<String> sortedWallets = readyProposals.keySet().stream()
            .sorted()
            .collect(Collectors.toList());
        
        // Process each wallet's proposals
        for (String walletAddress : sortedWallets) {
            List<QueuedProposal> walletProposals = readyProposals.get(walletAddress);
            if (walletProposals == null || walletProposals.isEmpty()) {
                continue;
            }
            
            // Sort wallet's proposals for optimal segment packing
            // 1. By path (co-locate related content)
            // 2. By timestamp (maintain causal order)
            Collections.sort(walletProposals, Comparator
                .comparing(QueuedProposal::getPath)
                .thenComparing(QueuedProposal::getTimestamp));
            
            log.debug("   💼 Wallet {}: {} proposals", walletAddress, walletProposals.size());
            
            // Create batches for this wallet
            for (int i = 0; i < walletProposals.size(); i += OPTIMAL_BATCH_SIZE) {
                int endIndex = Math.min(i + OPTIMAL_BATCH_SIZE, walletProposals.size());
                List<QueuedProposal> batch = new ArrayList<>(walletProposals.subList(i, endIndex));
                batches.add(batch);
                totalBatchesCreated++;
                totalProposalsFinalized += batch.size();
                
                log.debug("      → Batch {}: {} proposals ({} to {})",
                    totalBatchesCreated, batch.size(), i, endIndex - 1);
            }
        }
        
        lastFinalizedEpoch = epoch;
        long durationMs = (System.nanoTime() - startTime) / 1_000_000;
        
        log.info("✅ Finalized epoch {} in {}ms: {} batches, {} proposals",
            epoch, durationMs, batches.size(), 
            batches.stream().mapToInt(List::size).sum());
        
        return batches;
    }
    
    /**
     * Get statistics for monitoring.
     */
    public String getStats() {
        long currentEpoch = getCurrentEpoch();
        int pendingEpochs = pendingEpochWrites.size();
        int pendingProposals = pendingEpochWrites.values().stream()
            .mapToInt(m -> m.values().stream().mapToInt(List::size).sum())
            .sum();
        
        return String.format(
            "Current Epoch: %d, Pending Epochs: %d, Pending Proposals: %d, " +
            "Total Queued: %d, Total Finalized: %d, Batches Created: %d",
            currentEpoch, pendingEpochs, pendingProposals,
            totalProposalsQueued, totalProposalsFinalized, totalBatchesCreated
        );
    }
    
    /**
     * Get all pending epochs (epochs that have writes queued but not yet finalized).
     * 
     * @return List of epoch numbers with pending writes, sorted oldest to newest
     */
    public List<Long> getAllPendingEpochs() {
        return pendingEpochWrites.keySet().stream()
            .sorted()
            .collect(Collectors.toList());
    }
    
    /**
     * Get detailed statistics for a specific epoch.
     */
    public EpochStats getEpochStats(long epoch) {
        ConcurrentHashMap<String, List<QueuedProposal>> epochMap = pendingEpochWrites.get(epoch);
        if (epochMap == null) {
            return new EpochStats(epoch, 0, 0, Collections.emptyMap());
        }
        
        Map<String, Integer> walletCounts = epochMap.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> e.getValue().size()
            ));
        
        int totalProposals = walletCounts.values().stream().mapToInt(Integer::intValue).sum();
        
        return new EpochStats(epoch, epochMap.size(), totalProposals, walletCounts);
    }
    
    /**
     * Statistics for a single epoch.
     */
    public static class EpochStats {
        public final long epoch;
        public final int walletCount;
        public final int proposalCount;
        public final Map<String, Integer> walletCounts;
        
        public EpochStats(long epoch, int walletCount, int proposalCount, Map<String, Integer> walletCounts) {
            this.epoch = epoch;
            this.walletCount = walletCount;
            this.proposalCount = proposalCount;
            this.walletCounts = walletCounts;
        }
        
        @Override
        public String toString() {
            return String.format("Epoch %d: %d wallets, %d proposals", epoch, walletCount, proposalCount);
        }
    }
    
    /**
     * Clear all pending epochs (for testing/reset).
     */
    public void clear() {
        pendingEpochWrites.clear();
        lastFinalizedEpoch = -1;
        totalProposalsQueued = 0;
        totalBatchesCreated = 0;
        totalProposalsFinalized = 0;
        log.info("🧹 EpochBasedBatchQueue cleared");
    }
}

