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

import io.aeron.cluster.client.AeronCluster;
import io.aeron.cluster.client.EgressListener;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.IntToLongFunction;

final class AeronInternalClusterClientConnector {

    enum FailureKind {
        NONE,
        SESSION_LIMIT,
        FAILED
    }

    static final class ConnectAttemptResult {
        final AeronCluster client;
        final FailureKind failureKind;
        final String failureMessage;

        private ConnectAttemptResult(AeronCluster client, FailureKind failureKind, String failureMessage) {
            this.client = client;
            this.failureKind = failureKind;
            this.failureMessage = failureMessage;
        }

        static ConnectAttemptResult success(AeronCluster client) {
            return new ConnectAttemptResult(client, FailureKind.NONE, null);
        }

        static ConnectAttemptResult failure(FailureKind failureKind, String failureMessage) {
            return new ConnectAttemptResult(null, failureKind, failureMessage);
        }

        boolean isSuccess() {
            return client != null;
        }
    }

    interface ClusterClientFactory {
        AeronCluster connect(String aeronDirectoryName,
                             AeronIngressEndpointPlanner.Plan ingressPlan,
                             IdleStrategy idleStrategy);
    }

    private static final Logger log = LoggerFactory.getLogger(AeronInternalClusterClientConnector.class);

    private final ClusterClientFactory clusterClientFactory;
    private final AeronClusterAddressResolver.Sleeper sleeper;
    private final int maxRetries;
    private final IntToLongFunction retryDelayMs;

    AeronInternalClusterClientConnector() {
        this(AeronInternalClusterClientConnector::connectCluster, Thread::sleep, 10, attempt -> 1000L * attempt);
    }

    AeronInternalClusterClientConnector(ClusterClientFactory clusterClientFactory,
                                        AeronClusterAddressResolver.Sleeper sleeper,
                                        int maxRetries,
                                        IntToLongFunction retryDelayMs) {
        this.clusterClientFactory = clusterClientFactory;
        this.sleeper = sleeper;
        this.maxRetries = Math.max(1, maxRetries);
        this.retryDelayMs = retryDelayMs;
    }

    AeronCluster ensureConnected(AeronCluster currentClient,
                                 String aeronDirectoryName,
                                 AeronIngressEndpointPlanner.Plan ingressPlan,
                                 IdleStrategy idleStrategy) {
        if (currentClient != null && !currentClient.isClosed()) {
            return currentClient;
        }

        if (currentClient != null) {
            try {
                currentClient.close();
            } catch (Exception e) {
                log.debug("Error closing stale internal cluster client: {}", e.getMessage());
            }
        }

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                AeronCluster client = clusterClientFactory.connect(aeronDirectoryName, ingressPlan, idleStrategy);
                log.info("✅ Internal AeronCluster client created successfully (attempt {})", attempt);
                log.info("   Egress binding: {} (distributed network access)", ingressPlan.clientIp);
                return client;
            } catch (Exception e) {
                if (attempt >= maxRetries) {
                    log.error("❌ Failed to create internal AeronCluster client after {} attempts", maxRetries, e);
                    return null;
                }
                long delayMs = retryDelayMs.applyAsLong(attempt);
                log.debug("⚠️  Failed to create internal cluster client (attempt {}): {} - retrying...",
                    attempt, e.getMessage());
                try {
                    sleeper.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }

        return null;
    }

    ConnectAttemptResult connectOnce(AeronCluster currentClient,
                                     String aeronDirectoryName,
                                     AeronIngressEndpointPlanner.Plan ingressPlan,
                                     IdleStrategy idleStrategy) {
        if (currentClient != null && !currentClient.isClosed()) {
            return ConnectAttemptResult.success(currentClient);
        }

        if (currentClient != null) {
            try {
                currentClient.close();
            } catch (Exception e) {
                log.debug("Error closing stale internal cluster client: {}", e.getMessage());
            }
        }

        try {
            return ConnectAttemptResult.success(clusterClientFactory.connect(aeronDirectoryName, ingressPlan, idleStrategy));
        } catch (Exception e) {
            FailureKind failureKind = isSessionLimitFailure(e) ? FailureKind.SESSION_LIMIT : FailureKind.FAILED;
            String failureMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ConnectAttemptResult.failure(failureKind, failureMessage);
        }
    }

    private static boolean isSessionLimitFailure(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.toLowerCase(java.util.Locale.ROOT).contains("concurrent session limit")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static AeronCluster connectCluster(String aeronDirectoryName,
                                               AeronIngressEndpointPlanner.Plan ingressPlan,
                                               IdleStrategy idleStrategy) {
        EgressListener egressListener = (clusterSessionId, timestamp, message, header, offset, length) ->
            log.debug("Received egress message from cluster (session: {}, length: {})", clusterSessionId, length);

        return AeronCluster.connect(
            new AeronCluster.Context()
                .aeronDirectoryName(aeronDirectoryName)
                .ingressChannel("aeron:udp?term-length=128m")
                .ingressEndpoints(ingressPlan.ingressEndpoints)
                .egressChannel("aeron:udp?endpoint=" + ingressPlan.clientIp + ":0")
                .egressListener(egressListener)
                .idleStrategy(idleStrategy)
                .errorHandler(e -> log.error("Internal cluster client error", e))
        );
    }
}
