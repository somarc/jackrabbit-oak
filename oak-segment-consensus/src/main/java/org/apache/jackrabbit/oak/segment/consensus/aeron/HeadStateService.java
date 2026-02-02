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

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service responsible for tracking HEAD state and finality-aware broadcasting.
 *
 * <p>Extracted from AeronConsensusEngine to reduce class size and centralize
 * HEAD tracking logic used by health endpoints and finality checks.
 */
@Component(
    service = HeadStateService.class,
    immediate = true,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    property = {
        "service.description=HEAD State Service",
        "service.vendor=Apache Software Foundation"
    }
)
public class HeadStateService {

    private static final Logger log = LoggerFactory.getLogger(HeadStateService.class);

    private FileStore fileStore;

    private volatile String committedHead = null;
    private volatile String latestHead = null;
    private volatile int lastCommittedEpoch = -1;

    public HeadStateService() {
        this.fileStore = null;
    }

    public HeadStateService(FileStore fileStore) {
        this.fileStore = fileStore;
    }

    @Activate
    protected void activate() {
        log.info("✅ HeadStateService activated");
    }

    @Deactivate
    protected void deactivate() {
        log.info("✅ HeadStateService deactivated");
    }

    public void setFileStore(FileStore fileStore) {
        this.fileStore = fileStore;
    }

    /**
     * Commit finalized HEAD state at finality boundaries.
     *
     * <p>Idempotent boundary detection: {@code if (currentFinalizedEpoch >= lastCommittedEpoch + 2)}.
     * Safe across missed polls, restarts, or multiple epochs finalized while offline.
     *
     * @param isLeader whether this node is leader
     * @param currentFinalizedEpoch finalized epoch (2 epochs behind current)
     * @param newHeadStr optional HEAD to commit
     * @return true if a boundary was detected and committed
     */
    public boolean checkAndCommitFinalityBoundary(boolean isLeader, int currentFinalizedEpoch, String newHeadStr) {
        if (!isLeader) {
            return false;
        }

        if (currentFinalizedEpoch >= lastCommittedEpoch + 2) {
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🔄 FINALITY BOUNDARY DETECTED (Idempotent)");
            log.info("   Current finalized epoch:  {}", currentFinalizedEpoch);
            log.info("   Last committed epoch:     {}", lastCommittedEpoch);
            log.info("   Epochs to commit:        {}", (currentFinalizedEpoch - lastCommittedEpoch - 1));
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            String safeHead = null;
            if (newHeadStr != null && !newHeadStr.isEmpty()) {
                safeHead = newHeadStr;
            } else if (latestHead != null && !latestHead.isEmpty()) {
                safeHead = latestHead;
                log.debug("Using tracked latestHead for finality broadcast: {}",
                    safeHead.substring(0, Math.min(20, safeHead.length())));
            } else if (fileStore != null) {
                safeHead = fileStore.getHead().getRecordId().toString10();
                log.debug("Using FileStore HEAD for finality broadcast (fallback): {}",
                    safeHead.substring(0, Math.min(20, safeHead.length())));
            }

            if (safeHead != null && !safeHead.isEmpty()) {
                committedHead = safeHead.contains(":")
                    ? safeHead
                    : (fileStore != null ? fileStore.getHead().getRecordId().toString10() : safeHead);

                updateLatestHead(committedHead);

                lastCommittedEpoch = currentFinalizedEpoch - 1;

                log.info("✅ Committed HEAD broadcast: {} (epoch {})",
                    committedHead.substring(0, Math.min(20, committedHead.length())),
                    lastCommittedEpoch);

                return true;
            }

            log.warn("⚠️  Finality boundary detected but no HEAD available to broadcast");
        }

        return false;
    }

    public void updateLatestHead(String newHead) {
        if (newHead != null && !newHead.isEmpty()) {
            try {
                if (newHead.contains(":")) {
                    latestHead = newHead;
                } else if (fileStore != null) {
                    latestHead = fileStore.getHead().getRecordId().toString10();
                } else {
                    latestHead = newHead;
                }
            } catch (Exception e) {
                latestHead = newHead;
            }
        } else if (fileStore != null) {
            try {
                latestHead = fileStore.getHead().getRecordId().toString10();
            } catch (Exception e) {
                log.debug("Could not read HEAD from FileStore: {}", e.getMessage());
            }
        }

        if (latestHead != null) {
            log.debug("📝 Updated latestHead: {}...",
                latestHead.substring(0, Math.min(20, latestHead.length())));
        }
    }

    public String getCommittedHead() {
        return committedHead;
    }

    public String getLatestHead() {
        if (latestHead != null && !latestHead.isEmpty()) {
            return latestHead;
        }
        if (fileStore != null) {
            return fileStore.getHead().getRecordId().toString10();
        }
        return null;
    }

    public int getLastCommittedEpoch() {
        return lastCommittedEpoch;
    }
}
