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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Snapshot of Aeron cluster config values that are not otherwise mirrored
 * into runtime system properties.
 */
public final class AeronClusterRuntimeRegistry {

    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.empty());

    private AeronClusterRuntimeRegistry() {
    }

    public static void update(AeronClusterConfig config) {
        if (config == null) {
            SNAPSHOT.set(Snapshot.empty());
            return;
        }
        List<String> peerUrls = new ArrayList<>();
        if (config.peerUrls() != null) {
            for (String peerUrl : config.peerUrls()) {
                if (peerUrl != null && !peerUrl.trim().isEmpty()) {
                    peerUrls.add(peerUrl.trim());
                }
            }
        }
        SNAPSHOT.set(new Snapshot(
            config.enabled(),
            config.nodeId(),
            trimToNull(config.selfUrl()),
            Collections.unmodifiableList(peerUrls),
            config.observeElections(),
            config.logClusterStateDetails()
        ));
    }

    public static void clear() {
        SNAPSHOT.set(Snapshot.empty());
    }

    public static Snapshot snapshot() {
        return SNAPSHOT.get();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static final class Snapshot {
        public final boolean enabled;
        public final int nodeId;
        public final String selfUrl;
        public final List<String> peerUrls;
        public final boolean observeElections;
        public final boolean logClusterStateDetails;

        private Snapshot(boolean enabled,
                         int nodeId,
                         String selfUrl,
                         List<String> peerUrls,
                         boolean observeElections,
                         boolean logClusterStateDetails) {
            this.enabled = enabled;
            this.nodeId = nodeId;
            this.selfUrl = selfUrl;
            this.peerUrls = peerUrls;
            this.observeElections = observeElections;
            this.logClusterStateDetails = logClusterStateDetails;
        }

        private static Snapshot empty() {
            return new Snapshot(true, 0, null, Collections.emptyList(), true, false);
        }
    }
}
