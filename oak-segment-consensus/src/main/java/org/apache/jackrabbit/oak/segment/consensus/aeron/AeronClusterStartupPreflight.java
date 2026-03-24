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

import io.aeron.CommonContext;
import org.agrona.IoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

final class AeronClusterStartupPreflight {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterStartupPreflight.class);

    private final int nodeId;
    private final CrashHandler crashHandler;

    AeronClusterStartupPreflight(int nodeId, CrashHandler crashHandler) {
        this.nodeId = nodeId;
        this.crashHandler = crashHandler;
    }

    PreflightResult run(String aeronDirectoryName) {
        cleanupAeronDirectoryIfRequested();

        File aeronDir = new File(aeronDirectoryName);
        boolean hasCrashMarkers = crashHandler != null && crashHandler.hasCrashed();
        boolean staleDriverDirectoryDetected = hasResidualDriverState(aeronDir);
        boolean staleDriverDirectoryDeleted = false;

        if (hasCrashMarkers || staleDriverDirectoryDetected) {
            if (hasCrashMarkers) {
                log.warn("⚠️  Crash markers detected from previous run: {}", crashHandler.getState());
            }
            if (staleDriverDirectoryDetected) {
                log.warn("⚠️  Stale MediaDriver directory detected: {}", aeronDirectoryName);
                log.warn("   This may cause ActiveDriverException if MediaDriver didn't shut down cleanly");
            }

            try {
                File lockFile = new File(aeronDir, "driver.lock");
                if (lockFile.exists()) {
                    log.debug("MediaDriver lock file exists: {}", lockFile.getAbsolutePath());
                }
            } catch (Exception e) {
                log.debug("Could not check MediaDriver lock file: {}", e.getMessage());
            }

            if (aeronDir.exists()) {
                log.warn("🧹 Cleaning up stale MediaDriver directory: {}", aeronDirectoryName);
                try {
                    IoUtil.delete(aeronDir, true);
                    staleDriverDirectoryDeleted = true;
                    log.info("✅ Cleaned up stale MediaDriver directory");
                } catch (Exception e) {
                    log.warn("⚠️  Failed to clean up MediaDriver directory: {}", e.getMessage());
                    log.warn("   You may need to manually delete: {}", aeronDirectoryName);
                }
            }
        }

        boolean forceBootstrap = crashHandler != null && crashHandler.shouldForceBootstrap();
        if (forceBootstrap) {
            log.warn("🚨 Force bootstrap marker detected - will bootstrap on startup");
        }

        return new PreflightResult(
            hasCrashMarkers,
            staleDriverDirectoryDetected,
            staleDriverDirectoryDeleted,
            forceBootstrap
        );
    }

    private static boolean hasResidualDriverState(File aeronDir) {
        if (!aeronDir.exists()) {
            return false;
        }

        File[] children = aeronDir.listFiles();
        return children != null && children.length > 0;
    }

    private void cleanupAeronDirectoryIfRequested() {
        boolean deleteDirsOnStartup = Boolean.getBoolean("aeron.delete.dirs.on.startup");

        if (!deleteDirsOnStartup) {
            log.debug("Aeron directory cleanup disabled (aeron.delete.dirs.on.startup=false)");
            return;
        }

        String aeronDirPath = System.getProperty(
            "aeron.dir.name",
            CommonContext.getAeronDirectoryName() + "-node-" + nodeId
        );
        File aeronDir = new File(aeronDirPath);

        if (!aeronDir.exists()) {
            log.debug("Aeron directory does not exist, nothing to clean: {}", aeronDir.getAbsolutePath());
            return;
        }

        log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.warn("🧹 Cleaning stale Aeron directory (aeron.delete.dirs.on.startup=true)");
        log.warn("   Path: {}", aeronDir.getAbsolutePath());
        log.warn("   ⚠️  This should be DISABLED in production!");
        log.warn("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

        try {
            IoUtil.delete(aeronDir, false);
            log.info("✅ Aeron directory cleaned successfully");
        } catch (Exception e) {
            log.error("❌ Failed to clean Aeron directory - manual cleanup may be required", e);
            log.error("   Path: {}", aeronDir.getAbsolutePath());
            log.error("   Manual cleanup: rm -rf {}", aeronDir.getAbsolutePath());
            throw new RuntimeException("Aeron directory cleanup failed - cannot proceed", e);
        }
    }

    static final class PreflightResult {
        final boolean hasCrashMarkers;
        final boolean staleDriverDirectoryDetected;
        final boolean staleDriverDirectoryDeleted;
        final boolean forceBootstrap;

        PreflightResult(boolean hasCrashMarkers,
                        boolean staleDriverDirectoryDetected,
                        boolean staleDriverDirectoryDeleted,
                        boolean forceBootstrap) {
            this.hasCrashMarkers = hasCrashMarkers;
            this.staleDriverDirectoryDetected = staleDriverDirectoryDetected;
            this.staleDriverDirectoryDeleted = staleDriverDirectoryDeleted;
            this.forceBootstrap = forceBootstrap;
        }
    }
}
