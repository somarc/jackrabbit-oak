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

import io.aeron.cluster.service.Cluster;
import org.osgi.service.component.annotations.Component;

/**
 * Centralized health/heartbeat tracking for Aeron consensus.
 */
@Component(service = AeronHealthService.class)
public class AeronHealthService {

    private static final long HEARTBEAT_MAX_AGE_MS =
        Long.getLong("oak.cluster.heartbeat.maxAgeMs", 30000L);

    private volatile long lastHeartbeatTime = System.currentTimeMillis();

    public void markHeartbeat() {
        lastHeartbeatTime = System.currentTimeMillis();
    }

    public long getLastHeartbeatTime() {
        return lastHeartbeatTime;
    }

    public long getHeartbeatAgeMs() {
        return System.currentTimeMillis() - lastHeartbeatTime;
    }

    public boolean isHeartbeatStale() {
        return getHeartbeatAgeMs() > HEARTBEAT_MAX_AGE_MS;
    }

    public boolean isClusterHealthy(Cluster cluster,
                                    java.util.function.Supplier<Boolean> quorumSupplier,
                                    java.util.function.Supplier<io.aeron.cluster.client.AeronCluster> clientSupplier) {
        if (cluster == null) {
            return false;
        }
        if (cluster.role() == Cluster.Role.CANDIDATE) {
            return false;
        }
        if (quorumSupplier != null && !quorumSupplier.get()) {
            return false;
        }
        io.aeron.cluster.client.AeronCluster client = clientSupplier != null ? clientSupplier.get() : null;
        return client == null || !client.isClosed();
    }

    public String getUnhealthyReason(Cluster cluster,
                                     java.util.function.Supplier<Boolean> quorumSupplier,
                                     java.util.function.Supplier<io.aeron.cluster.client.AeronCluster> clientSupplier) {
        if (cluster == null) {
            return "cluster_not_initialized";
        }
        if (cluster.role() == Cluster.Role.CANDIDATE) {
            return "leader_election_in_progress";
        }
        if (quorumSupplier != null && !quorumSupplier.get()) {
            return "no_quorum";
        }
        io.aeron.cluster.client.AeronCluster client = clientSupplier != null ? clientSupplier.get() : null;
        if (client != null && client.isClosed()) {
            return "session_closed_timeout";
        }
        return null;
    }
}
