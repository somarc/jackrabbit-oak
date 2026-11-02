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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

final class AeronClusterStartupPreflight {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterStartupPreflight.class);

    private final CrashHandler crashHandler;

    AeronClusterStartupPreflight(CrashHandler crashHandler) {
        this.crashHandler = crashHandler;
    }

    PreflightResult run(String aeronDirectoryName) {
        File aeronDir = new File(aeronDirectoryName);
        boolean hasCrashMarkers = crashHandler != null && crashHandler.hasCrashed();
        boolean staleDriverDirectoryDetected = hasResidualDriverState(aeronDir);

        if (hasCrashMarkers) {
            log.warn("⚠️  Crash markers detected from previous run: {}", crashHandler.getState());
        }
        if (staleDriverDirectoryDetected) {
            log.info("MediaDriver directory {} exists; the driver refuses to start if its owner is alive, "
                + "otherwise saves its error log next to it and recreates it", aeronDirectoryName);
        }

        boolean forceBootstrap = crashHandler != null && crashHandler.shouldForceBootstrap();
        if (forceBootstrap) {
            log.warn("🚨 Force bootstrap marker detected - will bootstrap on startup");
        }

        return new PreflightResult(
            hasCrashMarkers,
            staleDriverDirectoryDetected,
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

    static final class PreflightResult {
        final boolean hasCrashMarkers;
        final boolean staleDriverDirectoryDetected;
        final boolean forceBootstrap;

        PreflightResult(boolean hasCrashMarkers,
                        boolean staleDriverDirectoryDetected,
                        boolean forceBootstrap) {
            this.hasCrashMarkers = hasCrashMarkers;
            this.staleDriverDirectoryDetected = staleDriverDirectoryDetected;
            this.forceBootstrap = forceBootstrap;
        }
    }
}
