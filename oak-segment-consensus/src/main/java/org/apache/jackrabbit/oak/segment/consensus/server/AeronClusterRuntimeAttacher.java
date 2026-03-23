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
package org.apache.jackrabbit.oak.segment.consensus.server;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronPrometheusMetrics;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronWriteClient;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AeronClusterRuntimeAttacher {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterRuntimeAttacher.class);

    interface WriteClientFactory {
        AeronWriteClient create(int clientId,
                                String aeronDirectoryName,
                                List<String> clusterHostnames,
                                int clusterBasePort,
                                String clientHostname);
    }

    interface MetricsFactory {
        AeronPrometheusMetrics create(io.aeron.Aeron aeron);
    }

    interface DelayedTaskScheduler {
        void schedule(Runnable task, long delay, TimeUnit unit);
    }

    private final WriteClientFactory writeClientFactory;
    private final MetricsFactory metricsFactory;
    private final DelayedTaskScheduler scheduler;

    AeronClusterRuntimeAttacher() {
        this(
            AeronWriteClient::new,
            AeronPrometheusMetrics::new,
            (task, delay, unit) -> {
                java.util.concurrent.ScheduledExecutorService executor =
                    Executors.newSingleThreadScheduledExecutor();
                executor.schedule(() -> {
                    try {
                        task.run();
                    } finally {
                        executor.shutdown();
                    }
                }, delay, unit);
            }
        );
    }

    AeronClusterRuntimeAttacher(WriteClientFactory writeClientFactory,
                                MetricsFactory metricsFactory,
                                DelayedTaskScheduler scheduler) {
        this.writeClientFactory = writeClientFactory;
        this.metricsFactory = metricsFactory;
        this.scheduler = scheduler;
    }

    AeronWriteClient attach(SegmentHttpServer httpServer,
                            AeronClusterLauncher aeronClusterLauncher,
                            List<String> hostnamesList,
                            String clientHostname) {
        String aeronDirectoryName = aeronClusterLauncher.getAeronDirectoryName();
        int clusterBasePort = aeronClusterLauncher.getClusterBasePort();

        AeronWriteClient aeronWriteClient =
            writeClientFactory.create(0, aeronDirectoryName, hostnamesList, clusterBasePort, clientHostname);

        httpServer.setAeronClusterLauncher(aeronClusterLauncher);
        scheduleMetricsInitializationIfNeeded(httpServer, aeronClusterLauncher);

        try {
            aeronWriteClient.connect();
        } catch (Exception e) {
            log.warn("   ⚠️  WARNING: Failed to connect AeronWriteClient: {}", e.getMessage());
        }

        httpServer.setAeronWriteClient(aeronWriteClient);
        return aeronWriteClient;
    }

    private void scheduleMetricsInitializationIfNeeded(SegmentHttpServer httpServer,
                                                       AeronClusterLauncher aeronClusterLauncher) {
        ServerContext context = httpServer.getContext();
        if (context.aeronPrometheusMetrics != null) {
            return;
        }

        scheduler.schedule(() -> {
            try {
                io.aeron.Aeron aeron = aeronClusterLauncher.getAeron();
                if (aeron != null && context.aeronPrometheusMetrics == null) {
                    AeronPrometheusMetrics metrics = metricsFactory.create(aeron);
                    context.setAeronPrometheusMetrics(metrics);
                    log.info("✅ Aeron Prometheus metrics initialized (delayed)");
                }
            } catch (Exception e) {
                log.warn("⚠️  Failed to initialize Aeron Prometheus metrics: {}", e.getMessage());
            }
        }, 5, TimeUnit.SECONDS);
    }
}
