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

import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;

/**
 * Manages proposal queue with Ethereum confirmation tracking.
 * 
 * <p>Queues proposals waiting for Ethereum transaction confirmation,
 * monitors transactions, verifies payments on-chain, and triggers
 * Raft append after verification.
 */
public class ProposalQueueManager {
    
    private static final Logger log = LoggerFactory.getLogger(ProposalQueueManager.class);
    
    private static final long CONFIRMATION_TIMEOUT_MS = 300_000; // 5 minutes
    private static final long MONITOR_INTERVAL_MS = 5_000; // Check every 5 seconds
    
    private final ConcurrentHashMap<String, QueuedProposal> pendingProposals = new ConcurrentHashMap<>();
    private final EvmBridge evmBridge;
    private final RaftAppendCallback raftAppendCallback;
    private final ScheduledExecutorService scheduler;
    private final BackpressureManager backpressureManager;
    private volatile boolean running = false;
    
    /**
     * Create a new proposal queue manager.
     * 
     * @param evmBridge EVM bridge for payment verification
     * @param raftAppendCallback Callback to append verified proposals to Raft
     * @param backpressureManager Backpressure manager for flow control
     */
    public ProposalQueueManager(
            EvmBridge evmBridge,
            RaftAppendCallback raftAppendCallback,
            BackpressureManager backpressureManager) {
        this.evmBridge = evmBridge;
        this.raftAppendCallback = raftAppendCallback;
        this.backpressureManager = backpressureManager;
        this.scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "ProposalQueueMonitor");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Start monitoring loop.
     */
    public void start() {
        if (running) {
            log.warn("ProposalQueueManager already started");
            return;
        }
        
        running = true;
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (running) {
                    processPendingProposals();
                }
            } catch (Exception e) {
                log.error("Error processing pending proposals", e);
            }
        }, MONITOR_INTERVAL_MS, MONITOR_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        log.info("✅ ProposalQueueManager started (monitor interval: {}ms, timeout: {}ms)", 
            MONITOR_INTERVAL_MS, CONFIRMATION_TIMEOUT_MS);
    }
    
    /**
     * Queue a proposal waiting for Ethereum confirmation.
     * 
     * @param proposalId Unique proposal identifier
     * @param ethereumTxHash Ethereum transaction hash
     * @param walletAddress Wallet address
     * @param path Shard path
     * @param contentType Content type
     * @param message Content message
     * @param signature Transaction signature
     */
    public void queueProposal(
            String proposalId,
            String ethereumTxHash,
            String walletAddress,
            String path,
            String contentType,
            String message,
            String signature) {
        
        QueuedProposal queued = new QueuedProposal(
            proposalId,
            ethereumTxHash,
            null, // WriteProposal not used for wallet-based writes
            System.currentTimeMillis(),
            System.currentTimeMillis() + CONFIRMATION_TIMEOUT_MS,
            ProposalState.PENDING
        );
        
        // Store proposal data in QueuedProposal (we'll extend it)
        queued.setWalletAddress(walletAddress);
        queued.setPath(path);
        queued.setContentType(contentType);
        queued.setMessage(message);
        queued.setSignature(signature);
        
        pendingProposals.put(proposalId, queued);
        log.debug("📥 Queued proposal {} (tx: {}), waiting for Ethereum confirmation", 
            proposalId, ethereumTxHash);
    }
    
    /**
     * Process all pending proposals.
     * Optimized to only process PENDING proposals and batch operations.
     */
    private void processPendingProposals() {
        long now = System.currentTimeMillis();
        int processed = 0;
        int verified = 0;
        int rejected = 0;
        
        // Use iterator to avoid ConcurrentModificationException when removing rejected proposals
        java.util.Iterator<QueuedProposal> iterator = pendingProposals.values().iterator();
        while (iterator.hasNext()) {
            QueuedProposal queued = iterator.next();
            
            if (queued.getState() == ProposalState.PENDING) {
                processed++;
                
                // Check timeout first (before expensive verification)
                if (now > queued.getTimeoutTimestamp()) {
                    rejectProposal(queued, "Confirmation timeout (>5 minutes)");
                    rejected++;
                    continue;
                }
                
                // Check Ethereum confirmation (via EvmBridge)
                ProposalState previousState = queued.getState();
                checkConfirmation(queued);
                
                // Track if proposal was verified in this cycle
                if (queued.getState() == ProposalState.VERIFIED && previousState == ProposalState.PENDING) {
                    verified++;
                }
            }
        }
        
        // Log summary if there was activity
        if (processed > 0) {
            log.debug("📊 Processed {} pending proposals: {} verified, {} rejected, {} still pending", 
                processed, verified, rejected, processed - verified - rejected);
        }
    }
    
    /**
     * Check Ethereum transaction confirmation.
     */
    private void checkConfirmation(QueuedProposal queued) {
        try {
            // Verify payment on-chain via EvmBridge
            PaymentProof proof = evmBridge.verifyPayment(queued.getProposalId());
            
            if (proof != null && proof.isConfirmed(1)) { // Require at least 1 confirmation
                // Payment verified - ready for Raft
                queued.setState(ProposalState.VERIFIED);
                if (proof.getBlockNumber() > 0) {
                    queued.setConfirmedBlock(proof.getBlockNumber());
                }
                
                log.debug("✅ Proposal {} verified on-chain (tx: {}, block: {}), ready for Raft", 
                    queued.getProposalId(), 
                    queued.getEthereumTxHash() != null ? queued.getEthereumTxHash().substring(0, Math.min(10, queued.getEthereumTxHash().length())) + "..." : "unknown",
                    queued.getConfirmedBlock());
                
                // Append to Raft via callback
                appendToRaft(queued);
            } else {
                // Payment not verified yet - will retry next cycle
                // In mock mode, auto-confirmation happens on first check
                // In real mode, EvmBridge polls blockchain for transaction receipt
            }
        } catch (Exception e) {
            log.error("Error checking confirmation for proposal {}", queued.getProposalId(), e);
        }
    }
    
    /**
     * Append verified proposal to Raft log.
     * Rate-limited to prevent overwhelming Aeron cluster with bursts.
     */
    private void appendToRaft(QueuedProposal queued) {
        try {
            if (raftAppendCallback != null) {
                long startTime = System.currentTimeMillis();
                
                raftAppendCallback.appendProposal(
                    queued.getWalletAddress(),
                    queued.getPath(),
                    queued.getContentType(),
                    queued.getMessage(),
                    queued.getSignature()
                );
                
                queued.setState(ProposalState.PROCESSED);
                long duration = System.currentTimeMillis() - startTime;
                
                log.debug("✅ Proposal {} appended to Raft (path: {}, duration: {}ms)", 
                    queued.getProposalId(), 
                    queued.getPath(),
                    duration);
                
                // Remove from pending map after successful append (optimize memory)
                pendingProposals.remove(queued.getProposalId());
                
                // Apply backpressure if Aeron cluster cannot keep up
                // Replaces fixed rate limiting with dynamic flow control
                // Blocks ONLY when pending messages >= max (default: 2000)
                // No delay when cluster healthy - achieves maximum throughput
                try {
                    backpressureManager.applyBackpressureIfNeeded();
                    backpressureManager.incrementSent(); // Track this offer
                } catch (BackpressureTimeoutException e) {
                    log.error("❌ Backpressure timeout - Aeron cluster overloaded: {}", e.getMessage());
                    rejectProposal(queued, "Backpressure timeout: " + e.getMessage());
                    // Continue processing next proposal (don't stop entire queue)
                }
            } else {
                log.warn("⚠️  No Raft append callback configured for proposal {}", queued.getProposalId());
                rejectProposal(queued, "No Raft append callback configured");
            }
        } catch (Exception e) {
            log.error("Error appending proposal {} to Raft", queued.getProposalId(), e);
            rejectProposal(queued, "Raft append failed: " + e.getMessage());
        }
    }
    
    /**
     * Reject proposal (remove from queue).
     */
    private void rejectProposal(QueuedProposal queued, String reason) {
        queued.setState(ProposalState.REJECTED);
        queued.setRejectionReason(reason);
        pendingProposals.remove(queued.getProposalId());
        
        log.warn("❌ Rejecting proposal {}: {}", queued.getProposalId(), reason);
    }
    
    /**
     * Get proposal status.
     */
    public ProposalStatus getProposalStatus(String proposalId) {
        QueuedProposal queued = pendingProposals.get(proposalId);
        if (queued == null) {
            return null; // Not in queue (processed or never queued)
        }
        
        return new ProposalStatus(
            queued.getProposalId(),
            queued.getState(),
            queued.getEthereumTxHash(),
            queued.getTimeoutTimestamp(),
            queued.getConfirmedBlock(),
            queued.getRejectionReason()
        );
    }
    
    /**
     * Get pending proposals count.
     */
    public int getPendingCount() {
        return (int) pendingProposals.values().stream()
            .filter(p -> p.getState() == ProposalState.PENDING)
            .count();
    }
    
    /**
     * Shutdown the queue manager.
     */
    public void shutdown() {
        running = false;
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("✅ ProposalQueueManager shut down");
    }
}

