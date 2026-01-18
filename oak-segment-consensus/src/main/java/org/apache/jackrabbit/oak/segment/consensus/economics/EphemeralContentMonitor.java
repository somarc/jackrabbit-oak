/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.economics;

import org.apache.jackrabbit.oak.segment.consensus.contracts.PropagationPaymentClient;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.io.Closeable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors ephemeral content for TTL expiration and executes scheduled deletes.
 * <p>
 * This service implements the "keeper pattern" where any validator can execute
 * scheduled deletes for expired content and receive the prepaid delete fees.
 * <p>
 * <strong>Operation:</strong>
 * <ol>
 *   <li>Periodically polls PropagationPayment contract for expired content</li>
 *   <li>Submits executeScheduledDelete() transactions for expired content</li>
 *   <li>Prepaid delete fees are distributed to all clusters</li>
 *   <li>Gas costs are paid by the executing validator (incentivized by fee share)</li>
 * </ol>
 * <p>
 * <strong>Economics:</strong>
 * <ul>
 *   <li>Prepaid delete fee is distributed to all clusters</li>
 *   <li>Executing validator pays gas (~50k gas per delete)</li>
 *   <li>Net positive if: (fee_share) > (gas_cost)</li>
 *   <li>With 100 clusters and 0.001 ETH base fee: share = 0.0012 ETH / 100 = 0.000012 ETH</li>
 *   <li>Gas cost at 50 gwei: 50k * 50 gwei = 0.0025 ETH</li>
 *   <li>Result: Executing is a public good (costs more than reward)</li>
 *   <li>Solution: Batch multiple deletes per transaction (future optimization)</li>
 * </ul>
 */
public class EphemeralContentMonitor implements Closeable {
    
    private static final Logger LOG = LoggerFactory.getLogger(EphemeralContentMonitor.class);
    
    // Configuration
    private static final long DEFAULT_POLL_INTERVAL_SECONDS = 300; // 5 minutes
    private static final int DEFAULT_MAX_DELETES_PER_CYCLE = 10;
    private static final int DEFAULT_MAX_EXPIRED_TO_FETCH = 100;
    
    private final PropagationPaymentClient paymentClient;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean running = new AtomicBoolean(false);
    
    // Configuration
    private final long pollIntervalSeconds;
    private final int maxDeletesPerCycle;
    private final int maxExpiredToFetch;
    
    // Metrics
    private final AtomicLong totalDeletesExecuted = new AtomicLong(0);
    private final AtomicLong totalDeletesFailed = new AtomicLong(0);
    private final AtomicLong totalGasSpent = new AtomicLong(0);
    private final AtomicLong lastPollTimestamp = new AtomicLong(0);
    
    // Callback for delete events
    private volatile DeleteCallback deleteCallback;
    
    /**
     * Callback interface for delete events.
     */
    public interface DeleteCallback {
        /**
         * Called when content is successfully deleted.
         *
         * @param contentId Content identifier
         * @param txHash Transaction hash
         * @param gasUsed Gas used
         */
        void onContentDeleted(byte[] contentId, String txHash, long gasUsed);
        
        /**
         * Called when delete fails.
         *
         * @param contentId Content identifier
         * @param error Error message
         */
        void onDeleteFailed(byte[] contentId, String error);
    }
    
    /**
     * Create an ephemeral content monitor with default settings.
     *
     * @param paymentClient PropagationPayment contract client (must have credentials)
     */
    public EphemeralContentMonitor(@NotNull PropagationPaymentClient paymentClient) {
        this(paymentClient, DEFAULT_POLL_INTERVAL_SECONDS, DEFAULT_MAX_DELETES_PER_CYCLE, DEFAULT_MAX_EXPIRED_TO_FETCH);
    }
    
    /**
     * Create an ephemeral content monitor with custom settings.
     *
     * @param paymentClient PropagationPayment contract client
     * @param pollIntervalSeconds How often to check for expired content
     * @param maxDeletesPerCycle Maximum deletes to execute per poll cycle
     * @param maxExpiredToFetch Maximum expired items to fetch from contract
     */
    public EphemeralContentMonitor(
            @NotNull PropagationPaymentClient paymentClient,
            long pollIntervalSeconds,
            int maxDeletesPerCycle,
            int maxExpiredToFetch) {
        this.paymentClient = paymentClient;
        this.pollIntervalSeconds = pollIntervalSeconds;
        this.maxDeletesPerCycle = maxDeletesPerCycle;
        this.maxExpiredToFetch = maxExpiredToFetch;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EphemeralContentMonitor");
            t.setDaemon(true);
            return t;
        });
        
        LOG.info("EphemeralContentMonitor created: pollInterval={}s, maxDeletes={}, maxFetch={}",
            pollIntervalSeconds, maxDeletesPerCycle, maxExpiredToFetch);
    }
    
    /**
     * Set callback for delete events.
     *
     * @param callback Callback to invoke on delete events
     */
    public void setDeleteCallback(DeleteCallback callback) {
        this.deleteCallback = callback;
    }
    
    /**
     * Start the monitor.
     */
    public void start() {
        if (running.compareAndSet(false, true)) {
            scheduler.scheduleAtFixedRate(
                this::pollAndDelete,
                pollIntervalSeconds, // Initial delay
                pollIntervalSeconds,
                TimeUnit.SECONDS
            );
            LOG.info("EphemeralContentMonitor started");
        }
    }
    
    /**
     * Stop the monitor.
     */
    public void stop() {
        if (running.compareAndSet(true, false)) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(30, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            LOG.info("EphemeralContentMonitor stopped");
        }
    }
    
    /**
     * Manually trigger a poll and delete cycle.
     *
     * @return Number of deletes executed
     */
    public int pollAndDeleteNow() {
        return pollAndDelete();
    }
    
    /**
     * Poll for expired content and execute deletes.
     *
     * @return Number of deletes executed
     */
    private int pollAndDelete() {
        lastPollTimestamp.set(System.currentTimeMillis());
        
        try {
            // Get expired content from contract
            int pendingCount = paymentClient.getPendingExpirationCount();
            if (pendingCount == 0) {
                LOG.debug("No pending expirations");
                return 0;
            }
            
            LOG.info("Found {} pending expirations, checking for expired content", pendingCount);
            
            // Note: getExpiredContent is a view function that returns content IDs
            // where block.timestamp >= ttlExpiry
            // This requires calling the contract which we'll simulate here
            
            // For now, we'll use a simplified approach:
            // In production, we'd call getExpiredContent(maxExpiredToFetch)
            
            List<byte[]> expiredContentIds = fetchExpiredContent();
            if (expiredContentIds.isEmpty()) {
                LOG.debug("No expired content found");
                return 0;
            }
            
            LOG.info("Found {} expired content items, executing deletes (max {})",
                expiredContentIds.size(), maxDeletesPerCycle);
            
            int deletesExecuted = 0;
            for (byte[] contentId : expiredContentIds) {
                if (deletesExecuted >= maxDeletesPerCycle) {
                    LOG.info("Reached max deletes per cycle ({}), stopping", maxDeletesPerCycle);
                    break;
                }
                
                if (executeDelete(contentId)) {
                    deletesExecuted++;
                }
            }
            
            LOG.info("Executed {} deletes this cycle", deletesExecuted);
            return deletesExecuted;
            
        } catch (Exception e) {
            LOG.error("Error during poll and delete cycle: {}", e.getMessage(), e);
            return 0;
        }
    }
    
    /**
     * Fetch expired content IDs from contract.
     */
    private List<byte[]> fetchExpiredContent() {
        // SEPOLIA_PHASE: Implement actual contract call to getExpiredContent() via Web3j
        // For now, return empty list as placeholder
        // In production:
        // return paymentClient.getExpiredContent(maxExpiredToFetch);
        return new ArrayList<>();
    }
    
    /**
     * Execute a scheduled delete for expired content.
     *
     * @param contentId Content identifier
     * @return true if delete was successful
     */
    private boolean executeDelete(byte[] contentId) {
        String contentIdHex = bytesToHex(contentId);
        
        try {
            LOG.info("Executing scheduled delete for content {}", contentIdHex);
            
            TransactionReceipt receipt = paymentClient.executeScheduledDelete(contentId);
            
            if (receipt.isStatusOK()) {
                long gasUsed = receipt.getGasUsed().longValue();
                totalDeletesExecuted.incrementAndGet();
                totalGasSpent.addAndGet(gasUsed);
                
                LOG.info("Successfully deleted content {}: txHash={}, gasUsed={}",
                    contentIdHex, receipt.getTransactionHash(), gasUsed);
                
                if (deleteCallback != null) {
                    deleteCallback.onContentDeleted(contentId, receipt.getTransactionHash(), gasUsed);
                }
                
                return true;
            } else {
                totalDeletesFailed.incrementAndGet();
                String error = "Transaction failed: " + receipt.getStatus();
                LOG.error("Failed to delete content {}: {}", contentIdHex, error);
                
                if (deleteCallback != null) {
                    deleteCallback.onDeleteFailed(contentId, error);
                }
                
                return false;
            }
            
        } catch (Exception e) {
            totalDeletesFailed.incrementAndGet();
            LOG.error("Exception deleting content {}: {}", contentIdHex, e.getMessage());
            
            if (deleteCallback != null) {
                deleteCallback.onDeleteFailed(contentId, e.getMessage());
            }
            
            return false;
        }
    }
    
    // ═══════════════════════════════════════════════════════════════
    // METRICS
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Get total number of deletes executed.
     */
    public long getTotalDeletesExecuted() {
        return totalDeletesExecuted.get();
    }
    
    /**
     * Get total number of failed deletes.
     */
    public long getTotalDeletesFailed() {
        return totalDeletesFailed.get();
    }
    
    /**
     * Get total gas spent on deletes.
     */
    public long getTotalGasSpent() {
        return totalGasSpent.get();
    }
    
    /**
     * Get timestamp of last poll.
     */
    public long getLastPollTimestamp() {
        return lastPollTimestamp.get();
    }
    
    /**
     * Check if monitor is running.
     */
    public boolean isRunning() {
        return running.get();
    }
    
    /**
     * Get metrics as a formatted string.
     */
    public String getMetricsSummary() {
        return String.format(
            "EphemeralContentMonitor: running=%s, deletes=%d, failed=%d, gasSpent=%d, lastPoll=%d",
            running.get(),
            totalDeletesExecuted.get(),
            totalDeletesFailed.get(),
            totalGasSpent.get(),
            lastPollTimestamp.get()
        );
    }
    
    // ═══════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════
    
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder("0x");
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    @Override
    public void close() {
        stop();
    }
}
