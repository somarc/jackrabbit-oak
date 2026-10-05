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
package org.apache.jackrabbit.oak.segment.consensus.metrics;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;

/**
 * Prometheus metrics this validator actually updates (exported via {@code /metrics}).
 */
public final class ConsensusMetrics {

    public static final Gauge leaderEpoch = Gauge.build()
            .name("oak_consensus_leader_epoch")
            .help("Current leader epoch (increments on leader change)")
            .register();

    public static final Gauge isLeader = Gauge.build()
            .name("oak_consensus_is_leader")
            .help("Whether this validator is currently the leader (1=leader, 0=follower)")
            .register();

    public static final Gauge timeSinceLastHeartbeat = Gauge.build()
            .name("oak_consensus_time_since_last_heartbeat_seconds")
            .help("Seconds since last heartbeat from leader")
            .register();

    public static final Gauge validatorsReachable = Gauge.build()
            .name("oak_validators_reachable")
            .help("Number of validators currently responding to health checks")
            .register();

    public static final Counter ipfsPolicyRejectionsTotal = Counter.build()
            .name("oak_api_ipfs_policy_rejections_total")
            .help("Total number of write rejections due to IPFS supply-chain policy enforcement")
            .labelNames("reason")
            .register();

    public static final Counter enterpriseCidAcceptedTotal = Counter.build()
            .name("oak_api_ipfs_enterprise_cid_accepted_total")
            .help("Total number of enterprise client ipfsCid write requests accepted by policy checks")
            .register();

    public static final Gauge mediaDriverCrashCount = Gauge.build()
            .name("oak_mediadriver_crash_count")
            .help("Number of MediaDriver crashes detected (resets on successful startup)")
            .register();

    public static final Gauge mediaDriverHasCrashed = Gauge.build()
            .name("oak_mediadriver_has_crashed")
            .help("Whether MediaDriver has crashed (1=crashed, 0=healthy)")
            .register();

    public static final Gauge mediaDriverForceBootstrap = Gauge.build()
            .name("oak_mediadriver_force_bootstrap")
            .help("Whether force bootstrap is required after multiple crashes (1=required, 0=not required)")
            .register();

    public static final Gauge segmentsStoredTotal = Gauge.build()
            .name("oak_segments_stored_total")
            .help("Number of Oak TAR segment-store files on this validator")
            .register();

    public static final Gauge segmentsDiskUsageBytes = Gauge.build()
            .name("oak_segments_disk_usage_bytes")
            .help("Logical bytes in Oak TAR segment files; excludes Aeron runtime files and filesystem allocation overhead")
            .register();

    public static final Gauge activeConnections = Gauge.build()
            .name("oak_active_connections")
            .help("Number of active HTTP connections to this validator")
            .register();

    public static void updateLeaderStatus(boolean isCurrentlyLeader, long epoch) {
        isLeader.set(isCurrentlyLeader ? 1 : 0);
        leaderEpoch.set(epoch);
    }

    public static void recordIpfsPolicyRejection(String reason) {
        ipfsPolicyRejectionsTotal.labels(reason == null ? "unknown" : reason).inc();
    }

    public static void recordEnterpriseCidAccepted() {
        enterpriseCidAcceptedTotal.inc();
    }

    private ConsensusMetrics() {
    }
}
