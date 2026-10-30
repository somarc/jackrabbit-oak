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
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.agrona.concurrent.SleepingMillisIdleStrategy;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.eth.BeaconChainClient;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.SimpleEvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumSignatureVerifier;
import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

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
    private static final int CAPABILITY_VALIDATOR_HOSTED_BINARY = 1 << 0;

    
    private static final Logger log = LoggerFactory.getLogger(ProposalQueueManagerOptimized.class);
    private static final long HIGH_FREQ_LOG_INTERVAL_MS = 5000;
    private final RateLimitedInfoLog queueDepthLog = new RateLimitedInfoLog();
    private final RateLimitedInfoLog dequeuedLog = new RateLimitedInfoLog();
    private final RateLimitedInfoLog batchSentLog = new RateLimitedInfoLog();
    private final RateLimitedInfoLog epochQueueLog = new RateLimitedInfoLog();
    // Configuration
    private final long confirmationTimeoutMs;
    private final int requiredConfirmations;
    private final long restoreTimeoutMs;
    private final int maxMessageBatch;
    private final int maxRetryCount;
    // 🌐 PRODUCTION WAN: Aeron default MTU = 1408 bytes (safe for AWS/GCP/Azure)
    // maxPayloadLength = 1408 - 32 (frame header) = 1376 bytes
    // Each proposal ~366 bytes: 3 proposals = 1098 bytes + overhead (~20 bytes) = ~1118 bytes
    // Keeps batches safely under 1376-byte limit for global distributed deployment
    // See: Blockchain-AEM/06-test-results/2025-11-21-BATCH-UDP-MTU-LIMIT.md
    private final int finalizationChunkSize;
    private final long finalizationChunkDelayMs;
    private final AdaptiveReleaseMode releaseMode;
    private final AdaptiveReleaseGovernor adaptiveReleaseGovernor;
    
    // Queues
    private final BeaconChainClient beaconClient;
    private final ConcurrentLinkedQueue<QueuedProposal> unverifiedQueue = new ConcurrentLinkedQueue<>();
    private final AdaptivePackingBuffer adaptivePackingBuffer;
    private final BackpressureOverflowBuffer backpressureOverflowBuffer;
    private final ConcurrentLinkedQueue<List<QueuedProposal>> batchQueue = new ConcurrentLinkedQueue<>(); // Batches ready to send
    private final ConcurrentHashMap<String, QueuedProposal> allProposals = new ConcurrentHashMap<>();
    private final ProposalPersistenceStore persistenceStore;
    private final ProposalPayloadStore payloadStore;
    private final QueueCounterStateStore counterStateStore;
    private final Object persistenceLock = new Object();
    private final long persistenceFlushIntervalMs;
    private final int persistenceFlushBatch;
    private final long payloadInlineMaxBytes;
    private final long payloadSpillSoftPending;
    private final long payloadSpillMaxBytes;
    private final long hardMaxPendingProposals;
    private final AtomicLong persistencePendingChanges =
        new AtomicLong(0);
    private final AtomicBoolean persistenceFlushInProgress =
        new AtomicBoolean(false);
    private volatile boolean persistenceDirty = false;
    private ScheduledExecutorService persistenceScheduler;
    
    // Dependencies
    private final EvmBridge evmBridge;
    private final RaftAppendCallback raftAppendCallback;
    private final BackpressureManager backpressureManager;
    private final ProposalQueueAgentRuntime agentRuntime;
    
    // Agents (3-agent architecture)
    private volatile boolean running = false;

    private final int verifierThreads;
    
    // Metrics: legacy compatibility tier routing
    private final AtomicLong priorityProposalsSent = new AtomicLong(0);
    private final AtomicLong batchedProposalsSent = new AtomicLong(0);
    
    // Metrics: Persistent counters (survive proposal removal from allProposals)
    private final AtomicLong totalRejectedCount = new AtomicLong(0);
    private final AtomicLong totalVerifiedCount = new AtomicLong(0);
    private final AtomicLong totalFinalizedCount = new AtomicLong(0);
    private final AtomicLong lifetimeRejectedBase = new AtomicLong(0);
    private final AtomicLong lifetimeVerifiedBase = new AtomicLong(0);
    private final AtomicLong lifetimeFinalizedBase = new AtomicLong(0);
    private final AtomicLong lifetimePrioritySentBase = new AtomicLong(0);
    private final AtomicLong lifetimeBatchedSentBase = new AtomicLong(0);
    private final AtomicLong counterWindowStartMs =
        new AtomicLong(System.currentTimeMillis());
    private final long counterRotationIntervalMs;
    private final long processedRetentionMs;
    private final long processedPendingRecoveryMs;
    private volatile ReplicatedDurability replicatedDurability = ReplicatedDurability.NONE;
    private volatile long lastProcessedCleanup = 0L;
    private volatile long lastProcessedRecoveryScan = 0L;

    // Metrics: EVM verifier timings and outcomes (for mempool bottleneck analysis)
    private final AtomicLong verifierAttemptCount = new AtomicLong(0);
    private final AtomicLong verifierSuccessCount = new AtomicLong(0);
    private final AtomicLong verifierRequeueNoProofCount = new AtomicLong(0);
    private final AtomicLong verifierRequeueUnconfirmedCount = new AtomicLong(0);
    private final AtomicLong verifierRejectedCount = new AtomicLong(0);
    private final AtomicLong verifierErrorCount = new AtomicLong(0);
    private final AtomicLong verifierTotalNanos = new AtomicLong(0);
    private final AtomicLong verifierProofNanos = new AtomicLong(0);
    private final AtomicLong verifierSignatureNanos = new AtomicLong(0);
    private final AtomicLong verifierAuthNanos = new AtomicLong(0);
    private final AtomicLong verifierPersistNanos = new AtomicLong(0);
    private final AtomicLong verifierQueueWaitMsTotal = new AtomicLong(0);
    private final AtomicLong verifierQueueWaitMsMax = new AtomicLong(0);
    private final AtomicLong verifierLastTotalMs = new AtomicLong(0);
    private final AtomicLong verifierLastProofMs = new AtomicLong(0);
    private final AtomicLong verifierLastSignatureMs = new AtomicLong(0);
    private final AtomicLong verifierLastAuthMs = new AtomicLong(0);
    private final AtomicLong verifierLastPersistMs = new AtomicLong(0);

    // Metrics: enqueue persistence overhead
    private final AtomicLong enqueuePersistNanos = new AtomicLong(0);
    private final AtomicLong enqueuePersistCount = new AtomicLong(0);
    private final AtomicLong enqueuePersistLastMs = new AtomicLong(0);

    // Metrics: persistence flush timing (async mode)
    private final AtomicLong persistenceFlushNanos = new AtomicLong(0);
    private final AtomicLong persistenceFlushCount = new AtomicLong(0);
    private final AtomicLong persistenceFlushLastMs = new AtomicLong(0);
    private final AtomicLong payloadInlineRetainedCount = new AtomicLong(0);
    private final AtomicLong payloadDiskOnlyCount = new AtomicLong(0);
    private final AtomicLong payloadRestoreMissingCount = new AtomicLong(0);
    private final AtomicLong payloadOverloadRejectCount = new AtomicLong(0);
    private final AtomicLong payloadResolveCount = new AtomicLong(0);
    private final AtomicLong payloadResolveNanos = new AtomicLong(0);
    private final AtomicLong payloadResolveLastMs = new AtomicLong(0);
    private volatile String lastAdaptiveDecisionSignature =
        AdaptiveReleaseGovernor.Decision.healthyDirect().signature();
    
    /**
     * Create optimized proposal queue manager with adaptive verified release.
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
            BeaconChainClient beaconClient) {
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
            BeaconChainClient beaconClient,
            String persistenceDir) {
        this(evmBridge, raftAppendCallback, backpressureManager, beaconClient, persistenceDir,
            ProposalQueueTuningRegistry.get());
    }

    ProposalQueueManagerOptimized(
            EvmBridge evmBridge,
            RaftAppendCallback raftAppendCallback,
            BackpressureManager backpressureManager,
            BeaconChainClient beaconClient,
            String persistenceDir,
            ProposalQueueTuning tuning) {
        this.evmBridge = evmBridge;
        this.raftAppendCallback = raftAppendCallback;
        this.backpressureManager = backpressureManager;
        this.beaconClient = beaconClient;
        this.agentRuntime = new ProposalQueueAgentRuntime();
        this.adaptivePackingBuffer = new AdaptivePackingBuffer();
        this.backpressureOverflowBuffer = new BackpressureOverflowBuffer();
        ProposalQueueTuning resolved = tuning != null ? tuning : ProposalQueueTuningRegistry.get();
        this.persistenceStore = createPersistenceStore(persistenceDir, resolved);
        this.payloadStore = createPayloadStore(persistenceDir, resolved);
        this.counterStateStore = createCounterStateStore(persistenceDir);
        this.confirmationTimeoutMs = resolved.confirmationTimeoutMs();
        this.requiredConfirmations = resolved.requiredConfirmations();
        this.restoreTimeoutMs = resolved.restoreTimeoutMs();
        this.maxMessageBatch = resolved.maxMessageBatch();
        this.maxRetryCount = resolved.maxRetryCount();
        this.finalizationChunkSize = resolved.finalizationChunkSize();
        this.finalizationChunkDelayMs = resolved.finalizationChunkDelayMs();
        this.verifierThreads = resolved.verifierThreads();
        this.releaseMode = resolved.releaseMode();
        this.adaptiveReleaseGovernor = AdaptiveReleaseGovernor.fromTuning(resolved);
        this.processedRetentionMs = resolved.processedRetentionMs();
        this.processedPendingRecoveryMs = Math.min(
            this.processedRetentionMs,
            Math.max(5_000L, this.backpressureManager.getBackpressureTimeoutMs())
        );
        this.persistenceFlushIntervalMs = resolved.persistenceFlushIntervalMs();
        this.persistenceFlushBatch = resolved.persistenceFlushBatch();
        this.counterRotationIntervalMs = resolved.counterRotationIntervalMs();
        this.payloadInlineMaxBytes = resolved.payloadInlineMaxBytes();
        this.payloadSpillSoftPending = resolved.payloadSpillSoftPending();
        this.payloadSpillMaxBytes = resolved.payloadSpillMaxBytes();
        this.hardMaxPendingProposals = resolved.hardMaxPendingProposals();
        restoreCounterState();
    }
    
    /**
     * Start tri-agent architecture with adaptive verified release.
     */
    public void start() {
        if (running) {
            log.warn("ProposalQueueManager already started");
            return;
        }
        
        running = true;
        restorePersistedProposals();

        if (persistenceStore != null && isAsyncPersistenceEnabled()) {
            persistenceScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "proposal-persist-flusher");
                t.setDaemon(true);
                return t;
            });
            persistenceScheduler.scheduleAtFixedRate(
                this::flushPersistedProposals,
                persistenceFlushIntervalMs,
                persistenceFlushIntervalMs,
                TimeUnit.MILLISECONDS
            );
        }
        agentRuntime.start(
            this::createBackoffIdleStrategy,
            AeronSenderAgent::new,
            verifierThreads,
            () -> new SleepingMillisIdleStrategy(1),
            ignored -> new EvmVerifierAgent(),
            () -> new SleepingMillisIdleStrategy(25),
            ReleaseFinalizerAgent::new,
            log
        );
        
        log.info("✅ ProposalQueueManager started (tri-agent + adaptive release)");
        log.info("   - Aeron Sender Agent: BackoffIdleStrategy (ultra-low latency)");
        log.info("   - EVM Verifier Agents: {} thread(s), SleepingIdleStrategy (1ms idle, 3-checkpoint security)",
            agentRuntime.getVerifierThreadCount());
        log.info("   - Release Finalizer Agent: SleepingMillisIdleStrategy(25ms)");
        log.info("   - Max batch size: {}", maxMessageBatch);
        log.info("   - Required payment confirmations: {}", requiredConfirmations);
        log.info("   - Release mode: {}", releaseMode.configValue());
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
        log.info("   - Processed-pending recovery window: {}ms", processedPendingRecoveryMs);
    }
    
    /**
     * Get the beacon client backing the epoch compatibility overlay.
     */
    public BeaconChainClient getBeaconClient() {
        return beaconClient;
    }

    public long getCurrentEpoch() {
        return resolveCurrentEpoch();
    }

    public long getFinalizedEpoch() {
        return resolveFinalizedEpoch();
    }
    
    /**
     * Get comprehensive queue statistics for dashboard display.
     */
    public Map<String, Object> getQueueStats() {
        Map<String, Object> stats = new HashMap<>();
        long nowMs = System.currentTimeMillis();
        rotateCountersIfNeeded(nowMs);
        
        // Queue sizes
        stats.put("unverifiedQueueSize", unverifiedQueue.size());
        stats.put("mempoolSize", unverifiedQueue.size());
        stats.put("batchQueueSize", batchQueue.size());
        stats.put("totalProposals", allProposals.size());
        
        long currentEpoch = resolveCurrentEpoch();
        long finalizedEpoch = resolveFinalizedEpoch();
        Map<String, Object> adaptiveStatsMap = adaptivePackingBuffer.getStatsMap();
        Map<String, Object> overflowStatsMap = backpressureOverflowBuffer.getStatsMap();
        long adaptiveVerifiedPackingBufferCount = getVerifiedPackingBufferCount();
        long verifiedPackingBufferCount = adaptiveVerifiedPackingBufferCount;
        long adaptiveWalletCount = getLongStat(adaptiveStatsMap, "walletCount");
        long adaptiveQueuedProposalTotal = getLongStat(adaptiveStatsMap, "totalProposalsQueued");
        long adaptiveDrainedProposalTotal = getLongStat(adaptiveStatsMap, "totalProposalsDrained");
        long adaptiveCreatedBatchTotal = getLongStat(adaptiveStatsMap, "totalBatchesCreated");
        long overflowProposalCount = getLongStat(overflowStatsMap, "pendingProposals");
        long overflowBatchCount = getLongStat(overflowStatsMap, "pendingBatches");
        long overflowBufferedBatchTotal = getLongStat(overflowStatsMap, "totalBatchesBuffered");
        long overflowBufferedProposalTotal = getLongStat(overflowStatsMap, "totalProposalsBuffered");
        long overflowPromotedBatchTotal = getLongStat(overflowStatsMap, "totalBatchesPromoted");
        long overflowPromotedProposalTotal = getLongStat(overflowStatsMap, "totalProposalsPromoted");
        stats.put("currentEpoch", currentEpoch);
        stats.put("finalizedEpoch", finalizedEpoch);
        stats.put("epochsUntilFinality", currentEpoch >= 0 && finalizedEpoch >= 0 ? currentEpoch - finalizedEpoch : -1L);
        stats.put("adaptivePackingBufferStats", adaptivePackingBuffer.getStats());
        stats.put("backpressureOverflowStats", backpressureOverflowBuffer.getStats());
        stats.put("adaptivePackingWalletCount", adaptiveWalletCount);
        stats.put("adaptiveVerifiedPackingBufferCount", adaptiveVerifiedPackingBufferCount);
        stats.put("verifiedPackingBufferCount", verifiedPackingBufferCount);
        stats.put("adaptivePackingQueuedProposalCountTotal", adaptiveQueuedProposalTotal);
        stats.put("adaptivePackingDrainedProposalCountTotal", adaptiveDrainedProposalTotal);
        stats.put("adaptivePackingCreatedBatchCountTotal", adaptiveCreatedBatchTotal);
        stats.put("backpressureOverflowProposalCount", overflowProposalCount);
        stats.put("backpressureOverflowBatchCount", overflowBatchCount);
        stats.put("backpressureOverflowBufferedBatchCountTotal", overflowBufferedBatchTotal);
        stats.put("backpressureOverflowBufferedProposalCountTotal", overflowBufferedProposalTotal);
        stats.put("backpressureOverflowPromotedBatchCountTotal", overflowPromotedBatchTotal);
        stats.put("backpressureOverflowPromotedProposalCountTotal", overflowPromotedProposalTotal);
        
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
        long releaseReadyProposalCount = countQueuedProposals(batchQueue);
        long releasePressureProposalCount = releaseReadyProposalCount + overflowProposalCount;
        long releasePressureBatchCount = batchQueue.size() + overflowBatchCount;
        long verifiedResidentProposalCount = verifiedPackingBufferCount + releasePressureProposalCount;
        stats.put("releaseReadyProposalCount", releaseReadyProposalCount);
        stats.put("releaseReadyBatchCount", batchQueue.size());
        stats.put("releasePressureProposalCount", releasePressureProposalCount);
        stats.put("releasePressureBatchCount", releasePressureBatchCount);
        stats.put("verifiedResidentProposalCount", verifiedResidentProposalCount);
        
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
        Map<Long, Long> proposalsByEpoch = new HashMap<>();
        for (QueuedProposal proposal : allProposals.values()) {
            if (proposal.getState() == ProposalState.VERIFIED || proposal.getState() == ProposalState.PENDING) {
                long epoch = proposal.getEpoch();
                proposalsByEpoch.merge(epoch, 1L, Long::sum);
            }
        }
        stats.put("proposalsByEpoch", proposalsByEpoch);
        
        // Backpressure stats
        long backpressurePendingRaw = backpressureManager.getPendingCount();
        long backpressureMax = backpressureManager.getMaxPendingMessages();
        boolean backpressureActive = backpressureManager.isBackpressureActive();
        boolean queueIdle = batchQueue.isEmpty()
            && backpressureOverflowBuffer.isEmpty()
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
        stats.put("backpressureTimeoutCount", backpressureManager.getBackpressureTimeoutCount());
        stats.put("backpressureReconciliationCount", backpressureManager.getStalePendingReconciliationCount());
        long backpressurePendingOldestMs = backpressureManager.getPendingOldestMs(nowMs);
        long backpressurePendingStalledMs = backpressureManager.getPendingStalledMs(nowMs);
        stats.put("backpressurePendingOldestMs", backpressurePendingOldestMs);
        stats.put("backpressurePendingStalledMs", backpressurePendingStalledMs);
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

        AdaptiveReleaseGovernor.SignalSnapshot adaptiveSignals = new AdaptiveReleaseGovernor.SignalSnapshot(
            Math.max(0L, verifiedCurrent - finalizedCurrent),
            backpressurePending,
            backpressureMax,
            backpressureActive,
            backpressurePendingOldestMs,
            backpressurePendingStalledMs,
            verifiedPackingBufferCount,
            releasePressureBatchCount,
            releasePressureProposalCount
        );
        AdaptiveReleaseGovernor.Decision adaptiveDecision = adaptiveReleaseGovernor.evaluate(adaptiveSignals);

        Map<String, Object> runtimeStages = new LinkedHashMap<>();
        runtimeStages.put("unverifiedMempoolCount", pending);
        runtimeStages.put("verifiedPackingBufferCount", verifiedPackingBufferCount);
        runtimeStages.put("adaptiveVerifiedPackingBufferCount", adaptiveVerifiedPackingBufferCount);
        runtimeStages.put("releaseReadyProposalCount", releaseReadyProposalCount);
        runtimeStages.put("releaseReadyBatchCount", batchQueue.size());
        runtimeStages.put("backpressureOverflowProposalCount", overflowProposalCount);
        runtimeStages.put("backpressureOverflowBatchCount", overflowBatchCount);
        runtimeStages.put("verifiedResidentProposalCount", verifiedResidentProposalCount);
        runtimeStages.put("backpressureOverflowSeparateBufferEnabled", true);
        stats.put("runtimeStageCounts", runtimeStages);
        stats.put("releaseMode", releaseMode.configValue());
        stats.put("requiredConfirmations", requiredConfirmations);
        stats.put("adaptiveReleaseGovernorState", adaptiveDecision.state().name());
        stats.put("adaptiveReleaseAction", adaptiveDecision.action().name());
        stats.put("adaptiveReleaseReasonCodes", adaptiveDecision.reasonCodes());
        Map<String, Object> releasePolicy = new LinkedHashMap<>();
        releasePolicy.put("scheduler", "adaptive");
        releasePolicy.put("releaseMode", releaseMode.configValue());
        releasePolicy.put("requiredConfirmations", requiredConfirmations);
        releasePolicy.put("note", "Verified proposals drain through the adaptive governor. Tier-specific queue behavior is retired.");
        stats.put("releasePolicy", releasePolicy);
        Map<String, Object> releaseFlow = new LinkedHashMap<>();
        releaseFlow.put("scheduler", "adaptive");
        releaseFlow.put("stages", runtimeStages);
        Map<String, Object> governor = new LinkedHashMap<>();
        governor.put("state", adaptiveDecision.state().name());
        governor.put("action", adaptiveDecision.action().name());
        governor.put("reasonCodes", adaptiveDecision.reasonCodes());
        releaseFlow.put("governor", governor);
        Map<String, Object> backpressure = new LinkedHashMap<>();
        backpressure.put("active", backpressureActive);
        backpressure.put("pendingCount", backpressurePending);
        backpressure.put("pendingRawCount", backpressurePendingRaw);
        backpressure.put("maxPending", backpressureMax);
        backpressure.put("pendingOldestMs", backpressurePendingOldestMs);
        backpressure.put("pendingStalledMs", backpressurePendingStalledMs);
        releaseFlow.put("backpressure", backpressure);
        releaseFlow.put("adaptivePacking", adaptivePackingBuffer.getStats());
        releaseFlow.put("overflow", backpressureOverflowBuffer.getStats());
        stats.put("releaseFlow", releaseFlow);
        
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
        stats.put("payloadInlineMaxBytes", payloadInlineMaxBytes);
        stats.put("payloadSpillSoftPending", payloadSpillSoftPending);
        stats.put("payloadSpillMaxBytes", payloadSpillMaxBytes);
        stats.put("hardMaxPendingProposals", hardMaxPendingProposals);
        stats.put("payloadSpoolBytes", payloadStore != null ? payloadStore.getTotalBytes() : 0L);
        stats.put("payloadInlineRetainedCount", payloadInlineRetainedCount.get());
        stats.put("payloadDiskOnlyCount", payloadDiskOnlyCount.get());
        stats.put("payloadRestoreMissingCount", payloadRestoreMissingCount.get());
        stats.put("payloadOverloadRejectCount", payloadOverloadRejectCount.get());
        stats.put("payloadResolveCount", payloadResolveCount.get());
        stats.put("payloadResolveAvgMs", payloadResolveCount.get() == 0 ? 0 :
            (payloadResolveNanos.get() / 1_000_000.0) / payloadResolveCount.get());
        stats.put("payloadResolveLastMs", payloadResolveLastMs.get());
        
        return stats;
    }

    private long countQueuedProposals(ConcurrentLinkedQueue<List<QueuedProposal>> queue) {
        long total = 0L;
        for (List<QueuedProposal> batch : queue) {
            if (batch != null) {
                total += batch.size();
            }
        }
        return total;
    }

    private long getLongStat(Map<String, Object> stats, String key) {
        Object value = stats.get(key);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0L;
    }

    private long resolveCurrentEpoch() {
        long currentEpoch = beaconClient != null ? beaconClient.getCachedCurrentEpoch() : -1L;
        if (currentEpoch >= 0L) {
            return currentEpoch;
        }
        long finalizedEpoch = beaconClient != null ? beaconClient.getCachedFinalizedEpoch() : -1L;
        return finalizedEpoch >= 0L ? finalizedEpoch + 2L : -1L;
    }

    private long resolveFinalizedEpoch() {
        long finalizedEpoch = beaconClient != null ? beaconClient.getCachedFinalizedEpoch() : -1L;
        if (finalizedEpoch >= 0L) {
            return finalizedEpoch;
        }
        long currentEpoch = beaconClient != null ? beaconClient.getCachedCurrentEpoch() : -1L;
        return currentEpoch >= 0L ? Math.max(0L, currentEpoch - 2L) : -1L;
    }

    private AdaptiveReleaseGovernor.Decision evaluateAdaptiveReleaseDecision(long nowMs) {
        AdaptiveReleaseGovernor.SignalSnapshot signals = new AdaptiveReleaseGovernor.SignalSnapshot(
            Math.max(0L, totalVerifiedCount.get() - totalFinalizedCount.get()),
            backpressureManager.getPendingCount(),
            backpressureManager.getMaxPendingMessages(),
            backpressureManager.isBackpressureActive(),
            backpressureManager.getPendingOldestMs(nowMs),
            backpressureManager.getPendingStalledMs(nowMs),
            getVerifiedPackingBufferCount(),
            getReleasePressureBatchCount(),
            getReleasePressureProposalCount()
        );
        return adaptiveReleaseGovernor.evaluate(signals);
    }

    private void captureAdaptiveReleaseDecision(long nowMs) {
        AdaptiveReleaseGovernor.Decision decision = evaluateAdaptiveReleaseDecision(nowMs);
        String signature = decision.signature();
        if (releaseMode == AdaptiveReleaseMode.ADAPTIVE_SHADOW && !signature.equals(lastAdaptiveDecisionSignature)) {
            lastAdaptiveDecisionSignature = signature;
            log.info("ADAPTIVE_RELEASE_SHADOW state={} action={} reasons={} gap={} packing={} releaseReady={} pending={} pendingOldestMs={} pendingStalledMs={}",
                decision.state(),
                decision.action(),
                decision.reasonCodes(),
                Math.max(0L, totalVerifiedCount.get() - totalFinalizedCount.get()),
                getVerifiedPackingBufferCount(),
                getReleasePressureProposalCount(),
                backpressureManager.getPendingCount(),
                backpressureManager.getPendingOldestMs(nowMs),
                backpressureManager.getPendingStalledMs(nowMs));
        } else if (!signature.equals(lastAdaptiveDecisionSignature)) {
            lastAdaptiveDecisionSignature = signature;
        }
    }

    private long getVerifiedPackingBufferCount() {
        return getLongStat(adaptivePackingBuffer.getStatsMap(), "pendingProposals");
    }

    private long getReleasePressureProposalCount() {
        return countQueuedProposals(batchQueue) + backpressureOverflowBuffer.getPendingProposalCount();
    }

    private long getReleasePressureBatchCount() {
        return batchQueue.size() + backpressureOverflowBuffer.getPendingBatchCount();
    }

    private void routeVerifiedProposal(QueuedProposal proposal,
                                       String txHashSummary,
                                       long confirmedBlockNumber) {
        adaptivePackingBuffer.addProposal(proposal, proposal.getVerifiedTimestampMs());
        epochQueueLog.info(
            "📥 Proposal added to adaptive packing buffer: {} | wallet: {} | epoch: {}",
            proposal.getProposalId().substring(0, 8),
            proposal.getWalletAddress().substring(0, 10),
            proposal.getEpoch());
        log.debug("🔒 Proposal {} VERIFIED (tx: {}, block: {}, epoch: {}, wallet: {}) → queued for adaptive release",
            proposal.getProposalId(),
            txHashSummary,
            confirmedBlockNumber,
            proposal.getEpoch(),
            proposal.getWalletAddress());
    }

    private void enqueueRestoredVerifiedProposal(QueuedProposal proposal, long nowMs) {
        long verifiedTimestampMs = proposal.getVerifiedTimestampMs();
        if (verifiedTimestampMs <= 0L) {
            proposal.setVerifiedTimestampMs(Math.max(proposal.getTimestamp(), nowMs));
        }

        Long confirmedBlock = proposal.getConfirmedBlock();
        long confirmedBlockNumber = confirmedBlock != null ? confirmedBlock.longValue() : -1L;
        routeVerifiedProposal(
            proposal,
            summarizeTxHash(proposal.getEthereumTxHash()),
            confirmedBlockNumber
        );
    }

    private String summarizeTxHash(String txHash) {
        if (txHash == null || txHash.isEmpty()) {
            return "n/a";
        }
        return txHash.substring(0, Math.min(10, txHash.length())) + "...";
    }

    private void registerProposalWalletMapping(QueuedProposal proposal) {
        if (proposal == null) {
            return;
        }
        if (evmBridge instanceof SimpleEvmBridge) {
            ((SimpleEvmBridge) evmBridge)
                .registerProposalWallet(proposal.getProposalId(), proposal.getWalletAddress());
        }
    }

    private int queueReleaseBatches(List<List<QueuedProposal>> batches, String sourceLabel) {
        int workCount = 0;
        for (List<QueuedProposal> batch : batches) {
            workCount += queueReleaseBatch(batch, sourceLabel);
        }
        return workCount;
    }

    private int bufferOverflowBatches(List<List<QueuedProposal>> batches, String sourceLabel) {
        int buffered = 0;
        for (List<QueuedProposal> batch : batches) {
            buffered += bufferOverflowBatch(batch, sourceLabel);
        }
        return buffered;
    }

    private int queueReleaseBatch(List<QueuedProposal> batch, String sourceLabel) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        if (batch.size() > finalizationChunkSize) {
            return offerChunked(batch, sourceLabel, batchQueue::offer, finalizationChunkDelayMs,
                "📦 Large {} batch detected ({} proposals), chunking into {}s", "chunk");
        }
        batchQueue.offer(batch);
        log.info("📦 {} batch queued for Aeron: {} proposals, wallet: {}, queue depth: {}",
            sourceLabel,
            batch.size(),
            batch.get(0).getWalletAddress(),
            batchQueue.size());
        return 1;
    }

    private int bufferOverflowBatch(List<QueuedProposal> batch, String sourceLabel) {
        if (batch == null || batch.isEmpty()) {
            return 0;
        }
        if (batch.size() > finalizationChunkSize) {
            return offerChunked(batch, sourceLabel, backpressureOverflowBuffer::bufferBatch, 0L,
                "📦 Large {} batch detected ({} proposals), buffering overflow in {} chunks", "overflow chunk");
        }
        backpressureOverflowBuffer.bufferBatch(batch);
        log.info("📦 {} batch buffered in overflow: {} proposals, wallet: {}, overflow depth: {}",
            sourceLabel,
            batch.size(),
            batch.get(0).getWalletAddress(),
            backpressureOverflowBuffer.getPendingBatchCount());
        return 1;
    }

    private int offerChunked(List<QueuedProposal> batch, String sourceLabel, Consumer<List<QueuedProposal>> sink,
                             long chunkDelayMs, String header, String chunkTag) {
        log.debug(header, sourceLabel, batch.size(), finalizationChunkSize);
        int chunks = 0;
        for (int i = 0; i < batch.size(); i += finalizationChunkSize) {
            int endIdx = Math.min(i + finalizationChunkSize, batch.size());
            List<QueuedProposal> chunk = new ArrayList<>(batch.subList(i, endIdx));
            sink.accept(chunk);
            chunks++;

            log.debug("  ↳ {} {} {}/{}: {} proposals, wallet: {}",
                sourceLabel,
                chunkTag,
                (i / finalizationChunkSize) + 1,
                (batch.size() + finalizationChunkSize - 1) / finalizationChunkSize,
                chunk.size(),
                chunk.get(0).getWalletAddress());

            if (chunkDelayMs > 0 && endIdx < batch.size()) {
                try {
                    Thread.sleep(chunkDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return chunks;
    }

    private boolean shouldRouteToOverflow(AdaptiveReleaseGovernor.Decision decision) {
        if (decision == null) {
            return false;
        }
        if (decision.action() == AdaptiveReleaseGovernor.ReleaseAction.THROTTLED) {
            return true;
        }
        return backpressureManager.isBackpressureActive()
            || backpressureManager.getPendingCount() >= backpressureManager.getMaxPendingMessages();
    }

    private int promoteOverflowBatches(AdaptiveReleaseGovernor.Decision decision) {
        if (backpressureOverflowBuffer.isEmpty()) {
            return 0;
        }
        if (decision == null || decision.action() == AdaptiveReleaseGovernor.ReleaseAction.THROTTLED) {
            return 0;
        }

        int maxReleaseReadyBatches = Math.max(maxMessageBatch, maxMessageBatch * 2);
        if (batchQueue.size() >= maxReleaseReadyBatches) {
            return 0;
        }

        int maxPromotions = decision.action() == AdaptiveReleaseGovernor.ReleaseAction.DIRECT
            ? maxMessageBatch
            : Math.max(1, maxMessageBatch / 2);
        int promoted = backpressureOverflowBuffer.promoteTo(batchQueue, maxPromotions, maxReleaseReadyBatches);
        if (promoted > 0) {
            log.info("♻️ Promoted {} overflow batches into release-ready queue (remaining overflow batches: {}, release-ready batches: {})",
                promoted,
                backpressureOverflowBuffer.getPendingBatchCount(),
                batchQueue.size());
        }
        return promoted;
    }

    /**
     * Build the canonical adaptive verified-release flow snapshot for operators.
     * This is the upstream source of truth for /v1/proposals/release-flow.
     */
    public Map<String, Object> getProposalReleaseFlowStats() {
        Map<String, Object> queueStats = getQueueStats();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contractVersion", "proposal.release-flow.v1");
        payload.put("generatedAtMs", System.currentTimeMillis());
        payload.put("source", "adaptive-release");
        payload.put("schedulerModel", "adaptive-capacity");
        payload.put("releaseMode", queueStats.get("releaseMode"));
        payload.put("requiredConfirmations", queueStats.get("requiredConfirmations"));
        payload.put("currentEpoch", queueStats.get("currentEpoch"));
        payload.put("finalizedEpoch", queueStats.get("finalizedEpoch"));
        payload.put("epochsUntilFinality", queueStats.get("epochsUntilFinality"));
        payload.put("note",
            "Verified proposals move through adaptive packing, release-ready, and overflow stages. "
                + "Beacon epoch data is informational telemetry only and does not control release scheduling.");

        Map<String, Object> releaseStages = new LinkedHashMap<>();
        releaseStages.put("unverifiedMempoolCount", queueStats.get("pendingCount"));
        releaseStages.put("verifiedPackingBufferCount", queueStats.get("verifiedPackingBufferCount"));
        releaseStages.put("releaseReadyProposalCount", queueStats.get("releaseReadyProposalCount"));
        releaseStages.put("releaseReadyBatchCount", queueStats.get("releaseReadyBatchCount"));
        releaseStages.put("backpressureOverflowProposalCount", queueStats.get("backpressureOverflowProposalCount"));
        releaseStages.put("backpressureOverflowBatchCount", queueStats.get("backpressureOverflowBatchCount"));
        releaseStages.put("verifiedResidentProposalCount", queueStats.get("verifiedResidentProposalCount"));
        payload.put("releaseStages", releaseStages);

        Map<String, Object> governor = new LinkedHashMap<>();
        governor.put("state", queueStats.get("adaptiveReleaseGovernorState"));
        governor.put("action", queueStats.get("adaptiveReleaseAction"));
        governor.put("reasonCodes", queueStats.get("adaptiveReleaseReasonCodes"));
        governor.put("backpressureActive", queueStats.get("backpressureActive"));
        governor.put("backpressurePendingCount", queueStats.get("backpressurePendingCount"));
        governor.put("backpressureMaxPending", queueStats.get("backpressureMaxPending"));
        governor.put("pendingOldestMs", queueStats.get("backpressurePendingOldestMs"));
        governor.put("pendingStalledMs", queueStats.get("backpressurePendingStalledMs"));
        payload.put("governor", governor);

        Map<String, Object> packing = new LinkedHashMap<>();
        packing.put("walletCount", queueStats.get("adaptivePackingWalletCount"));
        packing.put("queuedProposalCountTotal", queueStats.get("adaptivePackingQueuedProposalCountTotal"));
        packing.put("drainedProposalCountTotal", queueStats.get("adaptivePackingDrainedProposalCountTotal"));
        packing.put("createdBatchCountTotal", queueStats.get("adaptivePackingCreatedBatchCountTotal"));
        payload.put("packing", packing);

        Map<String, Object> overflow = new LinkedHashMap<>();
        overflow.put("separateBufferEnabled", true);
        overflow.put("bufferedBatchCountTotal", queueStats.get("backpressureOverflowBufferedBatchCountTotal"));
        overflow.put("bufferedProposalCountTotal", queueStats.get("backpressureOverflowBufferedProposalCountTotal"));
        overflow.put("promotedBatchCountTotal", queueStats.get("backpressureOverflowPromotedBatchCountTotal"));
        overflow.put("promotedProposalCountTotal", queueStats.get("backpressureOverflowPromotedProposalCountTotal"));
        payload.put("overflow", overflow);

        Map<String, Object> throughput = new LinkedHashMap<>();
        throughput.put("priorityProposalsSent", queueStats.get("priorityProposalsSent"));
        throughput.put("batchedProposalsSent", queueStats.get("batchedProposalsSent"));
        throughput.put("totalProposalsSent", queueStats.get("totalProposalsSent"));
        throughput.put("totalFinalizedCount", queueStats.get("totalFinalizedCount"));
        throughput.put("totalRejectedCount", queueStats.get("totalRejectedCount"));
        payload.put("throughput", throughput);

        return payload;
    }

    private ProposalPersistenceStore createPersistenceStore(String persistenceDir, ProposalQueueTuning tuning) {
        if (tuning != null && !tuning.persistenceEnabled()) {
            log.info("Proposal queue persistence disabled via tuning (persistence_enabled=false)");
            return null;
        }
        String resolved = resolvePersistenceDirectory(persistenceDir);
        if (resolved == null || resolved.isEmpty()) {
            return null;
        }
        return new ProposalPersistenceStore(java.nio.file.Path.of(resolved));
    }

    private ProposalPayloadStore createPayloadStore(String persistenceDir, ProposalQueueTuning tuning) {
        java.nio.file.Path payloadDir = resolvePayloadSpillDirectory(persistenceDir, tuning);
        boolean ephemeral = payloadDir == null;
        if (payloadDir == null) {
            try {
                payloadDir = java.nio.file.Files.createTempDirectory("oak-proposal-payloads");
            } catch (java.io.IOException e) {
                throw new IllegalStateException("Failed to create temporary payload spill directory", e);
            }
        }
        return new ProposalPayloadStore(payloadDir, ephemeral);
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

    private java.nio.file.Path resolvePayloadSpillDirectory(String persistenceDir, ProposalQueueTuning tuning) {
        if (tuning != null && tuning.payloadSpillDir() != null && !tuning.payloadSpillDir().trim().isEmpty()) {
            return java.nio.file.Path.of(tuning.payloadSpillDir().trim());
        }
        String configured = System.getProperty("oak.proposal.payload.spill.dir");
        if (configured == null || configured.trim().isEmpty()) {
            configured = System.getenv("OAK_PROPOSAL_PAYLOAD_SPILL_DIR");
        }
        if (configured != null && !configured.trim().isEmpty()) {
            return java.nio.file.Path.of(configured.trim());
        }
        String resolvedPersistenceDir = resolvePersistenceDirectory(persistenceDir);
        if (resolvedPersistenceDir != null && !resolvedPersistenceDir.isEmpty()) {
            return java.nio.file.Path.of(resolvedPersistenceDir).resolve("payloads");
        }
        return null;
    }

    private void restoreCounterState() {
        if (counterStateStore == null) {
            return;
        }
        Map<String, Long> state = counterStateStore.load();
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
        Map<String, Long> state = new HashMap<>();
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
        long nowMs = System.currentTimeMillis();
        int restoredPending = 0;
        int restoredVerified = 0;
        int restoredProcessedPending = 0;
        int restoredDecided = 0;
        int skippedTerminal = 0;
        int skippedMissingPayload = 0;
        for (QueuedProposal proposal : proposals) {
            if (proposal == null) {
                continue;
            }
            if (proposal.getState() == ProposalState.REJECTED) {
                cleanupPayload(proposal);
                skippedTerminal++;
                continue;
            }
            if (proposal.getState() == ProposalState.PROCESSED && isTerminalDurability(proposal.getDurabilityState())) {
                cleanupPayload(proposal);
                skippedTerminal++;
                continue;
            }
            if (!hasRestorablePayload(proposal)) {
                skippedMissingPayload++;
                continue;
            }
            allProposals.put(proposal.getProposalId(), proposal);
            registerProposalWalletMapping(proposal);

            if (proposal.getState() == ProposalState.PROCESSED) {
                if (takeReplicatedDecision(proposal)) {
                    restoredDecided++;
                } else if (recoverProcessedProposalForRetry(proposal, nowMs, "restart-restore")) {
                    restoredProcessedPending++;
                }
                continue;
            }

            if (proposal.getState() == ProposalState.VERIFIED) {
                enqueueRestoredVerifiedProposal(proposal, nowMs);
                restoredVerified++;
                continue;
            }

            proposal.setState(ProposalState.PENDING);
            proposal.setConfirmedBlock(null);
            proposal.setRejectionReason(null);
            proposal.overrideTimeoutTimestamp(nowMs + restoreTimeoutMs);
            unverifiedQueue.offer(proposal);
            restoredPending++;
        }
        if (restoredProcessedPending > 0 || restoredDecided > 0 || skippedTerminal > 0 || skippedMissingPayload > 0) {
            persistProposalsNow();
        }
        if (restoredPending > 0 || restoredVerified > 0 || restoredProcessedPending > 0 || restoredDecided > 0
            || skippedTerminal > 0 || skippedMissingPayload > 0) {
            log.info("🔁 Restored persisted proposals: pending={} verified={} processedPending={} decidedInLog={} releaseMode={} skippedTerminal={} skippedMissingPayload={}",
                restoredPending,
                restoredVerified,
                restoredProcessedPending,
                restoredDecided,
                releaseMode.configValue(),
                skippedTerminal,
                skippedMissingPayload);
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

    /**
     * Stop agents and cleanup.
     */
    public void stop() {
        running = false;
        agentRuntime.stop(log);

        if (persistenceScheduler != null) {
            persistenceScheduler.shutdown();
            try {
                persistenceScheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            flushPersistedProposals();
            persistenceScheduler = null;
        }
        persistCounterState();
        if (payloadStore != null) {
            payloadStore.close();
        }
        
        log.info("✅ ProposalQueueManager stopped");
    }
    
    /**
     * Queue a write proposal for verification and adaptive release (standard tier).
     *
     * @param intentToken intent token for lazy binary upload (optional, ADR 020)
     */
    public QueuedProposal queueProposal(
            String proposalId,
            String ethereumTxHash,
            String walletAddress,
            String path,
            String contentType,
            String message,
            String signature,
            String intentToken,
            String blobId,
            String mimeType,
            String ipfsCid) {
        return queueProposal(proposalId, ethereumTxHash, walletAddress, path, contentType, message, signature,
            ValidatorEarningsTracker.PaymentTier.STANDARD, intentToken, blobId, mimeType, ipfsCid);
    }

    public QueuedProposal queueProposal(
            String proposalId,
            String ethereumTxHash,
            String walletAddress,
            String path,
            String contentType,
            String message,
            String signature,
            ValidatorEarningsTracker.PaymentTier tier,
            String intentToken) {
        return queueProposal(proposalId, ethereumTxHash, walletAddress, path, contentType, message, signature,
            tier, intentToken, null, null, null);
    }

    /**
     * Queue a write proposal for verification and adaptive release; the submission epoch is captured for
     * compatibility overlays.
     */
    public QueuedProposal queueProposal(
            String proposalId,
            String ethereumTxHash,
            String walletAddress,
            String path,
            String contentType,
            String message,
            String signature,
            ValidatorEarningsTracker.PaymentTier tier,
            String intentToken,
            String blobId,
            String mimeType,
            String ipfsCid) {
        long epoch = resolveCurrentEpoch();
        CanonicalGenesisContent.requireMutable(walletAddress, path);
        enforceAdmissionCapacity();
        long pendingCount = getPendingCount();
        QueuedProposal proposal = newPendingProposal(proposalId, ethereumTxHash, walletAddress, path, signature, epoch, tier);
        proposal.setContentType(contentType);
        proposal.setIntentToken(intentToken);
        proposal.setBlobId(blobId);
        proposal.setMimeType(mimeType);
        proposal.setIpfsCid(ipfsCid);
        attachPayloadState(proposal, message, pendingCount);
        admit(proposal);
        log.debug("📥 Queued proposal {} for EVM verification in epoch {} (queue size: {})",
            proposalId, epoch, unverifiedQueue.size());
        persistOnEnqueue();
        return proposal;
    }

    /**
     * Queue a DELETE proposal; deletes flow through the same pipeline as writes.
     */
    public QueuedProposal queueDeleteProposal(
            String proposalId,
            String ethereumTxHash,
            String walletAddress,
            String path,
            String signature) {
        return queueDeleteProposal(proposalId, ethereumTxHash, walletAddress, path, signature, ValidatorEarningsTracker.PaymentTier.STANDARD);
    }

    public QueuedProposal queueDeleteProposal(
            String proposalId,
            String ethereumTxHash,
            String walletAddress,
            String path,
            String signature,
            ValidatorEarningsTracker.PaymentTier tier) {
        CanonicalGenesisContent.requireMutable(walletAddress, path);
        enforceAdmissionCapacity();
        long currentEpoch = resolveCurrentEpoch();
        QueuedProposal proposal =
            newPendingProposal(proposalId, ethereumTxHash, walletAddress, path, signature, currentEpoch, tier);
        proposal.setType(QueuedProposal.ProposalType.DELETE);
        proposal.setContentType("delete");
        proposal.setMessage("");
        admit(proposal);
        log.info("🗑️  Queued DELETE proposal {} for EVM verification in epoch {} (path: {}, queue size: {})",
            proposalId, currentEpoch, path, unverifiedQueue.size());
        persistOnEnqueue();
        return proposal;
    }

    private QueuedProposal newPendingProposal(String proposalId, String ethereumTxHash, String walletAddress,
                                              String path, String signature, long epoch,
                                              ValidatorEarningsTracker.PaymentTier tier) {
        long now = System.currentTimeMillis();
        QueuedProposal proposal = new QueuedProposal(
            proposalId, ethereumTxHash, now, now + confirmationTimeoutMs, ProposalState.PENDING);
        proposal.setWalletAddress(walletAddress);
        proposal.setPath(path);
        proposal.setSignature(signature);
        proposal.setEpoch(epoch);
        proposal.setTier(tier);
        proposal.setDurabilityState(DurabilityState.PENDING, null, null);
        return proposal;
    }

    /** Track the proposal, register its wallet before enqueue (mock-mode verifier race), then enqueue. */
    private void admit(QueuedProposal proposal) {
        allProposals.put(proposal.getProposalId(), proposal);
        registerProposalWalletMapping(proposal);
        unverifiedQueue.offer(proposal);
    }

    private void persistOnEnqueue() {
        long persistStart = System.nanoTime();
        persistProposals();
        long persistNanos = System.nanoTime() - persistStart;
        enqueuePersistNanos.addAndGet(persistNanos);
        enqueuePersistCount.incrementAndGet();
        enqueuePersistLastMs.set(persistNanos / 1_000_000L);
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
    public void setReplicatedDurability(ReplicatedDurability replicatedDurability) {
        this.replicatedDurability = replicatedDurability != null ? replicatedDurability : ReplicatedDurability.NONE;
    }

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
        long verifiedBufferCount = getVerifiedPackingBufferCount();
        long totalPending = unverifiedQueue.size()
            + verifiedBufferCount
            + countQueuedProposals(batchQueue)
            + backpressureOverflowBuffer.getPendingProposalCount();
        return totalPending >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) totalPending;
    }
    
    /**
     * Get queue statistics.
     */
    public String getStats() {
        return String.format("Unverified: %d, Verified Buffer: %d, Batches Ready: %d, Overflow: %d, Total: %d | Adaptive: %s | Overflow Buffer: %s",
            unverifiedQueue.size(),
            getVerifiedPackingBufferCount(),
            batchQueue.size(),
            backpressureOverflowBuffer.getPendingBatchCount(),
            allProposals.size(),
            adaptivePackingBuffer.getStats(),
            backpressureOverflowBuffer.getStats());
    }

    private void enforceAdmissionCapacity() {
        if (getPendingCount() >= hardMaxPendingProposals) {
            payloadOverloadRejectCount.incrementAndGet();
            throw new RejectedExecutionException("queue_overloaded");
        }
    }

    private void attachPayloadState(QueuedProposal proposal, String message, long pendingCount) {
        String normalizedMessage = message != null ? message : "";
        long messageSizeBytes = normalizedMessage.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        boolean keepInline = messageSizeBytes <= payloadInlineMaxBytes
            && pendingCount < payloadSpillSoftPending;
        boolean mustPersistForRecovery = persistenceStore != null;

        if (!mustPersistForRecovery && keepInline) {
            proposal.setMessage(normalizedMessage);
            proposal.setPayloadRef(null);
            proposal.setPayloadSizeBytes(0L);
            proposal.setPayloadSha256(null);
            if (messageSizeBytes > 0L) {
                payloadInlineRetainedCount.incrementAndGet();
            }
            return;
        }

        ProposalPayloadStore.StoredPayload storedPayload;
        try {
            storedPayload = payloadStore.storePayload(
                proposal.getProposalId(),
                normalizedMessage,
                payloadSpillMaxBytes
            );
        } catch (RejectedExecutionException e) {
            payloadOverloadRejectCount.incrementAndGet();
            throw new RejectedExecutionException("queue_overloaded", e);
        }

        proposal.setPayloadRef(storedPayload.getPayloadRef());
        proposal.setPayloadSizeBytes(storedPayload.getSizeBytes());
        proposal.setPayloadSha256(storedPayload.getSha256());

        if (keepInline || storedPayload.getPayloadRef() == null) {
            proposal.setMessage(normalizedMessage);
            if (storedPayload.getSizeBytes() > 0L) {
                payloadInlineRetainedCount.incrementAndGet();
            }
        } else {
            proposal.clearMessage();
            payloadDiskOnlyCount.incrementAndGet();
        }
    }

    /**
     * The exact text the client personal_signs: DELETE binds the content path, WRITE binds the message.
     */
    private String resolveSignedMessage(QueuedProposal proposal) {
        if (proposal.getType() == QueuedProposal.ProposalType.DELETE) {
            return proposal.getPath();
        }
        return resolveProposalMessage(proposal);
    }

    private String resolveProposalMessage(QueuedProposal proposal) {
        if (proposal == null) {
            return "";
        }
        String message = proposal.getMessage();
        if (message != null) {
            return message;
        }
        String payloadRef = proposal.getPayloadRef();
        if (payloadRef == null || payloadRef.isEmpty()) {
            return "";
        }

        long start = System.nanoTime();
        try {
            String resolved = payloadStore.loadPayload(payloadRef, proposal.getPayloadSha256());
            long nanos = System.nanoTime() - start;
            payloadResolveCount.incrementAndGet();
            payloadResolveNanos.addAndGet(nanos);
            payloadResolveLastMs.set(nanos / 1_000_000L);
            return resolved;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(
                "Failed to resolve payload for proposal " + proposal.getProposalId(),
                e
            );
        }
    }

    private List<QueuedProposal> hydrateBatchMessages(List<QueuedProposal> batch) {
        List<QueuedProposal> hydrated = new ArrayList<>();
        if (batch == null || batch.isEmpty()) {
            return hydrated;
        }
        for (QueuedProposal proposal : batch) {
            if (proposal == null
                || proposal.getType() == QueuedProposal.ProposalType.DELETE
                || proposal.getMessage() != null) {
                continue;
            }
            proposal.setMessage(resolveProposalMessage(proposal));
            hydrated.add(proposal);
        }
        return hydrated;
    }

    private void clearHydratedMessages(List<QueuedProposal> hydrated) {
        if (hydrated == null || hydrated.isEmpty()) {
            return;
        }
        for (QueuedProposal proposal : hydrated) {
            if (proposal != null) {
                proposal.clearMessage();
            }
        }
    }

    private void clearPayloadState(QueuedProposal proposal) {
        if (proposal == null) {
            return;
        }
        proposal.clearMessage();
        proposal.setPayloadRef(null);
        proposal.setPayloadSizeBytes(0L);
        proposal.setPayloadSha256(null);
    }

    private void cleanupPayload(QueuedProposal proposal) {
        if (proposal == null) {
            return;
        }
        String payloadRef = proposal.getPayloadRef();
        long payloadSizeBytes = proposal.getPayloadSizeBytes();
        if (payloadRef != null && !payloadRef.isEmpty()) {
            payloadStore.deletePayload(payloadRef, payloadSizeBytes);
        }
        clearPayloadState(proposal);
    }

    private boolean hasRestorablePayload(QueuedProposal proposal) {
        if (proposal == null) {
            return false;
        }
        String payloadRef = proposal.getPayloadRef();
        if (payloadRef == null || payloadRef.isEmpty()) {
            return true;
        }
        if (payloadStore.hasPayload(payloadRef)) {
            return true;
        }
        payloadRestoreMissingCount.incrementAndGet();
        log.warn("Skipping restored proposal {} because payload sidecar {} is missing",
            proposal.getProposalId(), payloadRef);
        return false;
    }

    private void transitionProposalToProcessed(QueuedProposal proposal) {
        if (proposal == null) {
            return;
        }
        proposal.setState(ProposalState.PROCESSED);
        totalFinalizedCount.incrementAndGet();
    }

    private void transitionProposalToRejected(QueuedProposal proposal, String reason) {
        if (proposal == null) {
            return;
        }
        proposal.setState(ProposalState.REJECTED);
        proposal.setRejectionReason(reason);
        cleanupPayload(proposal);
        totalRejectedCount.incrementAndGet();
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
            recoverStaleProcessedProposals(System.currentTimeMillis());
            
            // 🚀 RELEASE BATCHING: Process verified batches prepared by the adaptive finalizer
            // Batches are organized by wallet address for optimal segment packing
            
            // Process up to maxMessageBatch batches per cycle
            int batchesProcessed = 0;
            int queueDepth = batchQueue.size();
            
            // Log queue activity periodically  
            if (queueDepth > 0) {
                queueDepthLog.info(
                    "🔄 AeronSenderAgent: {} batches waiting in queue (overflow batches: {})",
                    queueDepth,
                    backpressureOverflowBuffer.getPendingBatchCount());
            }
            
            while (batchesProcessed < maxMessageBatch) {
                List<QueuedProposal> batch = batchQueue.poll();
                if (batch == null || batch.isEmpty()) {
                    break;
                }
                
                batchesProcessed++;
                QueuedProposal firstProposal = batch.get(0);
                dequeuedLog.info(
                    "📤 AeronSenderAgent: DEQUEUED batch {} of {} | {} proposals | wallet: {} | epoch: {} | remaining ready: {}, overflow: {}",
                    batchesProcessed, queueDepth, batch.size(),
                    firstProposal.getWalletAddress().substring(0, 10),
                    firstProposal.getEpoch(),
                    batchQueue.size(),
                    backpressureOverflowBuffer.getPendingBatchCount());
                
                // Apply backpressure ONCE per batch (not per proposal)
                try {
                    backpressureManager.applyBackpressureIfNeeded();
                } catch (BackpressureTimeoutException e) {
                    boolean reconciled = backpressureManager.reconcileIfStalled(
                        backpressureManager.getBackpressureTimeoutMs(),
                        "aeron-sender-timeout"
                    );
                    bufferOverflowBatch(batch, "aeron-backpressure");
                    if (reconciled) {
                        log.warn("⚠️  Backpressure timeout reconciled - buffering batch in overflow ({} proposals)", batch.size());
                    } else {
                        log.warn("⚠️  Backpressure timeout - buffering batch in overflow ({} proposals)", batch.size());
                    }
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
                        MutationAuditMetadata auditMetadata = proposal.toAuditMetadata();
                        
                        // Check proposal type: WRITE or DELETE
                        if (proposal.getType() == QueuedProposal.ProposalType.DELETE) {
                            log.debug("🗑️  Sending DELETE proposal (templateId 101)");
                            if (raftAppendCallback.tryAppendDeleteProposalWithId(
                                proposal.getProposalId(),
                                proposal.getWalletAddress(),
                                proposal.getPath(),
                                proposal.getSignature(),
                                auditMetadata
                            )) {
                                sent = 1;
                            }
                        } else {
                            String resolvedMessage = resolveProposalMessage(proposal);
                            log.debug("📝 Sending WRITE proposal (templateId 100) blobId={}, ipfsCid={}", 
                                proposal.getBlobId(), proposal.getIpfsCid());
                            if (raftAppendCallback.tryAppendProposalWithId(
                                proposal.getProposalId(),
                                proposal.getWalletAddress(),
                                proposal.getPath(),
                                proposal.getContentType(),
                                resolvedMessage,
                                proposal.getSignature(),
                                proposal.getBlobId(),
                                proposal.getMimeType(),
                                proposal.getIpfsCid(),
                                auditMetadata
                            )) {
                                sent = 1;
                            }
                        }
                    } else {
                        // Multi-proposal batch: use templateId 106
                        List<QueuedProposal> hydrated = hydrateBatchMessages(batch);
                        try {
                            log.debug("🔥 CALLING appendProposalBatch on instance of: {}", 
                                raftAppendCallback.getClass().getName());
                            sent = raftAppendCallback.appendProposalBatch(batch);
                            log.debug("🔥 appendProposalBatch RETURNED: {}", sent);
                        } finally {
                            clearHydratedMessages(hydrated);
                        }
                    }
                    
                    if (sent > 0) {
                        // Mark all proposals in batch as processed
                        for (QueuedProposal queued : batch) {
                            transitionProposalToProcessed(queued);
                            batchedProposalsSent.incrementAndGet();
                            workCount++;
                        }
                        persistProposals();
                        
                        batchSentLog.info(
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
                    bufferOverflowBatch(batch, "aeron-backpressure");
                    log.warn("⚠️  Backpressure timeout - buffering batch in overflow ({} proposals)", batch.size());
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
                            String reason = "Exceeded max retry count (" + maxRetryCount + ") after Aeron send failures: "
                                + e.getMessage();
                            if (proposal.isAppendedToLog()) {
                                transitionProposalToProcessed(proposal);
                                failProcessedDurability(proposal, reason);
                            } else {
                                transitionProposalToRejected(proposal, reason);
                            }
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
            
            cleanupTerminalProposals();
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
    private void cleanupTerminalProposals() {
        long now = System.currentTimeMillis();
        if (now - lastProcessedCleanup < 60_000L) {
            return;
        }
        lastProcessedCleanup = now;
        final int[] removed = {0};
        allProposals.entrySet().removeIf(entry -> {
            QueuedProposal proposal = entry.getValue();
            ProposalState state = proposal.getState();
            if (state != ProposalState.PROCESSED && state != ProposalState.REJECTED) {
                return false;
            }
            long age = now - proposal.getTimestamp();
            if (age < processedRetentionMs) {
                return false;
            }
            if (state == ProposalState.REJECTED) {
                cleanupPayload(proposal);
                removed[0]++;
                return true;
            }
            DurabilityState durability = proposal.getDurabilityState();
            boolean shouldRemove = durability == DurabilityState.ACKED || durability == DurabilityState.FAILED;
            if (shouldRemove) {
                cleanupPayload(proposal);
                removed[0]++;
            }
            return shouldRemove;
        });
        if (removed[0] > 0) {
            persistProposals();
        }
    }

    private void recoverStaleProcessedProposals(long nowMs) {
        if ((nowMs - lastProcessedRecoveryScan) < 1_000L) {
            return;
        }
        lastProcessedRecoveryScan = nowMs;

        int recovered = 0;
        int failed = 0;
        int decided = 0;
        for (QueuedProposal proposal : allProposals.values()) {
            if (proposal == null
                || proposal.getState() != ProposalState.PROCESSED
                || proposal.getDurabilityState() != DurabilityState.PENDING) {
                continue;
            }

            long staleMs = nowMs - getProcessedPendingReferenceTimestamp(proposal, nowMs);
            if (staleMs < processedPendingRecoveryMs) {
                continue;
            }
            if (takeReplicatedDecision(proposal)) {
                decided++;
                continue;
            }

            if (recoverProcessedProposalForRetry(proposal, nowMs, "stale-durability-pending")) {
                recovered++;
            } else {
                failed++;
            }
        }

        if (recovered > 0 || failed > 0 || decided > 0) {
            persistProposals();
            log.warn("♻️ Processed proposal recovery sweep completed: recovered={} durabilityFailed={} decidedInLog={} windowMs={}",
                recovered, failed, decided, processedPendingRecoveryMs);
        }
    }

    private boolean recoverProcessedProposalForRetry(QueuedProposal proposal, long nowMs, String reason) {
        if (proposal == null
            || proposal.getState() != ProposalState.PROCESSED
            || proposal.getDurabilityState() != DurabilityState.PENDING) {
            return false;
        }
        if (!hasRestorablePayload(proposal)) {
            failProcessedDurability(proposal,
                "Cannot recover processed proposal awaiting durability; payload sidecar missing");
            return false;
        }

        int nextRetry = proposal.incrementRetryCount();
        if (nextRetry > maxRetryCount) {
            failProcessedDurability(proposal,
                "Exceeded max retry count (" + maxRetryCount + ") while recovering processed proposal awaiting durability");
            return false;
        }

        totalFinalizedCount.updateAndGet(current -> current > 0L ? current - 1L : 0L);
        proposal.markAppendedToLog();
        proposal.setState(ProposalState.VERIFIED);
        proposal.setRejectionReason(null);
        proposal.overrideTimeoutTimestamp(nowMs + restoreTimeoutMs);
        proposal.setDurabilityState(DurabilityState.PENDING, null, null);
        backpressureManager.incrementAcknowledged();
        enqueueRestoredVerifiedProposal(proposal, nowMs);
        log.warn("♻️ Re-queued processed proposal for replay: proposalId={} reason={} retry={}/{}",
            proposal.getProposalId(), reason, nextRetry, maxRetryCount);
        return true;
    }

    /**
     * Takes the decision the replicated log already made for a processed proposal. The proposer misses it when it
     * is applied before this queue is restored; re-sending would only append a duplicate of a decided proposal.
     */
    private boolean takeReplicatedDecision(QueuedProposal proposal) {
        ReplicatedDurability.Decision decision = replicatedDurability.find(proposal.getProposalId());
        if (decision == null) {
            return false;
        }
        proposal.setDurabilityState(decision.durable ? DurabilityState.ACKED : DurabilityState.FAILED,
            decision.durableHead, decision.error);
        log.info("Durability of {} taken from the replicated log: {}", proposal.getProposalId(),
            proposal.getDurabilityState());
        return true;
    }

    /**
     * A processed proposal was already sent to the replicated log, so it must not be reported REJECTED;
     * only its durability confirmation failed.
     */
    private void failProcessedDurability(QueuedProposal proposal, String reason) {
        proposal.setDurabilityState(DurabilityState.FAILED, null, reason);
        log.warn("Durability FAILED for processed proposal {}: {}", proposal.getProposalId(), reason);
    }

    private boolean isTerminalDurability(DurabilityState durabilityState) {
        return durabilityState == DurabilityState.ACKED || durabilityState == DurabilityState.FAILED;
    }

    private long getProcessedPendingReferenceTimestamp(QueuedProposal proposal, long nowMs) {
        if (proposal == null) {
            return nowMs;
        }
        long since = proposal.getLastRetryTimestamp();
        if (since <= 0L) {
            since = proposal.getDurabilityTimestamp();
        }
        if (since <= 0L) {
            since = proposal.getVerifiedTimestampMs();
        }
        if (since <= 0L) {
            since = proposal.getTimestamp();
        }
        return since > 0L ? since : nowMs;
    }

    /** INFO logging at most once per {@link #HIGH_FREQ_LOG_INTERVAL_MS}, reporting how many lines were dropped. */
    private static final class RateLimitedInfoLog {
        private final AtomicLong lastMs = new AtomicLong(0);
        private final AtomicInteger suppressed = new AtomicInteger(0);

        void info(String format, Object... args) {
            long now = System.currentTimeMillis();
            long last = lastMs.get();
            if ((now - last) >= HIGH_FREQ_LOG_INTERVAL_MS && lastMs.compareAndSet(last, now)) {
                int dropped = suppressed.getAndSet(0);
                if (dropped > 0) {
                    Object[] withMeta = Arrays.copyOf(args, args.length + 2);
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
            while (running && (proposal = unverifiedQueue.poll()) != null) {
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
                    boolean isMockMode = BlockchainConfig.getInstance().isMockMode();
                    if (!isMockMode && !proposal.getProposalId().matches("^0x[0-9a-fA-F]{64}$")) {
                        verifierRejectedCount.incrementAndGet();
                        rejectProposal(proposal,
                            "Chain-backed modes require proposalId to match the on-chain bytes32 identifier (0x-prefixed 32-byte hex)");
                        continue;
                    }

                    long proofStartNs = System.nanoTime();
                    PaymentProof proof = evmBridge.verifyPayment(proposal.getProposalId());
                    long proofNanos = System.nanoTime() - proofStartNs;
                    verifierProofNanos.addAndGet(proofNanos);
                    verifierLastProofMs.set(proofNanos / 1_000_000L);

                    if (proof == null) {
                        // No payment found yet - re-queue (will retry)
                        // In mock mode, this immediately returns a valid proof
                        // In real mode, this polls the blockchain for the transaction
                        verifierRequeueNoProofCount.incrementAndGet();
                        if (running) {
                            unverifiedQueue.offer(proposal);
                        }
                        continue;
                    }
                    
                    if (!proof.isConfirmed(requiredConfirmations)) {
                        // Payment exists but not confirmed yet - re-queue
                        verifierRequeueUnconfirmedCount.incrementAndGet();
                        if (running) {
                            unverifiedQueue.offer(proposal);
                        }
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

                    if (!isMockMode) {
                        String declaredTxHash = proposal.getEthereumTxHash();
                        String confirmedTxHash = proof.getTransactionHash();
                        if (declaredTxHash == null || declaredTxHash.trim().isEmpty()
                                || confirmedTxHash == null || !confirmedTxHash.equalsIgnoreCase(declaredTxHash)) {
                            verifierRejectedCount.incrementAndGet();
                            rejectProposal(proposal, "Confirmed transaction hash does not match declared ethereumTxHash");
                            continue;
                        }

                        PaymentProof.ProposalKind expectedKind =
                            proposal.getType() == QueuedProposal.ProposalType.DELETE
                                ? PaymentProof.ProposalKind.DELETE
                                : PaymentProof.ProposalKind.WRITE;
                        if (proof.getProposalKind() != expectedKind) {
                            verifierRejectedCount.incrementAndGet();
                            rejectProposal(
                                proposal,
                                "Confirmed proposal kind does not match queued proposal type (expected "
                                    + expectedKind + ", got " + proof.getProposalKind() + ")"
                            );
                            continue;
                        }
                    }
                    
                    if (!isMockMode
                        && requiresValidatorHostedBinaryCapability(proposal)
                        && !proof.hasCapabilityFlag(CAPABILITY_VALIDATOR_HOSTED_BINARY)) {
                        verifierRejectedCount.incrementAndGet();
                        rejectProposal(
                            proposal,
                            "Validator-hosted binary proposal requires CAPABILITY_VALIDATOR_HOSTED_BINARY in the settlement proof"
                        );
                        continue;
                    }

                    // Verify payment amount is present and positive.
                    try {
                        java.math.BigInteger amountWei = new java.math.BigInteger(proof.getAmountWei());
                        if (amountWei.signum() <= 0) {
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
                    if (!isMockMode) {
                        String signedMessage = resolveSignedMessage(proposal);
                        String proposalSignature = proposal.getSignature();

                        if (proposalSignature == null || proposalSignature.isEmpty()) {
                            verifierRejectedCount.incrementAndGet();
                            rejectProposal(proposal, "Missing cryptographic signature for wallet "
                                + proposal.getWalletAddress());
                            continue;
                        }

                        if (!EthereumSignatureVerifier
                            .isFullVerificationAvailable()) {
                            verifierRejectedCount.incrementAndGet();
                            rejectProposal(proposal, "Full Ethereum signature verification unavailable: "
                                + EthereumSignatureVerifier
                                    .getAvailabilityReason());
                            continue;
                        }

                        long signatureStartNs = System.nanoTime();
                        boolean signatureValid = EthereumSignatureVerifier
                            .verifySignature(signedMessage, proposalSignature, proposal.getWalletAddress());
                        long signatureNanos = System.nanoTime() - signatureStartNs;
                        verifierSignatureNanos.addAndGet(signatureNanos);
                        verifierLastSignatureMs.set(signatureNanos / 1_000_000L);

                        if (!signatureValid) {
                            verifierRejectedCount.incrementAndGet();
                            rejectProposal(proposal, "Cryptographic signature verification failed for wallet "
                                + proposal.getWalletAddress());
                            continue;
                        }
                        log.debug("✅ CHECKPOINT 2 PASSED: Signature cryptographically verified for wallet {}",
                            proposal.getWalletAddress());
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
                    String expectedShardRoot = WalletPathUtil.getShardRoot(proposal.getWalletAddress());
                    boolean ownsPath = proposal.getPath().equals(expectedShardRoot)
                        || proposal.getPath().startsWith(expectedShardRoot + "/");
                    if (!ownsPath) {
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
                    
                    long verifiedAtMs = System.currentTimeMillis();
                    proposal.setState(ProposalState.VERIFIED);
                    proposal.setConfirmedBlock(proof.getBlockNumber());
                    proposal.setVerifiedTimestampMs(verifiedAtMs);
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
                    verifierQueueWaitMsMax.accumulateAndGet(queueWaitMs, Math::max);
                    String txHashSummary = summarizeTxHash(proof.getTransactionHash());
                    
                    routeVerifiedProposal(proposal, txHashSummary, proof.getBlockNumber());
                    workCount++;
                    
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
            transitionProposalToRejected(proposal, reason);
            persistProposals();
            
            log.warn("❌ REJECTED proposal {}: {} (total rejected: {})", 
                proposal.getProposalId(), reason, totalRejectedCount.get());
        }
        
        @Override
        public String roleName() {
            return "evm-verifier-agent";
        }
    }

    private static boolean requiresValidatorHostedBinaryCapability(QueuedProposal proposal) {
        if (proposal == null || proposal.getType() != QueuedProposal.ProposalType.WRITE) {
            return false;
        }
        String intentToken = proposal.getIntentToken();
        if (intentToken != null && !intentToken.isEmpty()) {
            return true;
        }
        String blobId = proposal.getBlobId();
        return blobId != null && !blobId.isEmpty();
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
    private class ReleaseFinalizerAgent implements Agent {
        
        private long lastQueueDepthAlert = 0;
        private static final long ALERT_INTERVAL_MS = 60_000; // Alert every 60 seconds max
        private static final int QUEUE_DEPTH_WARNING = 1000;  // Warn at 1000 proposals
        private static final int QUEUE_DEPTH_CRITICAL = 5000; // Critical at 5000 proposals
        
        @Override
        public int doWork() {
            if (!running) {
                return 0;
            }
            long now = System.currentTimeMillis();
            captureAdaptiveReleaseDecision(now);
            int workCount = 0;
            Map<String, Object> adaptiveStats = adaptivePackingBuffer.getStatsMap();
            long pendingProposals = getLongStat(adaptiveStats, "pendingProposals");
            AdaptiveReleaseGovernor.Decision decision = evaluateAdaptiveReleaseDecision(now);
            workCount += promoteOverflowBatches(decision);

            maybeLogQueueDepthAlert(pendingProposals, now);

            List<List<QueuedProposal>> batches = adaptivePackingBuffer.drainReadyBatches(now, decision);
            if (!batches.isEmpty()) {
                int totalProposals = batches.stream().mapToInt(List::size).sum();
                boolean overflowed = shouldRouteToOverflow(decision);
                int totalChunks = overflowed
                    ? bufferOverflowBatches(batches, "adaptive-overflow")
                    : queueReleaseBatches(batches, "adaptive");
                workCount += totalChunks;

                log.info("✅ Adaptive release drained {} proposals → {} {} (state={}, action={}, reasons={})",
                    totalProposals,
                    totalChunks,
                    overflowed ? "overflow batches/chunks" : "release batches/chunks",
                    decision.state(),
                    decision.action(),
                    decision.reasonCodes());

                batchedProposalsSent.addAndGet(totalProposals);
            }

            maybeLogAdaptiveHealth(pendingProposals, adaptiveStats, now);
            return workCount;
        }

        private void maybeLogQueueDepthAlert(long pendingProposals, long now) {
            long releaseReadyProposals = countQueuedProposals(batchQueue);
            long overflowProposals = backpressureOverflowBuffer.getPendingProposalCount();
            long alertDepth = pendingProposals + releaseReadyProposals + overflowProposals;
            if (now - lastQueueDepthAlert <= ALERT_INTERVAL_MS) {
                return;
            }
            if (alertDepth >= QUEUE_DEPTH_CRITICAL) {
                log.error("🚨 CRITICAL: Adaptive resident depth at {} proposals (threshold: {})! Packing={}, release-ready={}, overflow={}, backpressurePending={}",
                    alertDepth,
                    QUEUE_DEPTH_CRITICAL,
                    pendingProposals,
                    releaseReadyProposals,
                    overflowProposals,
                    backpressureManager.getPendingCount());
                lastQueueDepthAlert = now;
            } else if (alertDepth >= QUEUE_DEPTH_WARNING) {
                log.warn("⚠️  WARNING: Adaptive resident depth at {} proposals (threshold: {})! Packing={}, release-ready={}, overflow={}, backpressurePending={}",
                    alertDepth,
                    QUEUE_DEPTH_WARNING,
                    pendingProposals,
                    releaseReadyProposals,
                    overflowProposals,
                    backpressureManager.getPendingCount());
                lastQueueDepthAlert = now;
            }
        }

        private void maybeLogAdaptiveHealth(long pendingCount, Map<String, Object> adaptiveStats, long now) {
            if ((pendingCount <= 0 && backpressureOverflowBuffer.isEmpty()) || now % 30000 >= 1000) {
                return;
            }
            log.info("📊 Adaptive Queue Health: {} wallets, packing={}, release-ready={}, overflow={}, proposals drained={}, batches created={}",
                getLongStat(adaptiveStats, "walletCount"),
                pendingCount,
                countQueuedProposals(batchQueue),
                backpressureOverflowBuffer.getPendingProposalCount(),
                getLongStat(adaptiveStats, "totalProposalsDrained"),
                getLongStat(adaptiveStats, "totalBatchesCreated"));
        }
        
        @Override
        public String roleName() {
            return "release-finalizer-agent";
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
