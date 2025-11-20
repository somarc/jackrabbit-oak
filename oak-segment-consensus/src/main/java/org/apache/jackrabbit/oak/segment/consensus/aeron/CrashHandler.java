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

import io.aeron.exceptions.AeronException;
import io.aeron.exceptions.AeronException.Category;
import io.aeron.exceptions.DriverTimeoutException;
import io.aeron.exceptions.TimeoutException;
import org.agrona.IoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Handles crashes and errors in Aeron Cluster components.
 * 
 * <p>Tracks crash history via marker files and triggers recovery mechanisms
 * to ensure resilient operation of the Aeron Cluster consensus layer.
 * 
 * <p>Key features:
 * <ul>
 *   <li>Detects FATAL MediaDriver errors (timeouts, crashes)</li>
 *   <li>Tracks crash count via marker files</li>
 *   <li>Triggers force bootstrap after max crashes</li>
 *   <li>Enables graceful shutdown on FATAL errors</li>
 * </ul>
 */
public class CrashHandler {
    
    private static final Logger log = LoggerFactory.getLogger(CrashHandler.class);
    
    private static final int MAX_CRASHES = 3;
    private static final String CRASH_MARKER_PREFIX = "node-crash-";
    private static final String FORCE_BOOTSTRAP_MARKER = "node-force-bootstrap";
    
    private final File baseDir;
    private final int nodeId;
    private final AtomicBoolean shutdownScheduled = new AtomicBoolean(false);
    private volatile boolean startupSuccessful = false;
    
    public CrashHandler(File baseDir, int nodeId) {
        this.baseDir = baseDir;
        this.nodeId = nodeId;
    }
    
    /**
     * Determine if a FATAL error should trigger shutdown.
     * 
     * <p>MediaDriver timeout errors (keepalive failures) are FATAL and should
     * trigger shutdown to allow restart/recovery.
     * 
     * @param ex Aeron exception
     * @return true if error should trigger shutdown
     */
    public boolean shouldStop(AeronException ex) {
        // Always stop on FATAL errors
        if (ex.category() == Category.FATAL) {
            log.error("🚨 FATAL Aeron error detected: {} - {}", ex.getClass().getSimpleName(), ex.getMessage());
            return true;
        }
        
        // Stop on ERROR category MediaDriver timeouts
        if (ex.category() == Category.ERROR && ex instanceof DriverTimeoutException) {
            log.error("🚨 MediaDriver timeout error detected: {}", ex.getMessage());
            return true;
        }
        
        // Check for MediaDriver keepalive timeout specifically
        String message = ex.getMessage();
        if (message != null && message.contains("keepalive") && message.contains("timeout")) {
            log.error("🚨 MediaDriver keepalive timeout detected: {}", message);
            return true;
        }
        
        return false;
    }
    
    /**
     * Handle a crash/error by creating marker files.
     * 
     * @param ex Aeron exception that caused the crash
     */
    public void handleCrash(AeronException ex) {
        if (isMediaDriverTimeout(ex)) {
            markAsCrashed();
        } else if (isCorruptedState(ex)) {
            markAsForceBootstrap();
        } else {
            markAsCrashed();
        }
    }
    
    /**
     * Check if error is a MediaDriver timeout (keepalive failure).
     */
    private boolean isMediaDriverTimeout(AeronException ex) {
        String message = ex.getMessage();
        return message != null && (
            message.contains("keepalive") ||
            message.contains("MediaDriver") && message.contains("timeout") ||
            ex instanceof DriverTimeoutException
        );
    }
    
    /**
     * Check if error indicates corrupted disk state (requires bootstrap).
     */
    private boolean isCorruptedState(AeronException ex) {
        String message = ex.getMessage();
        return message != null && (
            message.contains("corrupted") ||
            message.contains("invalid") && message.contains("recording") ||
            message.contains("segment not found")
        );
    }
    
    /**
     * Mark this node as crashed (increment crash count).
     */
    private void markAsCrashed() {
        if (shouldForceBootstrap()) {
            // Already marked for bootstrap, don't increment
            return;
        }
        
        Path currentMarker = getCrashMarker();
        IoUtil.ensureDirectoryExists(baseDir, "");
        
        if (!Files.exists(currentMarker)) {
            // First crash
            createMarkerFile(baseDir.toPath().resolve(CRASH_MARKER_PREFIX + "1"));
            log.warn("📛 Crash marker created: node-crash-1");
        } else {
            // Increment crash count
            int currentCount = getCurrentCrashCount();
            if (currentCount >= MAX_CRASHES) {
                // Too many crashes - force bootstrap
                renameToForceBootstrap(currentMarker);
            } else {
                // Rename to next sequential number
                Path nextMarker = baseDir.toPath().resolve(CRASH_MARKER_PREFIX + (currentCount + 1));
                try {
                    Files.move(currentMarker, nextMarker, StandardCopyOption.ATOMIC_MOVE);
                    log.warn("📛 Crash marker incremented: node-crash-{} → node-crash-{}", currentCount, currentCount + 1);
                } catch (IOException e) {
                    log.error("Failed to increment crash marker", e);
                }
            }
        }
    }
    
    /**
     * Mark this node for force bootstrap (recovery from backup).
     */
    public void markAsForceBootstrap() {
        Path markerFile = getForceBootstrapMarker();
        if (Files.exists(markerFile)) {
            log.info("Force bootstrap marker already exists: {}", markerFile);
            return;
        }
        createMarkerFile(markerFile);
        log.warn("🚨 Force bootstrap marker created - will bootstrap on next startup");
    }
    
    /**
     * Check if force bootstrap is required.
     */
    public boolean shouldForceBootstrap() {
        return Files.exists(getForceBootstrapMarker());
    }
    
    /**
     * Check if node has crashed (has crash markers).
     */
    public boolean hasCrashed() {
        return Files.exists(getCrashMarker());
    }
    
    /**
     * Reset crash markers (called after successful startup).
     */
    public void reset() {
        deleteCrashMarker();
        deleteForceBootstrapMarker();
        startupSuccessful = true;
        log.info("✅ Crash markers reset - startup successful");
    }
    
    /**
     * Get current crash count from marker files.
     */
    private int getCurrentCrashCount() {
        Path currentMarker = getCrashMarker();
        if (!Files.exists(currentMarker)) {
            return 0;
        }
        String fileName = currentMarker.getFileName().toString();
        try {
            return Integer.parseInt(fileName.substring(CRASH_MARKER_PREFIX.length()));
        } catch (NumberFormatException e) {
            return 1; // Default to 1 if parsing fails
        }
    }
    
    /**
     * Get current crash count (public method for metrics).
     */
    public int getCrashCount() {
        return getCurrentCrashCount();
    }
    
    /**
     * Get the current crash marker file path.
     */
    private Path getCrashMarker() {
        Path basePath = baseDir.toPath();
        
        // Find highest numbered crash marker
        int maxCount = 0;
        try {
            if (Files.exists(basePath)) {
                java.util.List<Path> markers = new java.util.ArrayList<>();
                Files.list(basePath)
                    .filter(p -> p.getFileName().toString().startsWith(CRASH_MARKER_PREFIX))
                    .forEach(markers::add);
                
                for (Path p : markers) {
                    String name = p.getFileName().toString();
                    try {
                        int count = Integer.parseInt(name.substring(CRASH_MARKER_PREFIX.length()));
                        if (count > maxCount) {
                            maxCount = count;
                        }
                    } catch (NumberFormatException e) {
                        // Ignore
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Failed to list crash markers", e);
        }
        
        // Return the highest numbered marker if found
        if (maxCount > 0) {
            return basePath.resolve(CRASH_MARKER_PREFIX + maxCount);
        }
        
        // Return initial crash marker path
        return basePath.resolve(CRASH_MARKER_PREFIX + "1");
    }
    
    /**
     * Get force bootstrap marker file path.
     */
    private Path getForceBootstrapMarker() {
        return baseDir.toPath().resolve(FORCE_BOOTSTRAP_MARKER);
    }
    
    /**
     * Create a marker file.
     */
    private void createMarkerFile(Path markerFile) {
        try {
            IoUtil.ensureDirectoryExists(baseDir, "");
            Files.createFile(markerFile);
            log.info("Created marker file: {}", markerFile);
        } catch (IOException e) {
            log.error("Failed to create marker file: {}", markerFile, e);
        }
    }
    
    /**
     * Rename crash marker to force bootstrap marker.
     */
    private void renameToForceBootstrap(Path crashMarker) {
        try {
            Path bootstrapMarker = getForceBootstrapMarker();
            if (Files.exists(crashMarker)) {
                Files.move(crashMarker, bootstrapMarker, StandardCopyOption.ATOMIC_MOVE);
                log.warn("🚨 Crash-loop detected! Renamed {} to {} - forcing bootstrap on next startup", 
                    crashMarker.getFileName(), bootstrapMarker.getFileName());
            }
        } catch (IOException e) {
            log.error("Failed to rename crash marker to force bootstrap marker", e);
        }
    }
    
    /**
     * Delete crash marker files.
     */
    private void deleteCrashMarker() {
        try {
            if (Files.exists(baseDir.toPath())) {
                Files.list(baseDir.toPath())
                    .filter(p -> p.getFileName().toString().startsWith(CRASH_MARKER_PREFIX))
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                            log.info("Deleted crash marker: {}", p.getFileName());
                        } catch (IOException e) {
                            log.warn("Failed to delete crash marker: {}", p, e);
                        }
                    });
            }
        } catch (IOException e) {
            log.warn("Failed to delete crash markers", e);
        }
    }
    
    /**
     * Delete force bootstrap marker file.
     */
    private void deleteForceBootstrapMarker() {
        Path marker = getForceBootstrapMarker();
        if (Files.exists(marker)) {
            try {
                Files.delete(marker);
                log.info("Deleted force bootstrap marker: {}", marker.getFileName());
            } catch (IOException e) {
                log.warn("Failed to delete force bootstrap marker", e);
            }
        }
    }
    
    /**
     * Get current state string for diagnostics.
     */
    public String getState() {
        if (shouldForceBootstrap()) {
            return FORCE_BOOTSTRAP_MARKER;
        }
        if (hasCrashed()) {
            return CRASH_MARKER_PREFIX + getCurrentCrashCount();
        }
        return "None";
    }
    
    /**
     * Check if shutdown has been scheduled (prevents multiple shutdown attempts).
     */
    public boolean isShutdownScheduled() {
        return shutdownScheduled.get();
    }
    
    /**
     * Mark shutdown as scheduled.
     */
    public void markShutdownScheduled() {
        shutdownScheduled.set(true);
    }
}

