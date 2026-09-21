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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import io.aeron.Aeron;
import io.aeron.driver.status.SystemCounterDescriptor;
import org.agrona.concurrent.status.CountersReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Monitors MediaDriver health and detects potential issues early.
 * 
 * <p>✈️ AERON RESILIENCE: Proactive monitoring to keep MediaDriver alive.
 * 
 * <p>Based on Aeron best practices:
 * <ul>
 *   <li>Monitors system counters for errors, backpressure, timeouts</li>
 *   <li>Detects MediaDriver health issues before they become fatal</li>
 *   <li>Provides health status for external monitoring (health checks, metrics)</li>
 *   <li>Tracks error rates and backpressure events</li>
 * </ul>
 * 
 * <p>Key metrics monitored:
 * <ul>
 *   <li>Error count - detects MediaDriver errors</li>
 *   <li>Backpressure events - indicates resource exhaustion</li>
 *   <li>Client timeouts - indicates MediaDriver unresponsiveness</li>
 *   <li>Free space - detects disk space issues</li>
 * </ul>
 */
public class MediaDriverHealthMonitor implements AutoCloseable {
    
    private static final Logger log = LoggerFactory.getLogger(MediaDriverHealthMonitor.class);
    
    private final CountersReader countersReader;
    private final ScheduledExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    
    // Health state tracking
    private final AtomicLong lastErrorCount = new AtomicLong(0);
    private final AtomicLong lastBackpressureCount = new AtomicLong(0);
    private final AtomicLong lastTimeoutCount = new AtomicLong(0);
    private volatile long currentErrorCount = 0;
    private volatile long currentBackpressureCount = 0;
    private volatile long currentTimeoutCount = 0;
    private volatile long freeSpace = Long.MAX_VALUE;
    private volatile boolean healthy = true;
    private volatile String healthStatus = "OK";
    
    // Thresholds for health warnings
    private static final long ERROR_RATE_THRESHOLD = 10;  // Errors per minute
    private static final long BACKPRESSURE_THRESHOLD = 5;  // Backpressure events per minute
    private static final long TIMEOUT_THRESHOLD = 3;  // Timeouts per minute
    private static final long FREE_SPACE_THRESHOLD_MB = 100;  // 100MB minimum free space
    
    public MediaDriverHealthMonitor(Aeron aeron) {
        if (aeron == null) {
            throw new IllegalArgumentException("Aeron instance cannot be null");
        }
        this.countersReader = aeron.countersReader();
        
        // Start background monitoring thread
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "media-driver-health-monitor");
            t.setDaemon(true);
            return t;
        });
        
        // Monitor every 10 seconds
        executor.scheduleWithFixedDelay(this::checkHealth, 0, 10, TimeUnit.SECONDS);
        
        log.info("✅ MediaDriver health monitor started");
    }
    
    /**
     * Check MediaDriver health by reading system counters.
     */
    private void checkHealth() {
        if (closed.get()) {
            return;
        }
        
        try {
            // Read system counters by iterating through SystemCounterDescriptor values
            long errorCount = 0;
            long backpressureCount = 0;
            long timeoutCount = 0;
            long freeSpaceBytes = Long.MAX_VALUE;
            
            for (SystemCounterDescriptor scd : SystemCounterDescriptor.values()) {
                int counterId = scd.id();
                long value = getSystemCounter(counterId);
                
                // Match by name (more reliable than assuming enum order)
                String name = scd.name();
                if ("ERRORS".equals(name)) {
                    errorCount = value;
                } else if ("BACK_PRESSURE_EVENTS".equals(name)) {
                    backpressureCount = value;
                } else if ("CLIENT_TIMEOUTS".equals(name)) {
                    timeoutCount = value;
                } else if ("FREE_SPACE".equals(name)) {
                    freeSpaceBytes = value;
                }
            }
            
            // Calculate rates (per minute, assuming 10s interval)
            long errorRate = (errorCount - lastErrorCount.get()) * 6;  // 6 * 10s = 60s
            long backpressureRate = (backpressureCount - lastBackpressureCount.get()) * 6;
            long timeoutRate = (timeoutCount - lastTimeoutCount.get()) * 6;
            
            // Update state
            currentErrorCount = errorCount;
            currentBackpressureCount = backpressureCount;
            currentTimeoutCount = timeoutCount;
            freeSpace = freeSpaceBytes;
            
            // Check health thresholds
            boolean wasHealthy = healthy;
            healthy = true;
            StringBuilder statusBuilder = new StringBuilder();
            
            if (errorRate > ERROR_RATE_THRESHOLD) {
                healthy = false;
                statusBuilder.append("HIGH_ERROR_RATE(").append(errorRate).append("/min) ");
            }
            
            if (backpressureRate > BACKPRESSURE_THRESHOLD) {
                healthy = false;
                statusBuilder.append("BACKPRESSURE(").append(backpressureRate).append("/min) ");
            }
            
            if (timeoutRate > TIMEOUT_THRESHOLD) {
                healthy = false;
                statusBuilder.append("TIMEOUTS(").append(timeoutRate).append("/min) ");
            }
            
            long freeSpaceMB = freeSpaceBytes / (1024 * 1024);
            if (freeSpaceMB < FREE_SPACE_THRESHOLD_MB) {
                healthy = false;
                statusBuilder.append("LOW_FREE_SPACE(").append(freeSpaceMB).append("MB) ");
            }
            
            if (healthy) {
                healthStatus = "OK";
            } else {
                healthStatus = statusBuilder.toString().trim();
            }
            
            // Log health changes
            if (wasHealthy != healthy) {
                if (healthy) {
                    log.info("✅ MediaDriver health restored: {}", healthStatus);
                } else {
                    log.warn("⚠️  MediaDriver health degraded: {}", healthStatus);
                    log.warn("   Error count: {} (rate: {}/min)", errorCount, errorRate);
                    log.warn("   Backpressure: {} (rate: {}/min)", backpressureCount, backpressureRate);
                    log.warn("   Timeouts: {} (rate: {}/min)", timeoutCount, timeoutRate);
                    log.warn("   Free space: {} MB", freeSpaceMB);
                }
            }
            
            // Update last values
            lastErrorCount.set(errorCount);
            lastBackpressureCount.set(backpressureCount);
            lastTimeoutCount.set(timeoutCount);
            
        } catch (Exception e) {
            log.error("Error checking MediaDriver health", e);
            healthy = false;
            healthStatus = "MONITOR_ERROR: " + e.getMessage();
        }
    }
    
    /**
     * Get system counter value.
     */
    private long getSystemCounter(int counterId) {
        try {
            int counterIdOffset = CountersReader.metaDataOffset(counterId);
            if (counterIdOffset < 0) {
                return 0;  // Counter not found
            }
            return countersReader.getCounterValue(counterId);
        } catch (Exception e) {
            log.debug("Failed to read system counter {}: {}", counterId, e.getMessage());
            return 0;
        }
    }
    
    /**
     * Get current health status.
     * 
     * @return true if MediaDriver is healthy
     */
    public boolean isHealthy() {
        return healthy;
    }
    
    /**
     * Get health status string.
     * 
     * @return Human-readable health status
     */
    public String getHealthStatus() {
        return healthStatus;
    }
    
    /**
     * Get current error count.
     */
    public long getErrorCount() {
        return currentErrorCount;
    }
    
    /**
     * Get current backpressure count.
     */
    public long getBackpressureCount() {
        return currentBackpressureCount;
    }
    
    /**
     * Get current timeout count.
     */
    public long getTimeoutCount() {
        return currentTimeoutCount;
    }
    
    /**
     * Get free space in bytes.
     */
    public long getFreeSpace() {
        return freeSpace;
    }
    
    /**
     * Get free space in MB.
     */
    public long getFreeSpaceMB() {
        return freeSpace / (1024 * 1024);
    }
    
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            if (executor != null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
            log.info("MediaDriver health monitor stopped");
        }
    }
}
