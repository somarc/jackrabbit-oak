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
import io.aeron.cluster.codecs.CloseReason;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

final class AeronInternalIngressClientManager implements AutoCloseable {

    enum State {
        UNBOUND,
        CONNECTING,
        BOUND,
        DRAINING,
        COOLDOWN,
        CLOSED
    }

    private static final Logger log = LoggerFactory.getLogger(AeronInternalIngressClientManager.class);

    private static final long DEFAULT_KEEPALIVE_INTERVAL_MS = 1000L;
    private static final long DEFAULT_POLL_INTERVAL_MS = 100L;
    private static final long DEFAULT_RECONNECT_DELAY_MS = 250L;
    private static final long DEFAULT_SESSION_LIMIT_COOLDOWN_MS = 2000L;
    private static final long DEFAULT_WAIT_POLL_INTERVAL_MS = 50L;

    private final Supplier<AeronInternalClusterClientConnector> connectorSupplier;
    private final AeronIngressEndpointPlanner ingressEndpointPlanner;
    private final Supplier<String> aeronDirectorySupplier;
    private final Supplier<IdleStrategy> idleStrategySupplier;
    private final Supplier<AeronCluster> clientSupplier;
    private final Consumer<AeronCluster> clientConsumer;
    private final ScheduledExecutorService executor;
    private final LongSupplier clock;
    private final long keepAliveIntervalMs;
    private final long pollIntervalMs;
    private final long reconnectDelayMs;
    private final long sessionLimitCooldownMs;
    private final long waitPollIntervalMs;
    private final Object monitor = new Object();

    private volatile State state = State.UNBOUND;
    private volatile boolean reconnectInProgress;
    private volatile long clusterSessionId = -1L;
    private volatile long lastConnectTimestampMs;
    private volatile String lastCloseReason;
    private volatile String lastFailure;
    private volatile long cooldownUntilMs;
    private volatile long lastKeepAliveAtMs;
    private volatile boolean closed;

    private ScheduledFuture<?> serviceFuture;
    private ScheduledFuture<?> connectFuture;
    private boolean pendingCloseExisting;
    private String pendingReason;

    AeronInternalIngressClientManager(Supplier<AeronInternalClusterClientConnector> connectorSupplier,
                                      AeronIngressEndpointPlanner ingressEndpointPlanner,
                                      Supplier<String> aeronDirectorySupplier,
                                      Supplier<IdleStrategy> idleStrategySupplier,
                                      Supplier<AeronCluster> clientSupplier,
                                      Consumer<AeronCluster> clientConsumer) {
        this(
            connectorSupplier,
            ingressEndpointPlanner,
            aeronDirectorySupplier,
            idleStrategySupplier,
            clientSupplier,
            clientConsumer,
            newDaemonExecutor(),
            System::currentTimeMillis,
            DEFAULT_KEEPALIVE_INTERVAL_MS,
            DEFAULT_POLL_INTERVAL_MS,
            DEFAULT_RECONNECT_DELAY_MS,
            DEFAULT_SESSION_LIMIT_COOLDOWN_MS,
            DEFAULT_WAIT_POLL_INTERVAL_MS
        );
    }

    AeronInternalIngressClientManager(Supplier<AeronInternalClusterClientConnector> connectorSupplier,
                                      AeronIngressEndpointPlanner ingressEndpointPlanner,
                                      Supplier<String> aeronDirectorySupplier,
                                      Supplier<IdleStrategy> idleStrategySupplier,
                                      Supplier<AeronCluster> clientSupplier,
                                      Consumer<AeronCluster> clientConsumer,
                                      ScheduledExecutorService executor,
                                      LongSupplier clock,
                                      long keepAliveIntervalMs,
                                      long pollIntervalMs,
                                      long reconnectDelayMs,
                                      long sessionLimitCooldownMs,
                                      long waitPollIntervalMs) {
        this.connectorSupplier = Objects.requireNonNull(connectorSupplier);
        this.ingressEndpointPlanner = Objects.requireNonNull(ingressEndpointPlanner);
        this.aeronDirectorySupplier = Objects.requireNonNull(aeronDirectorySupplier);
        this.idleStrategySupplier = Objects.requireNonNull(idleStrategySupplier);
        this.clientSupplier = Objects.requireNonNull(clientSupplier);
        this.clientConsumer = Objects.requireNonNull(clientConsumer);
        this.executor = Objects.requireNonNull(executor);
        this.clock = Objects.requireNonNull(clock);
        this.keepAliveIntervalMs = keepAliveIntervalMs;
        this.pollIntervalMs = pollIntervalMs;
        this.reconnectDelayMs = reconnectDelayMs;
        this.sessionLimitCooldownMs = sessionLimitCooldownMs;
        this.waitPollIntervalMs = waitPollIntervalMs;
    }

    boolean ensureAvailable(String operationDescription, long waitMs) {
        if (isReady()) {
            return true;
        }

        scheduleConnect("ensure " + operationDescription, 0L, false);
        if (waitMs <= 0L) {
            return isReady();
        }

        long deadline = clock.getAsLong() + waitMs;
        synchronized (monitor) {
            while (!closed && !isReady()) {
                long remainingMs = deadline - clock.getAsLong();
                if (remainingMs <= 0L) {
                    break;
                }
                try {
                    monitor.wait(Math.min(waitPollIntervalMs, remainingMs));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return isReady();
    }

    void requestRebind(String reason) {
        scheduleConnect(reason, reconnectDelayMs, true);
    }

    void notifySendFailure(String reason) {
        scheduleConnect(reason, 0L, true);
    }

    void handleClusterSessionClose(long sessionId, CloseReason closeReason) {
        lastCloseReason = closeReason != null ? closeReason.name() : "UNKNOWN";
        if (closeReason == CloseReason.TIMEOUT && sessionId == clusterSessionId && clusterSessionId > 0L) {
            log.warn("🔄 Internal ingress session {} timed out - scheduling reconnect", sessionId);
            requestRebind("owned_session_timeout");
        } else {
            log.debug("Ignoring cluster session close for non-owned session {} (owned={}, reason={})",
                sessionId, clusterSessionId, closeReason);
        }
    }

    void closeClientNow(String reason) {
        synchronized (monitor) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.DRAINING;
            reconnectInProgress = false;
            cancelScheduledConnect();
            cancelServiceLoop();
            notifyWaiters();
        }
        closeCurrentClient(reason);
        synchronized (monitor) {
            if (state != State.CLOSED) {
                state = State.UNBOUND;
            }
            notifyWaiters();
        }
    }

    void requestClose(String reason) {
        synchronized (monitor) {
            if (closed || state == State.CLOSED) {
                return;
            }
            reconnectInProgress = false;
            cancelScheduledConnect();
            state = State.DRAINING;
            notifyWaiters();
        }
        executor.execute(() -> {
            closeCurrentClient(reason);
            synchronized (monitor) {
                if (state != State.CLOSED) {
                    state = State.UNBOUND;
                }
                notifyWaiters();
            }
        });
    }

    boolean isHealthy() {
        AeronCluster client = clientSupplier.get();
        return client != null && !client.isClosed();
    }

    private boolean isReady() {
        return state == State.BOUND && !reconnectInProgress && isHealthy();
    }

    Map<String, Object> diagnostics() {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("state", state.name());
        diagnostics.put("sessionId", clusterSessionId > 0L ? clusterSessionId : null);
        diagnostics.put("reconnectInProgress", reconnectInProgress);
        diagnostics.put("cooldownUntil", cooldownUntilMs > 0L ? cooldownUntilMs : null);
        diagnostics.put("lastConnectAt", lastConnectTimestampMs > 0L ? lastConnectTimestampMs : null);
        diagnostics.put("lastCloseReason", lastCloseReason);
        diagnostics.put("lastFailure", lastFailure);
        return diagnostics;
    }

    @Override
    public void close() {
        closed = true;
        closeClientNow("manager shutdown");
        synchronized (monitor) {
            state = State.CLOSED;
            notifyWaiters();
        }
        executor.shutdownNow();
    }

    private void scheduleConnect(String reason, long delayMs, boolean closeExisting) {
        synchronized (monitor) {
            if (closed || state == State.CLOSED) {
                return;
            }
            pendingReason = reason;
            pendingCloseExisting = pendingCloseExisting || closeExisting;
            if (connectFuture != null && !connectFuture.isDone()) {
                reconnectInProgress = true;
                notifyWaiters();
                return;
            }

            long safeDelayMs = Math.max(0L, delayMs);
            if (safeDelayMs > 0L) {
                state = State.COOLDOWN;
                cooldownUntilMs = clock.getAsLong() + safeDelayMs;
            } else {
                state = State.CONNECTING;
                cooldownUntilMs = 0L;
            }
            reconnectInProgress = true;
            connectFuture = executor.schedule(this::runConnectAttempt, safeDelayMs, TimeUnit.MILLISECONDS);
            notifyWaiters();
        }
    }

    private void runConnectAttempt() {
        String reason;
        boolean closeExisting;
        synchronized (monitor) {
            if (closed || state == State.CLOSED) {
                reconnectInProgress = false;
                notifyWaiters();
                return;
            }
            reason = pendingReason != null ? pendingReason : "unspecified";
            closeExisting = pendingCloseExisting;
            pendingReason = null;
            pendingCloseExisting = false;
            state = State.CONNECTING;
            notifyWaiters();
        }

        try {
            if (closeExisting) {
                closeCurrentClient(reason);
            } else if (isHealthy()) {
                markBound(clientSupplier.get());
                return;
            }

            String aeronDirectoryName = aeronDirectorySupplier.get();
            if (aeronDirectoryName == null || aeronDirectoryName.trim().isEmpty()) {
                markUnbound("aeron directory not available");
                return;
            }

            AeronIngressEndpointPlanner.Plan ingressPlan = ingressEndpointPlanner.plan();
            if (ingressPlan.ingressEndpoints == null || ingressPlan.ingressEndpoints.trim().isEmpty()) {
                markUnbound("no ingress endpoints available");
                return;
            }

            AeronInternalClusterClientConnector connector = connectorSupplier.get();
            if (connector == null) {
                markUnbound("internal connector unavailable");
                return;
            }

            AeronInternalClusterClientConnector.ConnectAttemptResult result = connector.connectOnce(
                null,
                aeronDirectoryName,
                ingressPlan,
                idleStrategySupplier.get()
            );

            if (result.isSuccess()) {
                markBound(result.client);
                return;
            }

            if (result.failureKind == AeronInternalClusterClientConnector.FailureKind.SESSION_LIMIT) {
                markFailure("session-limit: " + result.failureMessage);
                scheduleCooldownRetry(sessionLimitCooldownMs);
                return;
            }

            markFailure(result.failureMessage);
            scheduleCooldownRetry(reconnectDelayMs);
        } catch (RuntimeException e) {
            markFailure(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            scheduleCooldownRetry(reconnectDelayMs);
        }
    }

    private void scheduleCooldownRetry(long delayMs) {
        synchronized (monitor) {
            if (closed || state == State.CLOSED) {
                reconnectInProgress = false;
                notifyWaiters();
                return;
            }
            long safeDelayMs = Math.max(0L, delayMs);
            state = State.COOLDOWN;
            cooldownUntilMs = clock.getAsLong() + safeDelayMs;
            reconnectInProgress = true;
            connectFuture = executor.schedule(this::runConnectAttempt, safeDelayMs, TimeUnit.MILLISECONDS);
            notifyWaiters();
        }
    }

    private void markBound(AeronCluster client) {
        if (client == null || client.isClosed()) {
            markFailure("connected client was unavailable");
            scheduleCooldownRetry(reconnectDelayMs);
            return;
        }

        clientConsumer.accept(client);
        synchronized (monitor) {
            clusterSessionId = client.clusterSessionId();
            lastConnectTimestampMs = clock.getAsLong();
            lastFailure = null;
            cooldownUntilMs = 0L;
            reconnectInProgress = false;
            state = State.BOUND;
            ensureServiceLoop();
            notifyWaiters();
        }
        log.info("✅ Internal ingress client bound (sessionId={})", clusterSessionId);
    }

    private void markUnbound(String reason) {
        synchronized (monitor) {
            lastFailure = reason;
            reconnectInProgress = false;
            cooldownUntilMs = 0L;
            state = State.UNBOUND;
            notifyWaiters();
        }
        log.warn("⚠️  Internal ingress client remains unbound: {}", reason);
    }

    private void markFailure(String reason) {
        synchronized (monitor) {
            lastFailure = reason;
            reconnectInProgress = false;
            notifyWaiters();
        }
        log.warn("⚠️  Internal ingress client connect failed: {}", reason);
    }

    private void ensureServiceLoop() {
        if (serviceFuture != null && !serviceFuture.isCancelled() && !serviceFuture.isDone()) {
            return;
        }
        serviceFuture = executor.scheduleWithFixedDelay(this::serviceClient, pollIntervalMs, pollIntervalMs, TimeUnit.MILLISECONDS);
    }

    private void serviceClient() {
        if (closed || state == State.CLOSED) {
            return;
        }

        AeronCluster client = clientSupplier.get();
        if (client == null || client.isClosed()) {
            cancelServiceLoop();
            scheduleConnect("service_loop_client_missing", reconnectDelayMs, false);
            return;
        }

        try {
            client.pollEgress();
            long now = clock.getAsLong();
            if (now - lastKeepAliveAtMs >= keepAliveIntervalMs) {
                if (!client.sendKeepAlive()) {
                    log.warn("⚠️  Internal ingress keepalive failed for session {}", client.clusterSessionId());
                    notifySendFailure("keepalive_failed");
                    return;
                }
                lastKeepAliveAtMs = now;
            }
        } catch (RuntimeException e) {
            lastFailure = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            notifySendFailure("service_loop_error");
        }
    }

    private void closeCurrentClient(String reason) {
        AeronCluster existingClient = clientSupplier.get();
        clientConsumer.accept(null);
        clusterSessionId = -1L;
        lastCloseReason = reason;
        cancelServiceLoop();
        if (existingClient == null) {
            return;
        }
        try {
            existingClient.close();
            log.info("🔄 Closed internal ingress client ({})", reason);
        } catch (RuntimeException e) {
            log.debug("Error closing internal ingress client ({}): {}", reason, e.getMessage());
        }
    }

    private void cancelServiceLoop() {
        if (serviceFuture != null) {
            serviceFuture.cancel(false);
            serviceFuture = null;
        }
    }

    private void cancelScheduledConnect() {
        if (connectFuture != null) {
            connectFuture.cancel(false);
            connectFuture = null;
        }
    }

    private void notifyWaiters() {
        synchronized (monitor) {
            monitor.notifyAll();
        }
    }

    private static ScheduledExecutorService newDaemonExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "oak-aeron-ingress-client");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }
}
