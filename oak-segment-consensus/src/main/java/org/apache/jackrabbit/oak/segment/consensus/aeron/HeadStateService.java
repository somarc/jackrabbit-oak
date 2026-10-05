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
 * Tracks the latest HEAD for health and status endpoints.
 *
 * <p>Deterministic Aeron consensus has no finality commit, so the committed HEAD
 * is always {@code null} and the last committed epoch always {@code -1}.
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

    private volatile String latestHead = null;

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
        return null;
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
        return -1;
    }
}
