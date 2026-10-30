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

import io.aeron.cluster.ClusteredMediaDriver;
import io.aeron.cluster.service.ClusteredServiceContainer;
import org.agrona.CloseHelper;
import org.agrona.concurrent.ShutdownSignalBarrier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;

final class AeronClusterShutdownCoordinator {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterShutdownCoordinator.class);

    interface ResourceCloser {
        void close(ClusteredServiceContainer container, ClusteredMediaDriver clusteredMediaDriver);
    }

    private final int nodeId;
    private final ResourceCloser resourceCloser;

    AeronClusterShutdownCoordinator(int nodeId) {
        this(nodeId, (container, clusteredMediaDriver) ->
            CloseHelper.closeAll(
                errorHandler -> log.error("Error during shutdown", errorHandler),
                container,
                clusteredMediaDriver
            )
        );
    }

    AeronClusterShutdownCoordinator(int nodeId, ResourceCloser resourceCloser) {
        this.nodeId = nodeId;
        this.resourceCloser = resourceCloser;
    }

    void shutdown(MediaDriverHealthMonitor healthMonitor,
                  ClusteredServiceContainer container,
                  ClusteredMediaDriver clusteredMediaDriver,
                  ShutdownSignalBarrier barrier,
                  ExecutorService shutdownExecutor) {
        log.info("🛑 Shutting down Aeron Cluster (node {})...", nodeId);

        if (healthMonitor != null) {
            try {
                healthMonitor.close();
            } catch (Exception e) {
                log.warn("Error closing health monitor", e);
            }
        }

        try {
            resourceCloser.close(container, clusteredMediaDriver);
        } catch (RuntimeException e) {
            log.warn("Error closing Aeron resources", e);
        }

        if (barrier != null) {
            try {
                barrier.signal();
            } finally {
                barrier.close();
            }
        }

        if (shutdownExecutor != null) {
            shutdownExecutor.shutdown();
        }

        log.info("✅ Aeron Cluster shut down");
    }
}
