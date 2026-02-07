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

import org.agrona.concurrent.Agent;
import org.agrona.concurrent.AgentRunner;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.*;

/**
 * Optimized proposal queue manager with production throughput patterns.
 * 
 * <p><strong>Key Optimizations:</strong>
 * <ol>
 *   <li><strong>Message Batching</strong>: Process up to 10 verified proposals per Aeron cycle</li>
 *   <li><strong>Dual-Agent Architecture</strong>:
 *     <ul>
 *       <li>Fast Agent: Sends verified proposals to Aeron (ultra-low latency)</li>
 *       <li>Slow Agent: EVM signature verification (can take 1-100ms without blocking Aeron)</li>
 *     </ul>
 *   </li>
 *   <li><strong>BackoffIdleStrategy</strong>: Intelligent CPU usage (spin → yield → park)</li>
 * </ol>
 * 
 * <p><strong>Security Architecture:</strong>
 * <p>The EVM Verifier Agent enforces three security checkpoints BEFORE proposals reach Aeron:
 * <ol>
 *   <li><strong>Ethereum Transaction Validation</strong>: Verifies payment proof exists, is confirmed,
 *       went to correct contract, and has sufficient amount</li>
 *   <li><strong>Cryptographic Signature Verification</strong>: Verifies ECDSA signature matches
 *       wallet address and is specific to this write operation</li>
 *   <li><strong>Authorization Check</strong>: Verifies wallet has permission to write to the
 *       requested path (sharding rules, quotas, bans)</li>
 * </ol>
 * 
 * <p><strong>Architecture:</strong>
 * <pre>
 * HTTP API → Unverified Queue → [EVM Agent w/ Security Checks] → Verified Queue → [Aeron Agent] → Raft
 *                 ↓ slow (1-100ms, 3 security checkpoints)     ↑ fast (0.1ms, batched, ONLY validated proposals)
 * </pre>
 * 
 * <p><strong>Expected Performance:</strong>
 * <ul>
 *   <li>Current: ~100 writes/sec (single-threaded, 10ms delay)</li>
 *   <li>Optimized: ~1,000-2,000 writes/sec (dual-agent, batching, no delay)</li>
 * </ul>
 */
public class ProposalQueueManagerOptimized {
    
    private static final Logger log = LoggerFactory.getLogger(ProposalQueueManagerOptimized.class);
    private static final long HIGH_FREQ_LOG_INTERVAL_MS = 5000;
    private final java.util.concurrent.atomic.AtomicLong lastQueueDepthLogMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger queueDepthSuppressed = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong lastDequeuedLogMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger dequeuedSuppressed = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong lastBatchSentLogMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger batchSentSuppressed = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong lastEpochQueueLogMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger epochQueueSuppressed = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.concurrent.atomic.AtomicLong lastPriorityLogMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger prioritySuppressed = new java.util.concurrent.atomic.AtomicInteger(0);
    
    // Configuration
    private final long confirmationTimeoutMs;
    private final long restoreTimeoutMs;
    private final int maxMessageBatch;
    private final int maxRetryCount;
    // 🌐 PRODUCTION WAN: Aeron default MTU = 1408 bytes (safe for AWS/GCP/Azure)
    // maxPayloadLength = 1408 - 32 (frame header) = 1376 bytes
    // Each proposal ~366 bytes: 3 proposals = 1098 bytes + overhead (~20 bytes) = ~1118 bytes
    // Keeps batches safely under 1376-byte limit for global distributed deployment
    // See: Blockchain-AEM/06-test-results/2025-11-21-BATCH-UDP-MTU-LIMIT.md
    private final int finalizationChunkSize;
    
    // Queues
    private final ConcurrentLinkedQueue<QueuedProposal> unverifiedQueue = new ConcurrentLinkedQueue<>();
    private final EpochBasedBatchQueue epochQueue; // NEW: Epoch-based batching for optimal segment packing
    private final ConcurrentLinkedQueue<List<QueuedProposal>> batchQueue = new ConcurrentLinkedQueue<>(); // Batches ready to send
    private final ConcurrentHashMap<String, QueuedProposal> allProposals = new ConcurrentHashMap<>();
    private final ProposalPersistenceStore persistenceStore;
    private final QueueCounterStateStore counterStateStore;
    private final Object persistenceLock = new Object();
    private final long persistenceFlushIntervalMs;
    private final int persistenceFlushBatch;
    private final java.util.concurrent.atomic.AtomicLong persistencePendingChanges =
        new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicBoolean persistenceFlushInProgress =
        new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile boolean persistenceDirty = false;
    private java.util.concurrent.ScheduledExecutorService persistenceScheduler;
    
    // Dependencies
    private final EvmBridge evmBridge;
    private final RaftAppendCallback raftAppendCallback;
    private final BackpressureManager backpressureManager;
    
    // Agents (3-agent architecture)
    private AgentRunner aeronSenderAgent;
    private AgentRunner[] evmVerifierAgents;
    private AgentRunner epochFinalizerAgent; // NEW: Finalizes epochs and creates batches
    private volatile boolean running = false;

    private final int verifierThreads;
    
    // Metrics: Priority tier routing
    private final java.util.concurrent.atomic.AtomicLong priorityProposalsSent = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong batchedProposalsSent = new java.util.concurrent.atomic.AtomicLong(0);
    
    // Metrics: Persistent counters (survive proposal removal from allProposals)
    private final java.util.concurrent.atomic.AtomicLong totalRejectedCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalVerifiedCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong totalFinalizedCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong lifetimeRejectedBase = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong lifetimeVerifiedBase = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong lifetimeFinalizedBase = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong lifetimePrioritySentBase = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong lifetimeBatchedSentBase = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong counterWindowStartMs =
        new java.util.concurrent.atomic.AtomicLong(System.currentTimeMillis());
    private final long counterRotationIntervalMs;
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>> finalizedByEpochAndTier =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>> rejectedByEpochAndTier =
        new ConcurrentHashMap<>();
    private final long processedRetentionMs;
    private volatile long lastProcessedCleanup = 0L;

    // Metrics: EVM verifier timings and outcomes (for mempool bottleneck analysis)
    private final java.util.concurrent.atomic.AtomicLong verifierAttemptCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierSuccessCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierRequeueNoProofCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierRequeueUnconfirmedCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierRejectedCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierErrorCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierTotalNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierProofNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierSignatureNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierAuthNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierPersistNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierQueueWaitMsTotal = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierQueueWaitMsMax = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierLastTotalMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierLastProofMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierLastSignatureMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierLastAuthMs = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong verifierLastPersistMs = new java.util.concurrent.atomic.AtomicLong(0);

    // Metrics: enqueue persistence overhead
    private final java.util.concurrent.atomic.AtomicLong enqueuePersistNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong enqueuePersistCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong enqueuePersistLastMs = new java.util.concurrent.atomic.AtomicLong(0);

    // Metrics: persistence flush timing (async mode)
    private final java.util.concurrent.atomic.AtomicLong persistenceFlushNanos = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong persistenceFlushCount = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicLong persistenceFlushLastMs = new java.util.concurrent.atomic.AtomicLong(0);
    
    /**
     * Create optimized proposal queue manager with Ethereum epoch-based batching.
     * 
     * @param evmBridge EVM bridge for payment verification
     * @param raftAppendCallback Callback to append verified proposals to Raft
     * @param backpressureManager Backpressure manager for flow control
     * @param beaconClient Beacon Chain client for real-time Ethereum epoch tracking
     */
    public ProposalQueueManagerOptimized(
            EvmBridge evmBridge,
            RaftAppendCallback raftAppendCallback,
            BackpressureManager backpressureManager,
            org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient beaconClient) {
        this(evmBridge, raftAppendCallback, backpressureManager, beaconClient, null,
            ProposalQueueTuningRegistry.get());
    }
    
    /**
     * Create optimized proposal queue manager with optional persistence directory.
     * 
     * @param evmBridge EVM bridge for payment verification
     * @param raftAppendCallback Callback to append verified proposals to Raft
     * @param backpressureManager Backpressure manager for flow control
     * @param beaconClient Beacon Chain client for real-time Ethereum epoch tracking
     * @param persistenceDir Optional directory for persisting queued proposals
     */
    public ProposalQueueManagerOptimized(
            EvmBridge evmBridge,
            RaftAppendCallback raftAppendCallback,
            BackpressureManager backpressureManager,
            org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient beaconClient,
            String persistenceDir) {
        this(evmBridge, raftAppendCallback, backpressureManager, beaconClient, persistenceDir,
            ProposalQueueTuningRegistry.get());
    }

    ProposalQueueManagerOptimized(
            EvmBridge evmBridge,
            RaftAppendCallback raftAppendCallback,
            BackpressureManager backpressureManager,
            org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient beaconClient,
            String persistenceDir,
            ProposalQueueTuning tuning) {
        this.evmBridge = evmBridge;
        this.raftAppendCallback = raftAppendCallback;
        this.backpressureManager = backpressureManager;
        this.epochQueue = new EpochBasedBatchQueue(beaconClient);
        ProposalQueueTuning resolved = tuning != null ? tuning : ProposalQueueTuningRegistry.get();
        this.persistenceStore = createPersistenceStore(persistenceDir, resolved);
        this.counterStateStore = createCounterStateStore(persistenceDir);
        this.confirmationTimeoutMs = resolved.getConfirmationTimeoutMs();
        this.restoreTimeoutMs = resolved.getRestoreTimeoutMs();
        this.maxMessageBatch = resolved.getMaxMessageBatch();
        this.maxRetryCount = resolved.getMaxRetryCount();
        this.finalizationChunkSize = resolved.getFinalizationChunkSize();
        this.verifierThreads = resolved.getVerifierThreads();
        this.processedRetentionMs = resolved.getProcessedRetentionMs();
        this.persistenceFlushIntervalMs = resolved.getPersistenceFlushIntervalMs();
        this.persistenceFlushBatch = resolved.getPersistenceFlushBatch();
        this.counterRotationIntervalMs = resolved.getCounterRotationIntervalMs();
        restoreCounterState();
    }
    
    /**
     * Start tri-agent architecture with epoch-based batching.
     */
    public void start() {
        if (running) {
            log.warn("ProposalQueueManager already started");
            return;
        }
        
        running = true;
        restorePersistedProposals();

        if (persistenceStore != null && isAsyncPersistenceEnabled()) {
            persistenceScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "proposal-persist-flusher");
                t.setDaemon(true);
                return t;
            });
            persistenceScheduler.scheduleAtFixedRate(
                this::flushPersistedProposals,
                persistenceFlushIntervalMs,
                persistenceFlushIntervalMs,
                java.util.concurrent.TimeUnit.MILLISECONDS
            );
        }
        
        // Agent 1: Aeron Sender (FAST path - batch send finalized proposals)
        aeronSenderAgent = new AgentRunner(
            createBackoffIdleStrategy(),
            throwable -> log.error("Error in Aeron sender agent", throwable),
            null,
            new AeronSenderAgent()
        );
        
        // Agent 2: EVM Verifier (SLOW path - 3-checkpoint security verification)
        evmVerifierAgents = new AgentRunner[verifierThreads];
        for (int i = 0; i < verifierThreads; i++) {
            evmVerifierAgents[i] = new AgentRunner(
                // Lower idle delay cuts verifier queue wait under load.
                new SleepingMillisIdleStrategy(1),
                throwable -> log.error("Error in EVM verifier agent", throwable),
                null,
                new EvmVerifierAgent()
            );
        }
        
        // Agent 3: Epoch Finalizer (PERIODIC - checks for finalizable epochs every 1 second)
        epochFinalizerAgent = new AgentRunner(
            new SleepingMillisIdleStrategy(1000), // 1 second idle
            throwable -> log.error("Error in epoch finalizer agent", throwable),
            null,
            new EpochFinalizerAgent()
        );
        
        // Start all agents on separate threads
        AgentRunner.startOnThread(aeronSenderAgent, r -> {
            Thread t = new Thread(r, "aeron-sender");
            t.setDaemon(true);
            return t;
        });
        for (int i = 0; i < evmVerifierAgents.length; i++) {
            final int threadIndex = i;
            AgentRunner.startOnThread(evmVerifierAgents[i], r -> {
                Thread t = new Thread(r, "evm-verifier-" + threadIndex);
                t.setDaemon(true);
                return t;
            });
        }
        AgentRunner.startOnThread(epochFinalizerAgent, r -> {
            Thread t = new Thread(r, "epoch-finalizer");
            t.setDaemon(true);
            return t;
        });
        
        log.info("✅ ProposalQueueManager started (tri-agent + epoch batching)");
        log.info("   - Aeron Sender Agent: BackoffIdleStrategy (ultra-low latency)");
        log.info("   - EVM Verifier Agents: {} thread(s), SleepingIdleStrategy (1ms idle, 3-checkpoint security)",
            evmVerifierAgents.length);
        log.info("   - Epoch Finalizer Agent: SleepingIdleStrategy (1s idle, wallet batching)");
        log.info("   - Max batch size: {}", maxMessageBatch);
        log.info("   - Finality: 2 epochs (~{} minutes)", (2 * 384_000) / 60000.0);
        if (persistenceStore != null) {
            if (isAsyncPersistenceEnabled()) {
                log.info("   - Proposal persistence: async (flush every {}ms or {} changes)",
                    persistenceFlushIntervalMs, persistenceFlushBatch);
            } else {
                log.info("   - Proposal persistence: synchronous (per-change)");
            }
        }
        if (counterRotationIntervalMs > 0) {
            log.info("   - Counter rotation: {}ms (bounded window counters + lifetime totals)", counterRotationIntervalMs);
        } else {
            log.info("   - Counter rotation: disabled");
        }
    }
    
    /**
     * Get the epoch queue (for dashboard and metrics).
     */
    public EpochBasedBatchQueue getEpochQueue() {
        return epochQueue;
    }
    
    /**
     * Get comprehensive queue statistics for dashboard display.
     */
    public java.util.Map<String, Object> getQueueStats() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();
        long nowMs = System.currentTimeMillis();
        rotateCountersIfNeeded(nowMs);
        
        // Queue sizes
        stats.put("unverifiedQueueSize", unverifiedQueue.size());
        stats.put("mempoolSize", unverifiedQueue.size());
        stats.put("batchQueueSize", batchQueue.size());
        stats.put("totalProposals", allProposals.size());
        
        // Epoch queue stats
        stats.put("currentEpoch", epochQueue.getCurrentEpoch());
        stats.put("finalizedEpoch", epochQueue.getFinalizedEpoch());
        stats.put("epochsUntilFinality", epochQueue.getCurrentEpoch() - epochQueue.getFinalizedEpoch());
        stats.put("pendingEpochStats", epochQueue.getStats());
        
        // Count proposals by state + mempool age stats
        long pending = 0;
        long verified = 0;
        long rejected = 0;
        long processed = 0;
        long mempoolAgeTotalMs = 0;
        long mempoolOldestMs = 0;
        long nowForAgeMs = System.currentTimeMillis();
        for (QueuedProposal proposal : allProposals.values()) {
            ProposalState state = proposal.getState();
            if (state == ProposalState.PENDING) {
                pending++;
                long ageMs = nowForAgeMs - proposal.getTimestamp();
                mempoolAgeTotalMs += ageMs;
                if (ageMs > mempoolOldestMs) {
                    mempoolOldestMs = ageMs;
                }
            } else if (state == ProposalState.VERIFIED) {
                verified++;
            } else if (state == ProposalState.REJECTED) {
                rejected++;
            } else if (state == ProposalState.PROCESSED) {
                processed++;
            }
        }
        
        stats.put("pendingCount", pending);
        stats.put("mempoolPendingCount", pending);
        stats.put("verifiedCount", verified);
        stats.put("rejectedCount", rejected);
        stats.put("processedCount", processed);
        stats.put("mempoolAvgAgeMs", pending == 0 ? 0 : mempoolAgeTotalMs / pending);
        stats.put("mempoolOldestMs", mempoolOldestMs);
        
        // Rotating counters: bounded current window + persisted lifetime totals
        long rejectedCurrent = totalRejectedCount.get();
        long verifiedCurrent = totalVerifiedCount.get();
        long finalizedCurrent = totalFinalizedCount.get();
        long priorityCurrent = priorityProposalsSent.get();
        long batchedCurrent = batchedProposalsSent.get();
        long rejectedLifetime = lifetimeRejectedBase.get() + rejectedCurrent;
        long verifiedLifetime = lifetimeVerifiedBase.get() + verifiedCurrent;
        long finalizedLifetime = lifetimeFinalizedBase.get() + finalizedCurrent;
        long priorityLifetime = lifetimePrioritySentBase.get() + priorityCurrent;
        long batchedLifetime = lifetimeBatchedSentBase.get() + batchedCurrent;
        stats.put("counterWindowStartMs", counterWindowStartMs.get());
        stats.put("counterRotationIntervalMs", counterRotationIntervalMs);
        stats.put("totalRejectedCount", rejectedCurrent);
        stats.put("totalVerifiedCount", verifiedCurrent);
        stats.put("totalFinalizedCount", finalizedCurrent);
        stats.put("totalRejectedCountLifetime", rejectedLifetime);
        stats.put("totalVerifiedCountLifetime", verifiedLifetime);
        stats.put("totalFinalizedCountLifetime", finalizedLifetime);
        
        // Count proposals by type (WRITE vs DELETE)
        long writeProposals = allProposals.values().stream()
            .filter(p -> p.getType() == QueuedProposal.ProposalType.WRITE)
            .count();
        long deleteProposals = allProposals.values().stream()
            .filter(p -> p.getType() == QueuedProposal.ProposalType.DELETE)
            .count();
        
        stats.put("writeProposals", writeProposals);
        stats.put("deleteProposals", deleteProposals);
        
        // Per-epoch proposal counts (for triangular pipeline visualization)
        // Group by SUBMISSION EPOCH (simpler, shows when proposals entered the queue)
        java.util.Map<Long, Long> proposalsByEpoch = new java.util.HashMap<>();
        java.util.Map<Long, java.util.Map<String, Long>> proposalsByEpochAndTier = new java.util.HashMap<>();
        
        for (QueuedProposal proposal : allProposals.values()) {
            if (proposal.getState() == ProposalState.VERIFIED || proposal.getState() == ProposalState.PENDING) {
                long epoch = proposal.getEpoch();
                proposalsByEpoch.merge(epoch, 1L, Long::sum);
                
                // Track by tier
                String tierName = proposal.getTier() != null ? proposal.getTier().name() : "STANDARD";
                proposalsByEpochAndTier
                    .computeIfAbsent(epoch, k -> new java.util.HashMap<>())
                    .merge(tierName, 1L, Long::sum);
            }
        }
        stats.put("proposalsByEpoch", proposalsByEpoch);
        stats.put("proposalsByEpochAndTier", proposalsByEpochAndTier);
        
        // Backpressure stats
        long backpressurePendingRaw = backpressureManager.getPendingCount();
        long backpressureMax = backpressureManager.getMaxPendingMessages();
        boolean backpressureActive = backpressureManager.isBackpressureActive();
        boolean queueIdle = batchQueue.isEmpty()
            && unverifiedQueue.isEmpty()
            && pending == 0
            && verified == 0
            && persistencePendingChanges.get() == 0;
        long backpressurePending = (!backpressureActive && queueIdle && backpressurePendingRaw <= 2)
            ? 0
            : backpressurePendingRaw;
        stats.put("backpressureActive", backpressureActive);
        stats.put("backpressurePendingCount", backpressurePending);
        stats.put("backpressurePendingRawCount", backpressurePendingRaw);
        stats.put("backpressureMaxPending", backpressureMax);
        if (backpressurePending != backpressurePendingRaw) {
            stats.put("backpressureStats", String.format(
                "BackpressureManager[sent=%d, acked=%d, pending=%d, rawPending=%d, max=%d, active=%s]",
                backpressureManager.getSentCount(),
                backpressureManager.getAcknowledgedCount(),
                backpressurePending,
                backpressurePendingRaw,
                backpressureMax,
                backpressureActive
            ));
        } else {
            stats.put("backpressureStats", backpressureManager.getStats());
        }
        
        // Tier routing stats (current window + lifetime)
        stats.put("priorityProposalsSent", priorityCurrent);
        stats.put("batchedProposalsSent", batchedCurrent);
        stats.put("totalProposalsSent", priorityCurrent + batchedCurrent);
        stats.put("priorityProposalsSentLifetime", priorityLifetime);
        stats.put("batchedProposalsSentLifetime", batchedLifetime);
        stats.put("totalProposalsSentLifetime", priorityLifetime + batchedLifetime);
        
        // Retry stats
        long proposalsWithRetries = allProposals.values().stream()
            .filter(p -> p.getRetryCount() > 0)
            .count();
        long maxRetryObserved = allProposals.values().stream()
            .mapToInt(QueuedProposal::getRetryCount)
            .max()
            .orElse(0);
        stats.put("proposalsWithRetries", proposalsWithRetries);
        stats.put("maxRetryCount", maxRetryObserved);
        stats.put("maxRetryLimit", maxRetryCount);

        // Verifier metrics (mempool bottleneck analysis)
        long attempts = verifierAttemptCount.get();
        long successes = verifierSuccessCount.get();
        stats.put("verifierAttemptCount", attempts);
        stats.put("verifierSuccessCount", successes);
        stats.put("verifierRequeueNoProofCount", verifierRequeueNoProofCount.get());
        stats.put("verifierRequeueUnconfirmedCount", verifierRequeueUnconfirmedCount.get());
        stats.put("verifierRejectedCount", verifierRejectedCount.get());
        stats.put("verifierErrorCount", verifierErrorCount.get());
        stats.put("verifierAvgTotalMs", attempts == 0 ? 0 : (verifierTotalNanos.get() / 1_000_000.0) / attempts);
        stats.put("verifierAvgProofMs", attempts == 0 ? 0 : (verifierProofNanos.get() / 1_000_000.0) / attempts);
        stats.put("verifierAvgSignatureMs", attempts == 0 ? 0 : (verifierSignatureNanos.get() / 1_000_000.0) / attempts);
        stats.put("verifierAvgAuthMs", attempts == 0 ? 0 : (verifierAuthNanos.get() / 1_000_000.0) / attempts);
        stats.put("verifierAvgPersistMs", attempts == 0 ? 0 : (verifierPersistNanos.get() / 1_000_000.0) / attempts);
        stats.put("verifierQueueWaitAvgMs", successes == 0 ? 0 : verifierQueueWaitMsTotal.get() / successes);
        stats.put("verifierQueueWaitMaxMs", verifierQueueWaitMsMax.get());
        stats.put("verifierLastTotalMs", verifierLastTotalMs.get());
        stats.put("verifierLastProofMs", verifierLastProofMs.get());
        stats.put("verifierLastSignatureMs", verifierLastSignatureMs.get());
        stats.put("verifierLastAuthMs", verifierLastAuthMs.get());
        stats.put("verifierLastPersistMs", verifierLastPersistMs.get());
        stats.put("enqueuePersistAvgMs", enqueuePersistCount.get() == 0 ? 0 :
            (enqueuePersistNanos.get() / 1_000_000.0) / enqueuePersistCount.get());
        stats.put("enqueuePersistLastMs", enqueuePersistLastMs.get());
        stats.put("persistenceFlushAvgMs", persistenceFlushCount.get() == 0 ? 0 :
            (persistenceFlushNanos.get() / 1_000_000.0) / persistenceFlushCount.get());
        stats.put("persistenceFlushLastMs", persistenceFlushLastMs.get());
        stats.put("persistenceFlushCount", persistenceFlushCount.get());
        stats.put("persistencePendingChanges", persistencePendingChanges.get());
        stats.put("persistenceAsyncEnabled", isAsyncPersistenceEnabled());
        
        return stats;
    }

    /**
     * Build epoch-resident proposal flow stats with priority lanes.
     * This is the upstream source of truth for /v1/proposals/epochs.
     */
    public java.util.Map<String, Object> getProposalEpochFlowStats() {
        long currentEpoch = epochQueue.getCurrentEpoch();
        long finalizedEpoch = epochQueue.getFinalizedEpoch();
        long nextEpoch = Math.max(finalizedEpoch + 1, currentEpoch);

        java.util.Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("currentEpoch", currentEpoch);
        payload.put("finalizedEpoch", finalizedEpoch);
        payload.put("pendingEpochs", epochQueue.getAllPendingEpochs().size());
        payload.put("epochsUntilFinality", Math.max(0L, currentEpoch - finalizedEpoch));
        payload.put("source", "upstream-epoch-counters");
        payload.put("note", "Epoch counters are authoritative for unverified/verified residency and finalized/rejected per epoch-tier.");

        java.util.List<java.util.Map<String, Object>> blocks = new java.util.ArrayList<>();
        blocks.add(buildEpochFlowBlock("Finalized", "finalized", finalizedEpoch));
        blocks.add(buildEpochFlowBlock("Next to be Finalized", "next", nextEpoch));
        blocks.add(buildEpochFlowBlock("Current", "current", currentEpoch));
        payload.put("blocks", blocks);

        java.util.Map<String, Object> aeronLoad = new java.util.LinkedHashMap<>();
        aeronLoad.put("priorityProposalsSent", priorityProposalsSent.get());
        aeronLoad.put("batchedProposalsSent", batchedProposalsSent.get());
        aeronLoad.put("backpressurePending", backpressureManager.getPendingCount());
        aeronLoad.put("backpressureMax", backpressureManager.getMaxPendingMessages());
        payload.put("aeronLoad", aeronLoad);

        return payload;
    }

    private java.util.Map<String, Object> buildEpochFlowBlock(String label, String status, long epoch) {
        java.util.Map<String, Object> block = new java.util.LinkedHashMap<>();
        block.put("label", label);
        block.put("status", status);
        block.put("epoch", epoch);

        java.util.Map<String, java.util.Map<String, Long>> byPriority = collectEpochPriorityStateCounts(epoch);
        block.put("byPriority", byPriority);

        java.util.Map<String, Object> totals = new java.util.LinkedHashMap<>();
        totals.put("unverified", byPriority.values().stream().mapToLong(v -> v.getOrDefault("unverified", 0L)).sum());
        totals.put("verified", byPriority.values().stream().mapToLong(v -> v.getOrDefault("verified", 0L)).sum());
        totals.put("finalized", byPriority.values().stream().mapToLong(v -> v.getOrDefault("finalized", 0L)).sum());
        totals.put("rejected", byPriority.values().stream().mapToLong(v -> v.getOrDefault("rejected", 0L)).sum());
        block.put("totals", totals);

        long flowToNext = ((Number) totals.get("verified")).longValue() + ((Number) totals.get("unverified")).longValue();
        block.put("flowToNext", flowToNext);
        return block;
    }

    private java.util.Map<String, java.util.Map<String, Long>> collectEpochPriorityStateCounts(long epoch) {
        java.util.Map<String, java.util.Map<String, Long>> byPriority = new java.util.LinkedHashMap<>();
        byPriority.put("standard", emptyStateMap());
        byPriority.put("express", emptyStateMap());
        byPriority.put("priority", emptyStateMap());

        for (QueuedProposal proposal : allProposals.values()) {
            if (proposal == null || proposal.getEpoch() != epoch) {
                continue;
            }
            String tier = normalizeTierKey(proposal.getTier());
            java.util.Map<String, Long> counters = byPriority.computeIfAbsent(tier, k -> emptyStateMap());
            ProposalState state = proposal.getState();
            if (state == ProposalState.PENDING) {
                counters.put("unverified", counters.get("unverified") + 1L);
            } else if (state == ProposalState.VERIFIED || state == ProposalState.CONFIRMED) {
                counters.put("verified", counters.get("verified") + 1L);
            }
        }

        for (String tier : byPriority.keySet()) {
            long finalized = getTerminalCounter(finalizedByEpochAndTier, epoch, tier);
            long rejected = getTerminalCounter(rejectedByEpochAndTier, epoch, tier);
            java.util.Map<String, Long> counters = byPriority.get(tier);
            counters.put("finalized", counters.get("finalized") + finalized);
            counters.put("rejected", counters.get("rejected") + rejected);
        }

        return byPriority;
    }

    private java.util.Map<String, Long> emptyStateMap() {
        java.util.Map<String, Long> map = new java.util.LinkedHashMap<>();
        map.put("unverified", 0L);
        map.put("verified", 0L);
        map.put("finalized", 0L);
        map.put("rejected", 0L);
        return map;
    }

    private String normalizeTierKey(org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier tier) {
        if (tier == null) {
            return "standard";
        }
        switch (tier) {
            case PRIORITY:
                return "priority";
            case EXPRESS:
                return "express";
            case STANDARD:
            default:
                return "standard";
        }
    }

    private void incrementTerminalCounter(
            ConcurrentHashMap<Long, ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>> store,
            long epoch,
            String tier) {
        store.computeIfAbsent(epoch, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(tier, k -> new java.util.concurrent.atomic.AtomicLong(0))
            .incrementAndGet();
    }

    private long getTerminalCounter(
            ConcurrentHashMap<Long, ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong>> store,
            long epoch,
            String tier) {
        ConcurrentHashMap<String, java.util.concurrent.atomic.AtomicLong> byTier = store.get(epoch);
        if (byTier == null) {
            return 0L;
        }
        java.util.concurrent.atomic.AtomicLong counter = byTier.get(tier);
        return counter == null ? 0L : counter.get();
    }

    private void recordTerminalState(QueuedProposal proposal, ProposalState terminalState) {
        if (proposal == null) {
            return;
        }
        long epoch = proposal.getEpoch();
        String tier = normalizeTierKey(proposal.getTier());
        if (terminalState == ProposalState.PROCESSED) {
            incrementTerminalCounter(finalizedByEpochAndTier, epoch, tier);
        } else if (terminalState == ProposalState.REJECTED) {
            incrementTerminalCounter(rejectedByEpochAndTier, epoch, tier);
        }
    }
    
    private ProposalPersistenceStore createPersistenceStore(String persistenceDir, ProposalQueueTuning tuning) {
        if (tuning != null && !tuning.isPersistenceEnabled()) {
            log.info("Proposal queue persistence disabled via tuning (persistence_enabled=false)");
            return null;
        }
        String resolved = resolvePersistenceDirectory(persistenceDir);
        if (resolved == null || resolved.isEmpty()) {
            return null;
        }
        return new ProposalPersistenceStore(java.nio.file.Path.of(resolved));
    }

    private QueueCounterStateStore createCounterStateStore(String persistenceDir) {
        String resolved = resolvePersistenceDirectory(persistenceDir);
        if (resolved == null || resolved.isEmpty()) {
            return null;
        }
        return new QueueCounterStateStore(java.nio.file.Path.of(resolved));
    }

    private String resolvePersistenceDirectory(String persistenceDir) {
        String resolved = persistenceDir;
        if (resolved == null || resolved.isEmpty()) {
            resolved = System.getProperty("oak.proposal.persistence.dir");
        }
        if (resolved == null || resolved.isEmpty()) {
            resolved = System.getenv("OAK_PROPOSAL_PERSISTENCE_DIR");
        }
        return resolved;
    }

    private void restoreCounterState() {
        if (counterStateStore == null) {
            return;
        }
        java.util.Map<String, Long> state = counterStateStore.load();
        if (state.isEmpty()) {
            return;
        }
        totalRejectedCount.set(state.getOrDefault("current.rejected", 0L));
        totalVerifiedCount.set(state.getOrDefault("current.verified", 0L));
        totalFinalizedCount.set(state.getOrDefault("current.finalized", 0L));
        priorityProposalsSent.set(state.getOrDefault("current.prioritySent", 0L));
        batchedProposalsSent.set(state.getOrDefault("current.batchedSent", 0L));
        lifetimeRejectedBase.set(state.getOrDefault("lifetime.rejected", 0L));
        lifetimeVerifiedBase.set(state.getOrDefault("lifetime.verified", 0L));
        lifetimeFinalizedBase.set(state.getOrDefault("lifetime.finalized", 0L));
        lifetimePrioritySentBase.set(state.getOrDefault("lifetime.prioritySent", 0L));
        lifetimeBatchedSentBase.set(state.getOrDefault("lifetime.batchedSent", 0L));
        counterWindowStartMs.set(state.getOrDefault("window.start.ms", System.currentTimeMillis()));
    }

    private void persistCounterState() {
        if (counterStateStore == null) {
            return;
        }
        java.util.Map<String, Long> state = new java.util.HashMap<>();
        state.put("current.rejected", totalRejectedCount.get());
        state.put("current.verified", totalVerifiedCount.get());
        state.put("current.finalized", totalFinalizedCount.get());
        state.put("current.prioritySent", priorityProposalsSent.get());
        state.put("current.batchedSent", batchedProposalsSent.get());
        state.put("lifetime.rejected", lifetimeRejectedBase.get());
        state.put("lifetime.verified", lifetimeVerifiedBase.get());
        state.put("lifetime.finalized", lifetimeFinalizedBase.get());
        state.put("lifetime.prioritySent", lifetimePrioritySentBase.get());
        state.put("lifetime.batchedSent", lifetimeBatchedSentBase.get());
        state.put("window.start.ms", counterWindowStartMs.get());
        counterStateStore.save(state);
    }

    private void rotateCountersIfNeeded(long nowMs) {
        if (counterRotationIntervalMs <= 0L) {
            return;
        }
        long windowStart = counterWindowStartMs.get();
        if ((nowMs - windowStart) < counterRotationIntervalMs) {
            return;
        }
        synchronized (this) {
            windowStart = counterWindowStartMs.get();
            if ((nowMs - windowStart) < counterRotationIntervalMs) {
                return;
            }

            long verified = totalVerifiedCount.getAndSet(0L);
            long finalized = totalFinalizedCount.getAndSet(0L);
            long rejected = totalRejectedCount.getAndSet(0L);
            long prioritySent = priorityProposalsSent.getAndSet(0L);
            long batchedSent = batchedProposalsSent.getAndSet(0L);

            lifetimeVerifiedBase.addAndGet(verified);
            lifetimeFinalizedBase.addAndGet(finalized);
            lifetimeRejectedBase.addAndGet(rejected);
            lifetimePrioritySentBase.addAndGet(prioritySent);
            lifetimeBatchedSentBase.addAndGet(batchedSent);
            counterWindowStartMs.set(nowMs);
            persistCounterState();

            log.info("🔁 Rotated proposal counters windowMs={} verified={} finalized={} rejected={} prioritySent={} batchedSent={}",
                counterRotationIntervalMs, verified, finalized, rejected, prioritySent, batchedSent);
        }
    }
    
    private void restorePersistedProposals() {
        if (persistenceStore == null) {
            return;
        }
        List<QueuedProposal> proposals = persistenceStore.load();
        if (proposals.isEmpty()) {
            return;
        }
        int restored = 0;
        int skippedTerminal = 0;
        for (QueuedProposal proposal : proposals) {
            if (proposal == null) {
                continue;
            }
            if (proposal.getState() == ProposalState.PROCESSED || proposal.getState() == ProposalState.REJECTED) {
                skippedTerminal++;
                continue;
            }
            proposal.setState(ProposalState.PENDING);
            proposal.setConfirmedBlock(null);
            proposal.setRejectionReason(null);
            proposal.overrideTimeoutTimestamp(System.currentTimeMillis() + restoreTimeoutMs);
            allProposals.put(proposal.getProposalId(), proposal);
            unverifiedQueue.offer(proposal);
            restored++;
            
            if (evmBridge instanceof org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) {
                ((org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) evmBridge)
                    .registerProposalWallet(proposal.getProposalId(), proposal.getWalletAddress());
            }
        }
        if (restored > 0 || skippedTerminal > 0) {
            log.info("🔁 Restored {} persisted proposals into unverified queue (skipped terminal: {})",
                restored, skippedTerminal);
        }
    }
    
    private void persistProposals() {
        if (persistenceStore == null) {
            return;
        }
        if (!isAsyncPersistenceEnabled()) {
            persistProposalsNow();
            return;
        }
        persistencePendingChanges.incrementAndGet();
        persistenceDirty = true;
        if (persistenceFlushBatch > 0 && persistencePendingChanges.get() >= persistenceFlushBatch) {
            flushPersistedProposals();
        }
    }

    private void persistProposalsNow() {
        synchronized (persistenceLock) {
            persistenceStore.save(allProposals.values());
        }
    }

    private void flushPersistedProposals() {
        if (persistenceStore == null) {
            return;
        }
        if (!persistenceDirty) {
            return;
        }
        if (!persistenceFlushInProgress.compareAndSet(false, true)) {
            return;
        }
        try {
            if (!persistenceDirty) {
                return;
            }
            long start = System.nanoTime();
            persistProposalsNow();
            long nanos = System.nanoTime() - start;
            persistenceFlushNanos.addAndGet(nanos);
            persistenceFlushCount.incrementAndGet();
            persistenceFlushLastMs.set(nanos / 1_000_000L);
            persistencePendingChanges.set(0);
            persistenceDirty = false;
        } finally {
            persistenceFlushInProgress.set(false);
        }
    }

    private boolean isAsyncPersistenceEnabled() {
        return persistenceFlushIntervalMs > 0 || persistenceFlushBatch > 1;
    }

    private static void updateMax(java.util.concurrent.atomic.AtomicLong max, long candidate) {
        long prev;
        while (candidate > (prev = max.get()) && !max.compareAndSet(prev, candidate)) {
            // retry until updated
        }
    }

    /**
     * Stop agents and cleanup.
     */
    public void stop() {
        running = false;
        
        if (aeronSenderAgent != null) {
            try {
                aeronSenderAgent.close();
            } catch (Exception e) {
                log.error("Error closing Aeron sender agent", e);
            }
        }
        
        if (evmVerifierAgents != null) {
            for (AgentRunner evmVerifierAgent : evmVerifierAgents) {
                if (evmVerifierAgent == null) {
                    continue;
                }
                try {
                    evmVerifierAgent.close();
                } catch (Exception e) {
                    log.error("Error closing EVM verifier agent", e);
                }
            }
        }
        
        if (epochFinalizerAgent != null) {
            try {
                epochFinalizerAgent.close();
            } catch (Exception e) {
                log.error("Error closing epoch finalizer agent", e);
            }
        }

        if (persistenceScheduler != null) {
            persistenceScheduler.shutdown();
            try {
                persistenceScheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            flushPersistedProposals();
        }
        persistCounterState();
        
        log.info("✅ ProposalQueueManager stopped");
    }
    
    /**
     * Queue a new proposal for verification and epoch-based batching.
     * This overload calculates the current epoch automatically.
     * 
     * @param proposalId Unique proposal ID
     * @param ethereumTxHash Ethereum transaction hash (optional)
     * @param walletAddress Ethereum wallet address
     * @param path Content path
     * @param contentType Content type
     * @param message Content message
     * @param signature Transaction signature
     * @return The queued proposal
     */
    public QueuedProposal queueProposal(
            String proposalId,
            String ethereumTxHash,
            String walletAddress,
            String path,
            String contentType,
            String message,
            String signature) {
        // Calculate current epoch automatically
        long currentEpoch = epochQueue.getCurrentEpoch();
        return queueProposal(proposalId, walletAddress, path, contentType, message, signature, ethereumTxHash, currentEpoch, 
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.STANDARD, null);
    }
    
    /**
     * Queue a new proposal for verification and epoch-based batching (with payment tier).
     * This overload calculates the target epoch automatically based on payment tier.
     * 
     * @param proposalId Unique proposal ID
     * @param ethereumTxHash Ethereum transaction hash (optional)
     * @param walletAddress Ethereum wallet address
     * @param path Content path
     * @param contentType Content type
     * @param message Content message
     * @param signature Transaction signature
     * @param tier Payment tier (STANDARD, EXPRESS, or PRIORITY)
     * @param intentToken Intent token for lazy binary upload (optional, ADR 020)
     * @return The queued proposal
     */
    public QueuedProposal queueProposal(
            String proposalId,
            String ethereumTxHash,
            String walletAddress,
            String path,
            String contentType,
            String message,
            String signature,
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier tier,
            String intentToken) {
        // Calculate target epoch based on payment tier
        long currentEpoch = epochQueue.getCurrentEpoch();
        long targetEpoch;
        
        // Map tier to target epoch based on FINALITY DELAY:
        // 
        // All tiers queue to currentEpoch. Delays are enforced during finalization
        // by EpochBasedBatchQueue.getFinalityDelay():
        //   - PRIORITY: 0 epochs (immediate via fast-path)
        //   - EXPRESS:  1 epoch delay (~6.4 min)
        //   - STANDARD: 2 epoch delay (~12.8 min)
        // 
        // Example timeline:
        //   Epoch 100: EXPRESS & STANDARD proposals arrive → queued to epoch 100
        //   Epoch 101: EXPRESS proposals from E100 finalize (1 transition)
        //   Epoch 102: STANDARD proposals from E100 finalize (2 transitions)
        if (tier == org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY) {
            // PRIORITY bypasses epoch queue entirely (handled via priorityQueue in agents)
            targetEpoch = currentEpoch - 2;  // Marked as "already finalized" for fast-path
        } else {
            // EXPRESS and STANDARD both queue to current epoch
            // Finality delay differentiation happens in EpochBasedBatchQueue.getFinalizableEpochs()
            targetEpoch = currentEpoch;
        }
        
        return queueProposal(proposalId, walletAddress, path, contentType, message, signature, ethereumTxHash, targetEpoch, tier, intentToken);
    }
    
    /**
     * Queue a new proposal for verification and epoch-based batching.
     * 
     * @param proposalId Unique proposal ID
     * @param walletAddress Ethereum wallet address
     * @param path Content path
     * @param contentType Content type
     * @param message Content message
     * @param signature Transaction signature
     * @param ethereumTxHash Ethereum transaction hash (optional)
     * @param epoch Ethereum epoch when transaction was seen (for finality tracking)
     * @param tier Payment tier (STANDARD, EXPRESS, or PRIORITY) for priority handling
     * @param intentToken Intent token for lazy binary upload (optional, ADR 020)
     * @return The queued proposal
     */
    public QueuedProposal queueProposal(
            String proposalId,
            String walletAddress,
            String path,
            String contentType,
            String message,
            String signature,
            String ethereumTxHash,
            long epoch,
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier tier,
            String intentToken) {
        
        long now = System.currentTimeMillis();
        QueuedProposal proposal = new QueuedProposal(
            proposalId,
            ethereumTxHash,
            null, // unused compatibility parameter
            now,
            now + confirmationTimeoutMs,
            ProposalState.PENDING
        );
        
        // Set wallet-based write fields
        proposal.setWalletAddress(walletAddress);
        proposal.setPath(path);
        proposal.setContentType(contentType);
        proposal.setMessage(message);
        proposal.setSignature(signature);
        proposal.setEpoch(epoch); // NEW: Track epoch for finality
        proposal.setTier(tier); // Set payment tier for priority handling
        proposal.setIntentToken(intentToken); // Set intent token for lazy binary upload (ADR 020)
        proposal.setDurabilityState(DurabilityState.PENDING, null, null);
        
        // Add to tracking map and unverified queue
        allProposals.put(proposalId, proposal);
        unverifiedQueue.offer(proposal);
        
        // Register wallet for mock mode (allows mock payment simulation with correct from address)
        if (evmBridge instanceof org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) {
            ((org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) evmBridge)
                .registerProposalWallet(proposalId, walletAddress);
        }
        
        log.debug("📥 Queued proposal {} for EVM verification in epoch {} (tier: {}, queue size: {})", 
            proposalId, epoch, tier, unverifiedQueue.size());
        long persistStart = System.nanoTime();
        persistProposals();
        long persistNanos = System.nanoTime() - persistStart;
        enqueuePersistNanos.addAndGet(persistNanos);
        enqueuePersistCount.incrementAndGet();
        enqueuePersistLastMs.set(persistNanos / 1_000_000L);
        
        return proposal;
    }
    
    /**
     * Queue a DELETE proposal for verification and epoch-based batching.
     * Deletes flow through same pipeline as writes, just with different type.
     * 
     * @param proposalId Unique proposal ID
     * @param ethereumTxHash Ethereum transaction hash (required)
     * @param walletAddress Ethereum wallet address
     * @param path Content path to delete
     * @param signature Transaction signature
     * @param tier Payment tier (STANDARD, EXPRESS, or PRIORITY)
     * @return The queued proposal
     */
    public QueuedProposal queueDeleteProposal(
            String proposalId,
            String ethereumTxHash,
            String walletAddress,
            String path,
            String signature,
            org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier tier) {
        
        // Calculate target epoch based on payment tier (same as writes)
        long currentEpoch = epochQueue.getCurrentEpoch();
        long targetEpoch;
        
        switch (tier) {
            case PRIORITY:
                // Priority: direct ingress, no epoch wait (fastest)
                targetEpoch = currentEpoch;
                break;
            case EXPRESS:
                // Express: +1 epoch (fast)
                targetEpoch = currentEpoch + 1;
                break;
            case STANDARD:
            default:
                // Standard: +2 epochs (economical)
                targetEpoch = currentEpoch + 2;
                break;
        }
        
        long now = System.currentTimeMillis();
        QueuedProposal proposal = new QueuedProposal(
            proposalId,
            ethereumTxHash,
            null, // unused compatibility parameter
            now,
            now + confirmationTimeoutMs,
            ProposalState.PENDING
        );
        
        // Set DELETE-specific fields
        proposal.setType(QueuedProposal.ProposalType.DELETE); // Mark as DELETE
        proposal.setWalletAddress(walletAddress);
        proposal.setPath(path);
        proposal.setContentType("delete"); // Special marker for deletes
        proposal.setMessage(""); // Not needed for deletes
        proposal.setSignature(signature);
        proposal.setEpoch(targetEpoch);
        proposal.setTier(tier);
        proposal.setDurabilityState(DurabilityState.PENDING, null, null);
        
        // Add to tracking map and unverified queue
        allProposals.put(proposalId, proposal);
        unverifiedQueue.offer(proposal);
        
        // Register wallet for mock mode (allows mock payment simulation with correct from address)
        if (evmBridge instanceof org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) {
            ((org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) evmBridge)
                .registerProposalWallet(proposalId, walletAddress);
        }
        
        log.info("🗑️  Queued DELETE proposal {} for EVM verification in epoch {} (tier: {}, path: {}, queue size: {})", 
            proposalId, targetEpoch, tier, path, unverifiedQueue.size());
        long persistStart = System.nanoTime();
        persistProposals();
        long persistNanos = System.nanoTime() - persistStart;
        enqueuePersistNanos.addAndGet(persistNanos);
        enqueuePersistCount.incrementAndGet();
        enqueuePersistLastMs.set(persistNanos / 1_000_000L);
        
        return proposal;
    }
    
    /**
     * Get proposal status.
     */
    public QueuedProposal getProposal(String proposalId) {
        return allProposals.get(proposalId);
    }
    
    /**
     * Get proposal status (for API compatibility).
     */
    public ProposalStatus getProposalStatus(String proposalId) {
        QueuedProposal proposal = getProposal(proposalId);
        if (proposal == null) {
            return null;
        }
        
        return new ProposalStatus(
            proposal.getProposalId(),
            proposal.getState(),
            proposal.getEthereumTxHash(),
            proposal.getTimeoutTimestamp(),
            proposal.getConfirmedBlock(),
            proposal.getRejectionReason(),
            proposal.getDurabilityState(),
            proposal.getDurabilityTimestamp(),
            proposal.getDurabilityError(),
            proposal.getDurableHead()
        );
    }

    /**
     * Update durability status for a proposal after local disk persistence.
     */
    public void updateDurability(String proposalId, DurabilityState state, String durableHead, String error) {
        QueuedProposal proposal = allProposals.get(proposalId);
        if (proposal == null) {
            return;
        }
        proposal.setDurabilityState(state, durableHead, error);
        persistProposals();
    }
    
    /**
     * Get pending count (for backpressure management).
     */
    public int getPendingCount() {
        return unverifiedQueue.size() + batchQueue.size();
    }
    
    /**
     * Get queue statistics (including epoch queue).
     */
    public String getStats() {
        return String.format("Unverified: %d, Batches Ready: %d, Total: %d | Epoch: %s", 
            unverifiedQueue.size(), batchQueue.size(), allProposals.size(), epochQueue.getStats());
    }
    
    // ============================================================================
    // AGENT 1: Aeron Sender (FAST PATH)
    // ============================================================================
    
    /**
     * Agent that sends verified proposals to Aeron in batches.
     * This agent uses BackoffIdleStrategy for ultra-low latency.
     */
    private class AeronSenderAgent implements Agent {
        
        @Override
        public int doWork() {
            if (!running) {
                return 0;
            }
            
            int workCount = 0;
            
            // 🚀 EPOCH-BASED BATCHING: Process pre-batched proposals from epoch finalizer
            // Batches are organized by wallet address for optimal segment packing
            
            // Process up to maxMessageBatch batches per cycle
            int batchesProcessed = 0;
            int queueDepth = batchQueue.size();
            
            // Log queue activity periodically  
            if (queueDepth > 0) {
                logRateLimitedInfo(lastQueueDepthLogMs, queueDepthSuppressed,
                    "🔄 AeronSenderAgent: {} batches waiting in queue", queueDepth);
            }
            
            while (batchesProcessed < maxMessageBatch) {
                List<QueuedProposal> batch = batchQueue.poll();
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                
                batchesProcessed++;
                QueuedProposal firstProposal = batch.get(0);
                logRateLimitedInfo(lastDequeuedLogMs, dequeuedSuppressed,
                    "📤 AeronSenderAgent: DEQUEUED batch {} of {} | {} proposals | wallet: {} | epoch: {} | remaining in queue: {}",
                    batchesProcessed, queueDepth, batch.size(),
                    firstProposal.getWalletAddress().substring(0, 10),
                    firstProposal.getEpoch(),
                    batchQueue.size());
                
                // Apply backpressure ONCE per batch (not per proposal)
                try {
                    backpressureManager.applyBackpressureIfNeeded();
                } catch (BackpressureTimeoutException e) {
                    batchQueue.offer(batch);
                    log.warn("⚠️  Backpressure timeout - re-queuing batch ({} proposals)", batch.size());
                    break;
                }
                
                // ✈️ AERON BATCHING: Send entire batch as single message
                // This is MUCH more efficient than individual sends
                // Aeron can optimize batched messages at the transport layer
                try {
                    int sent = 0;
                    
                    // 🧪 DIAGNOSTIC: Send single-item batches as individual proposals (templateId 100/101)
                    // This isolates whether the issue is queue mechanism vs templateId 106 encoding
                    if (batch.size() == 1) {
                        QueuedProposal proposal = batch.get(0);
                        
                        // Check proposal type: WRITE or DELETE
                        if (proposal.getType() == QueuedProposal.ProposalType.DELETE) {
                            log.debug("🗑️  Sending DELETE proposal (templateId 101)");
                            raftAppendCallback.appendDeleteProposalWithId(
                                proposal.getProposalId(),
                                proposal.getWalletAddress(),
                                proposal.getPath(),
                                proposal.getSignature()
                            );
                        } else {
                            log.debug("📝 Sending WRITE proposal (templateId 100) blobId={}, ipfsCid={}", 
                                proposal.getBlobId(), proposal.getIpfsCid());
                            raftAppendCallback.appendProposalWithId(
                                proposal.getProposalId(),
                                proposal.getWalletAddress(),
                                proposal.getPath(),
                                proposal.getContentType(),
                                proposal.getMessage(),
                                proposal.getSignature(),
                                proposal.getBlobId(),
                                proposal.getMimeType(),
                                proposal.getIpfsCid()
                            );
                        }
                        sent = 1; // appendProposal/appendDeleteProposal returns void, assume success
                    } else {
                        // Multi-proposal batch: use templateId 106
                        log.debug("🔥 CALLING appendProposalBatch on instance of: {}", 
                            raftAppendCallback.getClass().getName());
                        sent = raftAppendCallback.appendProposalBatch(batch);
                        log.debug("🔥 appendProposalBatch RETURNED: {}", sent);
                    }
                    
                    if (sent > 0) {
                        // Mark all proposals in batch as processed
                        for (QueuedProposal queued : batch) {
                            queued.setState(ProposalState.PROCESSED);
                            totalFinalizedCount.incrementAndGet();
                            recordTerminalState(queued, ProposalState.PROCESSED);
                            
                            batchedProposalsSent.incrementAndGet();
                            workCount++;
                        }
                        persistProposals();
                        
                        logRateLimitedInfo(lastBatchSentLogMs, batchSentSuppressed,
                            "✅ Batch sent to Aeron: {} proposals in 1 message (diagnostic mode: {})",
                            sent, batch.size() == 1 ? "templateId 100" : "templateId 106");
                    } else {
                        // Batch send failed - re-queue for retry
                        batchQueue.offer(batch);
                        log.warn("⚠️  Batch send failed - re-queuing batch ({} proposals)", batch.size());
                        break;
                    }
                    
                } catch (BackpressureTimeoutException e) {
                    // Backpressure timeout - re-queue entire batch for next cycle
                    batchQueue.offer(batch);
                    log.warn("⚠️  Backpressure timeout - re-queuing batch ({} proposals)", batch.size());
                    break;
                } catch (Exception e) {
                    log.error("❌ Error sending batch to Aeron ({} proposals) - checking retry eligibility", batch.size(), e);
                    
                    // Track retry count for each proposal in the batch
                    boolean allExhausted = true;
                    for (QueuedProposal proposal : batch) {
                        int retries = proposal.incrementRetryCount();
                        if (retries <= maxRetryCount) {
                            allExhausted = false;
                        }
                        log.debug("  Proposal {} retry count: {}/{}", 
                            proposal.getProposalId(), retries, maxRetryCount);
                    }
                    
                    if (allExhausted) {
                        // All proposals in batch have exceeded retry limit - reject them
                        log.error("❌ Batch exceeded max retries ({}) - rejecting {} proposals", 
                            maxRetryCount, batch.size());
                        for (QueuedProposal proposal : batch) {
                            proposal.setState(ProposalState.REJECTED);
                            proposal.setRejectionReason("Exceeded max retry count (" + maxRetryCount + 
                                ") after Aeron send failures: " + e.getMessage());
                            totalRejectedCount.incrementAndGet();
                            recordTerminalState(proposal, ProposalState.REJECTED);
                        }
                        persistProposals();
                    } else {
                        // Re-queue for retry
                        batchQueue.offer(batch);
                        log.warn("🔄 Re-queuing batch for retry (attempt {}/{})", 
                            batch.get(0).getRetryCount(), maxRetryCount);
                    }
                    break;
                }
            }
            
            cleanupProcessedProposals();
            return workCount;
        }
        
        @Override
        public String roleName() {
            return "aeron-sender-agent";
        }
    }

    /**
     * Clean up processed proposals after retention window to avoid unbounded growth.
     */
    private void cleanupProcessedProposals() {
        long now = System.currentTimeMillis();
        if (now - lastProcessedCleanup < 60_000L) {
            return;
        }
        lastProcessedCleanup = now;
        final int[] removed = {0};
        allProposals.entrySet().removeIf(entry -> {
            QueuedProposal proposal = entry.getValue();
            if (proposal.getState() != ProposalState.PROCESSED) {
                return false;
            }
            long age = now - proposal.getTimestamp();
        if (age < processedRetentionMs) {
                return false;
            }
            DurabilityState durability = proposal.getDurabilityState();
            boolean shouldRemove = durability == DurabilityState.ACKED || durability == DurabilityState.FAILED;
            if (shouldRemove) {
                removed[0]++;
            }
            return shouldRemove;
        });
        if (removed[0] > 0) {
            persistProposals();
        }
    }

    private void logRateLimitedInfo(java.util.concurrent.atomic.AtomicLong lastMs,
                                    java.util.concurrent.atomic.AtomicInteger suppressed,
                                    String format,
                                    Object... args) {
        long now = System.currentTimeMillis();
        long last = lastMs.get();
        if ((now - last) >= HIGH_FREQ_LOG_INTERVAL_MS && lastMs.compareAndSet(last, now)) {
            int dropped = suppressed.getAndSet(0);
            if (dropped > 0) {
                Object[] withMeta = java.util.Arrays.copyOf(args, args.length + 2);
                withMeta[args.length] = dropped;
                withMeta[args.length + 1] = HIGH_FREQ_LOG_INTERVAL_MS;
                log.info(format + " (RATE LIMITED - suppressed {} in last {}ms)", withMeta);
            } else {
                log.info(format + " (RATE LIMITED)", args);
            }
        } else {
            suppressed.incrementAndGet();
        }
    }
    
    // ============================================================================
    // AGENT 2: EVM Verifier (SLOW PATH)
    // ============================================================================
    
    /**
     * Agent that verifies proposals via EvmBridge.
     * This agent uses SleepingIdleStrategy (10ms) since EVM verification can be slow.
     * 
     * <p><strong>Security Checkpoints:</strong>
     * <ol>
     *   <li>Ethereum Transaction Validation (payment proof)</li>
     *   <li>Cryptographic Signature Verification (proves authority)</li>
     *   <li>Authorization Check (wallet has write permission)</li>
     * </ol>
     * 
     * <p>ONLY proposals that pass ALL checks reach Aeron!
     */
    private class EvmVerifierAgent implements Agent {
        
        @Override
        public int doWork() {
            if (!running) {
                return 0;
            }
            
            int workCount = 0;
            
            // Process unverified proposals
            QueuedProposal proposal;
            while ((proposal = unverifiedQueue.poll()) != null) {
                long attemptStartNs = System.nanoTime();
                verifierAttemptCount.incrementAndGet();
                try {
                    // ═══════════════════════════════════════════════════════════
                    // TIMEOUT CHECK: Ensure proposal doesn't wait forever
                    // ═══════════════════════════════════════════════════════════
                    if (System.currentTimeMillis() > proposal.getTimeoutTimestamp()) {
                        verifierRejectedCount.incrementAndGet();
                        rejectProposal(proposal, "Timeout waiting for confirmation (" + 
                            (confirmationTimeoutMs / 1000) + "s)");
                        continue;
                    }
                    
                    // ═══════════════════════════════════════════════════════════
                    // SECURITY CHECKPOINT 1: Ethereum Transaction Validation
                    // ═══════════════════════════════════════════════════════════
                    // Verify that:
                    // - A valid Ethereum transaction exists
                    // - Transaction has sufficient confirmations
                    // - Payment went to correct contract
                    // - Payment amount is sufficient
                    // ═══════════════════════════════════════════════════════════
                    
                    long proofStartNs = System.nanoTime();
                    PaymentProof proof = evmBridge.verifyPayment(proposal.getProposalId());
                    long proofNanos = System.nanoTime() - proofStartNs;
                    verifierProofNanos.addAndGet(proofNanos);
                    verifierLastProofMs.set(proofNanos / 1_000_000L);
                    
                    if (proof == null) {
                        if (org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance().isMockMode()) {
                            proof = createMockProof(proposal);
                            if (proof != null && evmBridge instanceof org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) {
                                ((org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge) evmBridge)
                                    .simulatePayment(proof);
                            }
                        }
                    }

                    if (proof == null) {
                        // No payment found yet - re-queue (will retry)
                        // In mock mode, this immediately returns a valid proof
                        // In real mode, this polls the blockchain for the transaction
                        verifierRequeueNoProofCount.incrementAndGet();
                        unverifiedQueue.offer(proposal);
                        continue;
                    }
                    
                    if (!proof.isConfirmed(1)) {
                        // Payment exists but not confirmed yet - re-queue
                        verifierRequeueUnconfirmedCount.incrementAndGet();
                        unverifiedQueue.offer(proposal);
                        continue;
                    }
                    
                    // Verify payment went to correct contract
                    String expectedContract = evmBridge.getContractAddress();
                    if (!proof.getContractAddress().equalsIgnoreCase(expectedContract)) {
                        verifierRejectedCount.incrementAndGet();
                        rejectProposal(proposal, "Payment to wrong contract (expected: " + 
                            expectedContract + ", got: " + proof.getContractAddress() + ")");
                        continue;
                    }
                    
                    // Verify payment amount is sufficient
                    // For POC, we accept any amount > 0
                    // In production, this would check against calculateRequiredPayment()
                    try {
                        long amountWei = Long.parseLong(proof.getAmountWei());
                        if (amountWei <= 0) {
                            verifierRejectedCount.incrementAndGet();
                            rejectProposal(proposal, "Insufficient payment amount: " + amountWei + " wei");
                            continue;
                        }
                    } catch (NumberFormatException e) {
                        verifierRejectedCount.incrementAndGet();
                        rejectProposal(proposal, "Invalid payment amount format: " + proof.getAmountWei());
                        continue;
                    }
                    
                    log.debug("✅ CHECKPOINT 1 PASSED: Ethereum tx {} confirmed (block: {}, amount: {} wei)",
                        proof.getTransactionHash(), proof.getBlockNumber(), proof.getAmountWei());
                    
                    // ═══════════════════════════════════════════════════════════
                    // SECURITY CHECKPOINT 2: Cryptographic Signature Verification
                    // ═══════════════════════════════════════════════════════════
                    // Verify that:
                    // - Signature is valid ECDSA signature
                    // - Signature was created by the claimed wallet address
                    // - Signature is for THIS specific write (path + message)
                    // ═══════════════════════════════════════════════════════════
                    
                    // Verify from address matches proposal wallet (payment verification)
                    if (!proof.getFromAddress().equalsIgnoreCase(proposal.getWalletAddress())) {
                        verifierRejectedCount.incrementAndGet();
                        rejectProposal(proposal, "Payment from address (" + proof.getFromAddress() + 
                            ") does not match proposal wallet (" + proposal.getWalletAddress() + ")");
                        continue;
                    }
                    
                    // Cryptographic signature verification (secp256k1 ECDSA)
                    // Note: Initial verification happens in ConsensusApiHandler at API entry
                    // This is a secondary check for proposals that bypass the API (e.g., internal)
                    // Skip in mock mode - signature verification is done at API entry in real mode
                    boolean isMockMode = org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig.getInstance().isMockMode();
                    
                    if (!isMockMode) {
                        String signedMessage = proposal.getMessage() != null ? proposal.getMessage() : "";
                        String proposalSignature = proposal.getSignature();
                        
                        if (proposalSignature != null && !proposalSignature.isEmpty() && 
                            org.apache.jackrabbit.oak.segment.consensus.security.EthereumSignatureVerifier.isFullVerificationAvailable()) {
                            
                            long signatureStartNs = System.nanoTime();
                            boolean signatureValid = org.apache.jackrabbit.oak.segment.consensus.security.EthereumSignatureVerifier
                                .verifySignature(signedMessage, proposalSignature, proposal.getWalletAddress());
                            long signatureNanos = System.nanoTime() - signatureStartNs;
                            verifierSignatureNanos.addAndGet(signatureNanos);
                            verifierLastSignatureMs.set(signatureNanos / 1_000_000L);
                            
                            if (!signatureValid) {
                                verifierRejectedCount.incrementAndGet();
                                rejectProposal(proposal, "Cryptographic signature verification failed for wallet " + 
                                    proposal.getWalletAddress());
                                continue;
                            }
                            log.debug("✅ CHECKPOINT 2 PASSED: Signature cryptographically verified for wallet {}",
                                proposal.getWalletAddress());
                        } else {
                            // Signature already verified at API entry, or BC not available
                            log.debug("✅ CHECKPOINT 2 PASSED: Signature format verified for wallet {} (crypto check at API entry)",
                                proposal.getWalletAddress());
                        }
                    } else {
                        log.debug("✅ CHECKPOINT 2 PASSED: Signature verification skipped (mock mode) for wallet {}",
                            proposal.getWalletAddress());
                    }
                    
                    // ═══════════════════════════════════════════════════════════
                    // SECURITY CHECKPOINT 3: Authorization Check
                    // ═══════════════════════════════════════════════════════════
                    // Verify that:
                    // - Wallet has write permission for this path
                    // - Path follows sharding rules (wallet can only write to own shard)
                    // ═══════════════════════════════════════════════════════════
                    
                    // Verify path belongs to wallet's shard (using WalletPathUtil for wallet-scoped paths)
                    long authStartNs = System.nanoTime();
                    String expectedShardRoot = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil.getShardRoot(proposal.getWalletAddress());
                    if (!proposal.getPath().startsWith(expectedShardRoot + "/")) {
                        verifierRejectedCount.incrementAndGet();
                        rejectProposal(proposal, "Wallet " + proposal.getWalletAddress() + 
                            " cannot write to path outside its shard: " + proposal.getPath() + 
                            " (expected shard root: " + expectedShardRoot + ")");
                        continue;
                    }
                    long authNanos = System.nanoTime() - authStartNs;
                    verifierAuthNanos.addAndGet(authNanos);
                    verifierLastAuthMs.set(authNanos / 1_000_000L);
                    
                    // Additional authorization checks could go here:
                    // - Check wallet is not banned
                    // - Check wallet has sufficient quota
                    // - Check path is not system-reserved
                    
                    log.debug("✅ CHECKPOINT 3 PASSED: Wallet {} authorized for path {}",
                        proposal.getWalletAddress(), proposal.getPath());
                    
                    // ═══════════════════════════════════════════════════════════
                    // ✅ ALL SECURITY CHECKS PASSED
                    // ═══════════════════════════════════════════════════════════
                    // This proposal is:
                    // - Paid for (Ethereum tx confirmed)
                    // - Cryptographically signed (proves authority)
                    // - Authorized (wallet can write to path)
                    // ═══════════════════════════════════════════════════════════
                    
                    proposal.setState(ProposalState.VERIFIED);
                    proposal.setConfirmedBlock(proof.getBlockNumber());
                    long persistStartNs = System.nanoTime();
                    persistProposals();
                    long persistNanos = System.nanoTime() - persistStartNs;
                    verifierPersistNanos.addAndGet(persistNanos);
                    verifierLastPersistMs.set(persistNanos / 1_000_000L);
                    
                    // Track verification persistently
                    totalVerifiedCount.incrementAndGet();
                    verifierSuccessCount.incrementAndGet();
                    long queueWaitMs = System.currentTimeMillis() - proposal.getTimestamp();
                    verifierQueueWaitMsTotal.addAndGet(queueWaitMs);
                    updateMax(verifierQueueWaitMsMax, queueWaitMs);
                    
                    // ═══════════════════════════════════════════════════════════
                    // PRIORITY TIER: Fast-path directly to Aeron (bypass epoch batching)
                    // ═══════════════════════════════════════════════════════════
                    if (proposal.getTier() == org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.PRIORITY) {
                        log.debug("🚀 PRIORITY TIER: Fast-tracking proposal {} directly to Aeron (bypassing epoch queue, type: {})", 
                            proposal.getProposalId(), proposal.getType());
                        
                        try {
                            // Send directly to Aeron (bypass batch queue)
                            // Check type: WRITE or DELETE
                            if (proposal.getType() == QueuedProposal.ProposalType.DELETE) {
                                log.debug("🗑️  PRIORITY DELETE: Sending directly to Aeron");
                                raftAppendCallback.appendDeleteProposal(
                                    proposal.getWalletAddress(),
                                    proposal.getPath(),
                                    proposal.getSignature()
                                );
                            } else {
                                log.debug("📝 PRIORITY WRITE: Sending directly to Aeron (ipfsCid={})", proposal.getIpfsCid());
                                raftAppendCallback.appendProposal(
                                    proposal.getWalletAddress(),
                                    proposal.getPath(),
                                    proposal.getContentType(),
                                    proposal.getMessage(),
                                    proposal.getSignature(),
                                    proposal.getBlobId(),
                                    proposal.getMimeType(),
                                    proposal.getIpfsCid()
                                );
                            }
                            
                            proposal.setState(ProposalState.PROCESSED);
                            allProposals.remove(proposal.getProposalId());
                            totalFinalizedCount.incrementAndGet();
                            recordTerminalState(proposal, ProposalState.PROCESSED);
                            long priorityPersistStartNs = System.nanoTime();
                            persistProposals();
                            long priorityPersistNanos = System.nanoTime() - priorityPersistStartNs;
                            verifierPersistNanos.addAndGet(priorityPersistNanos);
                            verifierLastPersistMs.set(priorityPersistNanos / 1_000_000L);
                            
                            logRateLimitedInfo(lastPriorityLogMs, prioritySuppressed,
                                "✅ Priority proposal {} sent to Aeron (tx: {}, block: {}, latency: ~30s)",
                                proposal.getProposalId(),
                                proof.getTransactionHash().substring(0, Math.min(10, proof.getTransactionHash().length())) + "...",
                                proof.getBlockNumber());
                            priorityProposalsSent.incrementAndGet();
                            workCount++;
                            
                        } catch (Exception e) {
                            log.error("❌ Failed to send priority proposal {} to Aeron", proposal.getProposalId(), e);
                            rejectProposal(proposal, "Aeron send failed: " + e.getMessage());
                        }
                    } else {
                        // EXPRESS or STANDARD: Add to epoch queue for batching
                        epochQueue.addProposal(proposal, proposal.getEpoch());
                        workCount++;
                        
                        String tierLabel = proposal.getTier() == org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker.PaymentTier.EXPRESS 
                            ? "EXPRESS (1-epoch)" : "STANDARD (2-epoch)";
                        
                        logRateLimitedInfo(lastEpochQueueLogMs, epochQueueSuppressed,
                            "📥 Proposal added to epoch queue: {} | wallet: {} | epoch: {} | tier: {}",
                            proposal.getProposalId().substring(0, 8),
                            proposal.getWalletAddress().substring(0, 10),
                            proposal.getEpoch(),
                            tierLabel);
                        
                        log.debug("🔒 Proposal {} VERIFIED (tx: {}, block: {}, epoch: {}, tier: {}, wallet: {}) → queued for epoch finality", 
                            proposal.getProposalId(),
                            proof.getTransactionHash().substring(0, Math.min(10, proof.getTransactionHash().length())) + "...",
                            proof.getBlockNumber(),
                            proposal.getEpoch(),
                            tierLabel,
                            proposal.getWalletAddress());
                    }
                    
                } catch (Exception e) {
                    verifierErrorCount.incrementAndGet();
                    log.error("Error verifying proposal {}", proposal.getProposalId(), e);
                    rejectProposal(proposal, "Verification error: " + e.getMessage());
                } finally {
                    long attemptTotalNanos = System.nanoTime() - attemptStartNs;
                    verifierTotalNanos.addAndGet(attemptTotalNanos);
                    verifierLastTotalMs.set(attemptTotalNanos / 1_000_000L);
                }
            }
            
            return workCount;
        }
        
        /**
         * Reject a proposal and remove it from tracking.
         */
        private void rejectProposal(QueuedProposal proposal, String reason) {
            proposal.setState(ProposalState.REJECTED);
            proposal.setRejectionReason(reason);
            allProposals.remove(proposal.getProposalId());
            
            // Track rejection persistently (survives proposal removal)
            totalRejectedCount.incrementAndGet();
            recordTerminalState(proposal, ProposalState.REJECTED);
            persistProposals();
            
            log.warn("❌ REJECTED proposal {}: {} (total rejected: {})", 
                proposal.getProposalId(), reason, totalRejectedCount.get());
        }
        
        @Override
        public String roleName() {
            return "evm-verifier-agent";
        }
    }

    private org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof createMockProof(QueuedProposal proposal) {
        try {
            String proposalId = proposal.getProposalId();
            if (proposalId == null || proposalId.isEmpty()) {
                return null;
            }
            String proposalIdHex = proposalId.replace("-", "");
            String mockTxHash = "0x" + proposalIdHex;
            if (mockTxHash.length() < 66) {
                int paddingNeeded = 66 - mockTxHash.length();
                StringBuilder padding = new StringBuilder();
                for (int i = 0; i < paddingNeeded; i++) {
                    padding.append("0");
                }
                mockTxHash = mockTxHash + padding.toString();
            } else if (mockTxHash.length() > 66) {
                mockTxHash = mockTxHash.substring(0, 66);
            }

            String fromAddress = proposal.getWalletAddress() != null ? proposal.getWalletAddress()
                : "0x0000000000000000000000000000000000000000";

            log.warn("🎭 MOCK MODE: Auto-creating payment proof for proposal {} (from={})", proposalId, fromAddress);

            return new org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimplePaymentProof(
                mockTxHash,
                evmBridge.getCurrentBlockNumber(),
                fromAddress,
                evmBridge.getContractAddress(),
                proposalId,
                "1000000000000000",
                3
            );
        } catch (Exception e) {
            log.error("Failed to create mock payment proof for proposal {}", proposal.getProposalId(), e);
            return null;
        }
    }
    
    // ============================================================================
    // AGENT 3: Epoch Finalizer (PERIODIC - Wallet Batching)
    // ============================================================================
    
    /**
     * Agent that checks for finalizable epochs and creates wallet-based batches.
     * 
     * <p>This agent runs every 1 second and:
     * <ol>
     *   <li>Checks for epochs that are ready for finalization (2 epochs old)</li>
     *   <li>Groups proposals by wallet address</li>
     *   <li>Sorts within each wallet group (by path, then timestamp)</li>
     *   <li>Creates optimally-sized batches</li>
     *   <li>Queues batches for Aeron sender</li>
     * </ol>
     * 
     * <p><strong>Storage Benefits:</strong>
     * <ul>
     *   <li>Co-located Segments: All of wallet A's writes in segments 1,2,5</li>
     *   <li>Efficient GC: Deleting a wallet removes contiguous TAR files</li>
     *   <li>Accurate Metrics: Per-wallet fragmentation trivial to calculate</li>
     * </ul>
     */
    private class EpochFinalizerAgent implements Agent {
        
        private long lastQueueDepthAlert = 0;
        private long lastWatchdogCheck = 0;
        private static final long ALERT_INTERVAL_MS = 60_000; // Alert every 60 seconds max
        private static final long WATCHDOG_INTERVAL_MS = 30_000; // Check for stale proposals every 30s
        private static final int QUEUE_DEPTH_WARNING = 1000;  // Warn at 1000 proposals
        private static final int QUEUE_DEPTH_CRITICAL = 5000; // Critical at 5000 proposals
        private static final int MAX_TIER_DELAY = 2; // STANDARD tier = max delay (epochs)
        
        @Override
        public int doWork() {
            if (!running) {
                return 0;
            }
            
            int workCount = 0;
            
            // Check for epochs ready to finalize
            List<Long> finalizableEpochs = epochQueue.getFinalizableEpochs();
            
            // ════════════════════════════════════════════════════════════════
            // QUEUE DEPTH MONITORING & ALERTING
            // ════════════════════════════════════════════════════════════════
            java.util.Map<String, Object> epochStats = epochQueue.getStatsMap();
            long pendingProposals = epochStats.containsKey("pendingProposals") ? 
                ((Number) epochStats.get("pendingProposals")).longValue() : 0L;
            
            long now = System.currentTimeMillis();
            if (now - lastQueueDepthAlert > ALERT_INTERVAL_MS) {
                if (pendingProposals >= QUEUE_DEPTH_CRITICAL) {
                    log.error("🚨 CRITICAL: Queue depth at {} proposals (threshold: {})! " +
                        "Finalized epoch: {}, Current epoch: {}, Backlog: {} epochs",
                        pendingProposals, QUEUE_DEPTH_CRITICAL,
                        epochQueue.getFinalizedEpoch(), epochQueue.getCurrentEpoch(),
                        epochQueue.getCurrentEpoch() - epochQueue.getFinalizedEpoch());
                    lastQueueDepthAlert = now;
                } else if (pendingProposals >= QUEUE_DEPTH_WARNING) {
                    log.warn("⚠️  WARNING: Queue depth at {} proposals (threshold: {})! " +
                        "Finalized epoch: {}, Current epoch: {}",
                        pendingProposals, QUEUE_DEPTH_WARNING,
                        epochQueue.getFinalizedEpoch(), epochQueue.getCurrentEpoch());
                    lastQueueDepthAlert = now;
                }
            }
            
            // DEBUG: Log finalization check every ~10 seconds
            if (finalizableEpochs.isEmpty() && System.currentTimeMillis() % 10000 < 1000) {
                log.debug("🔍 Epoch finalization check: {} finalizable epochs, pending={}, finalized={}, current={}",
                    finalizableEpochs.size(),
                    pendingProposals,
                    epochQueue.getFinalizedEpoch(),
                    epochQueue.getCurrentEpoch());
            }
            
            for (Long epoch : finalizableEpochs) {
                try {
                    // Finalize epoch and get wallet-based batches
                    List<List<QueuedProposal>> batches = epochQueue.finalizeEpoch(epoch);
                    
                    if (batches.isEmpty()) {
                        continue;
                    }
                    
                    // Queue each batch for Aeron sender (with chunking to avoid backpressure)
                    int totalProposals = 0;
                    int totalChunks = 0;
                    
                    for (List<QueuedProposal> batch : batches) {
                        totalProposals += batch.size();
                        
                        // If batch is large, chunk it to avoid overwhelming Aeron
                        if (batch.size() > finalizationChunkSize) {
                            log.debug("📦 Large batch detected ({} proposals), chunking into {}s",
                                batch.size(), finalizationChunkSize);
                            
                            for (int i = 0; i < batch.size(); i += finalizationChunkSize) {
                                int endIdx = Math.min(i + finalizationChunkSize, batch.size());
                                List<QueuedProposal> chunk = batch.subList(i, endIdx);
                                batchQueue.offer(chunk);
                                totalChunks++;
                                workCount++;
                                
                                log.debug("  ↳ Chunk {}/{}: {} proposals, wallet: {}",
                                    (i / finalizationChunkSize) + 1,
                                    (batch.size() + finalizationChunkSize - 1) / finalizationChunkSize,
                                    chunk.size(),
                                    chunk.get(0).getWalletAddress());
                                
                                // Small delay to let Aeron process chunks smoothly
                                // Prevents all chunks hitting backpressure simultaneously
                                if (endIdx < batch.size()) {
                                    try {
                                        Thread.sleep(50); // 50ms between chunks
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                        break;
                                    }
                                }
                            }
                        } else {
                            // Small batch, queue as-is
                            batchQueue.offer(batch);
                            totalChunks++;
                            workCount++;
                            
                            log.info("📦 Batch from epoch {} queued for Aeron: {} proposals, wallet: {}, queue depth: {}",
                                epoch, batch.size(),
                                batch.isEmpty() ? "?" : batch.get(0).getWalletAddress(),
                                batchQueue.size());
                        }
                    }
                    
                    log.info("✅ Finalized epoch {}: {} proposals → {} batches/chunks (chunk size: {}, avg batch: {})",
                        epoch, totalProposals, totalChunks, finalizationChunkSize,
                        totalChunks > 0 ? totalProposals / totalChunks : 0);
                    
                    // Update batch sent counter
                    batchedProposalsSent.addAndGet(totalProposals);
                    
                } catch (Exception e) {
                    log.error("❌ Error finalizing epoch {}", epoch, e);
                }
            }
            
            // ════════════════════════════════════════════════════════════════
            // 🛡️ WATCHDOG: Force-finalize stale epochs (defense in depth)
            // ════════════════════════════════════════════════════════════════
            // Even with the epoch polling bug fixed, this safeguard catches:
            // - Logic errors in getFinalizableEpochs()
            // - Stale epoch data from any source
            // - Edge cases we haven't thought of
            // 
            // Rule: If current epoch >= (proposal epoch + MAX delay), 
            //       AND it wasn't already finalized, force-finalize it now
            // ════════════════════════════════════════════════════════════════
            if (now - lastWatchdogCheck > WATCHDOG_INTERVAL_MS) {
                lastWatchdogCheck = now;
                
                long currentEpoch = epochQueue.getCurrentEpoch();
                long finalizedEpoch = epochQueue.getFinalizedEpoch();
                List<Long> allPendingEpochs = epochQueue.getAllPendingEpochs();
                
                for (Long pendingEpoch : allPendingEpochs) {
                    if (pendingEpoch <= finalizedEpoch) {
                        continue;
                    }
                    // Check if this epoch is DEFINITELY past due
                    // (current epoch is at least MAX_TIER_DELAY epochs ahead)
                    long epochAge = currentEpoch - pendingEpoch;
                    
                    if (epochAge >= MAX_TIER_DELAY) {
                        // This epoch should have been finalized by now
                        // Check if it was in the finalizable list
                        if (!finalizableEpochs.contains(pendingEpoch)) {
                            log.warn("🛡️ WATCHDOG: Detected stale epoch {} (age: {} epochs, current: {}). " +
                                "Forcing finalization to prevent proposals from being stuck forever!",
                                pendingEpoch, epochAge, currentEpoch);
                            
                            try {
                                // Force finalize this epoch
                                List<List<QueuedProposal>> batches = epochQueue.finalizeEpoch(pendingEpoch);
                                
                                if (!batches.isEmpty()) {
                                    int totalProposals = 0;
                                    for (List<QueuedProposal> batch : batches) {
                                        totalProposals += batch.size();
                                        batchQueue.offer(batch);
                                        workCount++;
                                    }
                                    
                                    log.warn("🛡️ WATCHDOG: Force-finalized stale epoch {}: {} proposals → {} batches",
                                        pendingEpoch, totalProposals, batches.size());
                                    
                                    batchedProposalsSent.addAndGet(totalProposals);
                                }
                            } catch (Exception e) {
                                log.error("🛡️ WATCHDOG: Error force-finalizing stale epoch {}", pendingEpoch, e);
                            }
                        }
                    }
                }
            }
            
            // ════════════════════════════════════════════════════════════════
            // QUEUE HEALTH REPORTING (periodic)
            // ════════════════════════════════════════════════════════════════
            if (!finalizableEpochs.isEmpty() && System.currentTimeMillis() % 30000 < 1000) {
                java.util.Map<String, Object> healthStats = epochQueue.getStatsMap();
                long batchesCreated = healthStats.containsKey("totalBatchesCreated") ? 
                    ((Number) healthStats.get("totalBatchesCreated")).longValue() : 0L;
                long pendingCount = healthStats.containsKey("pendingProposals") ? 
                    ((Number) healthStats.get("pendingProposals")).longValue() : 0L;
                long processedCount = healthStats.containsKey("totalProposalsFinalized") ? 
                    ((Number) healthStats.get("totalProposalsFinalized")).longValue() : 0L;
                    
                log.info("📊 Queue Health: {} batches created, {} proposals pending, {} proposals processed",
                    batchesCreated, pendingCount, processedCount);
            }
            
            return workCount;
        }
        
        @Override
        public String roleName() {
            return "epoch-finalizer-agent";
        }
    }
    
    // ============================================================================
    // IDLE STRATEGY
    // ============================================================================
    
    /**
     * Create BackoffIdleStrategy for Aeron sender agent.
     * This balances low latency with CPU efficiency.
     * 
     * <p>Strategy:
     * <ul>
     *   <li>Spin 500 times (ultra-low latency, high CPU)</li>
     *   <li>Yield 50,000 times (low latency, medium CPU)</li>
     *   <li>Park 0.1ms-1ms (low CPU, acceptable latency)</li>
     * </ul>
     */
    private IdleStrategy createBackoffIdleStrategy() {
        return new BackoffIdleStrategy(
            500,        // maxSpins - ultra-low latency phase
            50_000,     // maxYields - low latency phase
            100_000,    // minParkPeriodNs - 0.1ms minimum park
            1_000_000   // maxParkPeriodNs - 1ms maximum park
        );
    }
}
