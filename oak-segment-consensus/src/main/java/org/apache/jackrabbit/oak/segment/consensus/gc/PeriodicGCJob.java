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
package org.apache.jackrabbit.oak.segment.consensus.gc;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Periodic Garbage Collection job for GC Account Tax Model.
 * 
 * <p>Runs once per configured epoch duration to:
 * <ul>
 *   <li>Convert pending GC debt to executed debt (simulates actual GC)</li>
 *   <li>Block writes for entities over debt limit</li>
 *   <li>Track GC execution history</li>
 *   <li>Log entities affected</li>
 * </ul>
 * 
 * <p><strong>GC Account Tax Model:</strong>
 * Deletes add "pending debt" immediately. This job converts pending → executed,
 * which triggers write blocking if debt exceeds limit. Payment clears executed debt.
 * 
 * <p><strong>Production Design:</strong>
 * In production, this would trigger actual Oak segment compaction and attribute
 * real reclaimed space to entities. For MVP/POC, we simulate GC execution.
 */
public class PeriodicGCJob {
    
    private static final Logger log = LoggerFactory.getLogger(PeriodicGCJob.class);
    
    private static final long ETHEREUM_EPOCH_SECONDS = 384L; // 32 slots × 12s
    private static final long DEFAULT_MOCK_EPOCH_SECONDS = 300L;
    private static final String ENV_MOCK_EPOCH_DURATION_SECONDS = "OAK_MOCK_EPOCH_DURATION_SECONDS";
    private static final String PROP_MOCK_EPOCH_DURATION_SECONDS = "oak.mock.epoch.duration.seconds";
    
    private final GCAccountManager gcAccountManager;
    private final BlockchainConfig.Mode mode;
    private final long intervalSeconds;
    private final long initialDelaySeconds;
    private ScheduledExecutorService executor;
    private volatile boolean running = false;
    
    /** Total GC executions counter */
    private final AtomicLong totalExecutions = new AtomicLong(0);
    
    /** Total entities converted counter */
    private final AtomicLong totalEntitiesConverted = new AtomicLong(0);
    
    /** Total entities blocked counter */
    private final AtomicLong totalEntitiesBlocked = new AtomicLong(0);
    
    /** Last GC execution timestamp */
    private volatile long lastExecutionTime = 0;
    
    /** Last GC execution duration (ms) */
    private volatile long lastExecutionDuration = 0;
    
    /**
     * Create periodic GC job.
     * 
     * @param gcAccountManager GC account manager to execute GC against
     */
    public PeriodicGCJob(GCAccountManager gcAccountManager) {
        this.gcAccountManager = gcAccountManager;
        this.mode = BlockchainConfig.getInstance().getMode();
        this.intervalSeconds = resolveIntervalSeconds();
        this.initialDelaySeconds = intervalSeconds;
    }
    
    /**
     * Start the periodic GC job.
     */
    public void start() {
        if (running) {
            log.warn("Periodic GC job already running - skipping duplicate start");
            return;
        }
        
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "periodic-gc-job");
            t.setDaemon(true); // Won't prevent JVM shutdown
            return t;
        });
        
        executor.scheduleAtFixedRate(
            this::executeGCCycle,
            initialDelaySeconds,
            intervalSeconds,
            TimeUnit.SECONDS
        );
        
        running = true;
        
        log.info("🔄 Periodic GC job started");
        log.info("   - Mode: {}", mode);
        log.info("   - Interval: {}", formatDuration(intervalSeconds));
        log.info("   - Initial delay: {}", formatDuration(initialDelaySeconds));
        log.info("   - Action: Convert pending debt → executed debt");
    }
    
    /**
     * Stop the periodic GC job.
     */
    public void stop() {
        if (!running) {
            return;
        }
        
        if (executor != null) {
            try {
                executor.shutdown();
                if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
                executor = null;
                log.info("🔄 Periodic GC job stopped");
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                log.warn("Periodic GC job shutdown interrupted", e);
            }
        }
        
        running = false;
    }
    
    /**
     * Execute a single GC cycle.
     * Converts pending debt to executed debt for all entities.
     */
    private void executeGCCycle() {
        long startTime = System.currentTimeMillis();
        long executionNumber = totalExecutions.incrementAndGet();
        
        try {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🧹 GC Cycle #{} - Converting Pending Debt to Executed", executionNumber);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // Get entities with pending debt
            List<EntityGCAccount> entitiesWithPending = gcAccountManager.getAccountsWithPendingDebt();
            
            if (entitiesWithPending.isEmpty()) {
                log.info("✅ GC Cycle #{}: No pending debt to process", executionNumber);
                lastExecutionTime = System.currentTimeMillis();
                lastExecutionDuration = System.currentTimeMillis() - startTime;
                return;
            }
            
            log.info("📊 Found {} entities with pending debt", entitiesWithPending.size());
            
            // Convert pending to executed for all entities
            int converted = 0;
            int blocked = 0;
            BigDecimal totalDebtExecuted = BigDecimal.ZERO;
            
            for (EntityGCAccount account : entitiesWithPending) {
                BigDecimal pendingBefore = account.getPendingDebt();
                boolean wasBlocked = account.writesBlocked;
                
                // Convert pending → executed
                gcAccountManager.convertAllPendingToExecuted();
                
                BigDecimal pendingAfter = account.getPendingDebt();
                BigDecimal convertedAmount = pendingBefore.subtract(pendingAfter);
                
                if (convertedAmount.compareTo(BigDecimal.ZERO) > 0) {
                    converted++;
                    totalDebtExecuted = totalDebtExecuted.add(convertedAmount);
                    
                    // Check if entity became blocked
                    boolean nowBlocked = account.writesBlocked;
                    if (!wasBlocked && nowBlocked) {
                        blocked++;
                        log.warn("🔒 BLOCKED: {} - Debt ${} exceeds limit ${}",
                                account.walletAddress, account.executedDebt, account.debtLimit);
                    } else if (nowBlocked) {
                        log.debug("   Still blocked: {} (debt: ${})", account.walletAddress, account.executedDebt);
                    } else {
                        log.debug("   Converted: {} - ${} pending → executed (under limit)",
                                account.walletAddress, convertedAmount);
                    }
                }
            }
            
            // Update counters
            totalEntitiesConverted.addAndGet(converted);
            totalEntitiesBlocked.addAndGet(blocked);
            
            // Calculate duration
            long duration = System.currentTimeMillis() - startTime;
            lastExecutionTime = System.currentTimeMillis();
            lastExecutionDuration = duration;
            
            // Log summary
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("✅ GC Cycle #{} COMPLETE", executionNumber);
            log.info("   - Entities processed: {}", entitiesWithPending.size());
            log.info("   - Entities converted: {}", converted);
            log.info("   - Entities BLOCKED: {}", blocked);
            log.info("   - Total debt executed: ${}", totalDebtExecuted);
            log.info("   - Duration: {}ms", duration);
            log.info("   - Next run in: {}", formatDuration(intervalSeconds));
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            
            // Log currently blocked entities
            List<EntityGCAccount> blockedAccounts = gcAccountManager.getBlockedAccounts();
            if (!blockedAccounts.isEmpty()) {
                log.warn("⚠️  {} entities currently BLOCKED from writing:", blockedAccounts.size());
                for (EntityGCAccount blockedAccount : blockedAccounts) {
                    log.warn("   - {} (debt: ${}, limit: ${})",
                            blockedAccount.walletAddress,
                            blockedAccount.executedDebt,
                            blockedAccount.debtLimit);
                }
            }
            
        } catch (Exception e) {
            log.error("❌ GC Cycle #{} FAILED: {}", executionNumber, e.getMessage(), e);
            // Don't crash - just log and continue
        }
    }
    
    /**
     * Get GC job statistics.
     * 
     * @return stats as human-readable string
     */
    public String getStats() {
        return String.format(
            "GC Job Stats: executions=%d, entities_converted=%d, entities_blocked=%d, last_run=%d, last_duration=%dms",
            totalExecutions.get(),
            totalEntitiesConverted.get(),
            totalEntitiesBlocked.get(),
            lastExecutionTime,
            lastExecutionDuration
        );
    }
    
    /**
     * Get total executions.
     * 
     * @return total GC cycles executed
     */
    public long getTotalExecutions() {
        return totalExecutions.get();
    }
    
    /**
     * Get last execution time.
     * 
     * @return timestamp of last GC execution
     */
    public long getLastExecutionTime() {
        return lastExecutionTime;
    }
    
    /**
     * Is the job running?
     * 
     * @return true if running
     */
    public boolean isRunning() {
        return running;
    }

    public long getIntervalSeconds() {
        return intervalSeconds;
    }

    public long getInitialDelaySeconds() {
        return initialDelaySeconds;
    }

    private long resolveIntervalSeconds() {
        if (mode != BlockchainConfig.Mode.MOCK) {
            return ETHEREUM_EPOCH_SECONDS;
        }
        String envValue = System.getenv(ENV_MOCK_EPOCH_DURATION_SECONDS);
        String propValue = System.getProperty(PROP_MOCK_EPOCH_DURATION_SECONDS);
        String raw = (envValue != null && !envValue.trim().isEmpty()) ? envValue : propValue;
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_MOCK_EPOCH_SECONDS;
        }
        try {
            long seconds = Long.parseLong(raw.trim());
            if (seconds <= 0) {
                log.warn("Invalid mock epoch duration ({}={}) - using {}s",
                    ENV_MOCK_EPOCH_DURATION_SECONDS, raw, DEFAULT_MOCK_EPOCH_SECONDS);
                return DEFAULT_MOCK_EPOCH_SECONDS;
            }
            return seconds;
        } catch (NumberFormatException e) {
            log.warn("Failed to parse mock epoch duration seconds (raw='{}') - using {}s",
                raw, DEFAULT_MOCK_EPOCH_SECONDS);
            return DEFAULT_MOCK_EPOCH_SECONDS;
        }
    }

    private String formatDuration(long seconds) {
        if (seconds % 60 == 0) {
            return (seconds / 60) + " minutes";
        }
        return seconds + " seconds";
    }
}
