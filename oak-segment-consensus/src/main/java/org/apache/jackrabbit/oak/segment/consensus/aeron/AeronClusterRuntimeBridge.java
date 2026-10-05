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

import io.aeron.cluster.service.ClusteredService;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AeronClusterRuntimeBridge {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterRuntimeBridge.class);

    interface HealthMonitorFactory {
        MediaDriverHealthMonitor create(io.aeron.Aeron aeron);
    }

    private final ClusteredService clusteredService;
    private final HealthMonitorFactory healthMonitorFactory;

    AeronClusterRuntimeBridge(ClusteredService clusteredService) {
        this(clusteredService, MediaDriverHealthMonitor::new);
    }

    AeronClusterRuntimeBridge(ClusteredService clusteredService, HealthMonitorFactory healthMonitorFactory) {
        this.clusteredService = clusteredService;
        this.healthMonitorFactory = healthMonitorFactory;
    }

    MediaDriverHealthMonitor activate(ClusteredServiceContainer container,
                                      String aeronDirectoryName,
                                      AeronClusterFailureCoordinator failureCoordinator) {
        MediaDriverHealthMonitor healthMonitor = null;

        try {
            io.aeron.Aeron aeron = container.context().aeron();
            if (aeron != null) {
                healthMonitor = healthMonitorFactory.create(aeron);
            } else {
                log.warn("⚠️  Aeron instance not available - health monitor not started");
            }
        } catch (Exception e) {
            log.warn("⚠️  Failed to start MediaDriver health monitor: {}", e.getMessage());
        }

        if (clusteredService instanceof AeronConsensusEngine) {
            AeronConsensusEngine engine = (AeronConsensusEngine) clusteredService;
            engine.setAeronDirectoryName(aeronDirectoryName);
            log.info("✈️  Aeron directory configured: {}", aeronDirectoryName);
        }

        if (failureCoordinator != null) {
            failureCoordinator.scheduleSuccessfulStartupReset();
        }

        return healthMonitor;
    }
}
