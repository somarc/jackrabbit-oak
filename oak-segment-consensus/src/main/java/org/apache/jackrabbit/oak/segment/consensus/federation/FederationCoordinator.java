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
package org.apache.jackrabbit.oak.segment.consensus.federation;

import org.apache.jackrabbit.oak.segment.consensus.aeron.SimpleMessageHeader;
import org.apache.jackrabbit.oak.segment.consensus.service.FederationApplicationService;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Runs on every member. Each tick compares this member's configured charter with the committed one and
 * publishes a {@link Status}. The leader also proposes what is missing: the charter once after genesis,
 * then each newer directory snapshot from the {@link DirectorySource}, chained after the committed one.
 * Proposals are only offers; whether they take effect is decided at replicated apply.
 */
public final class FederationCoordinator implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(FederationCoordinator.class);

    public interface Proposer {
        boolean canPropose();

        boolean propose(int templateId, String json);
    }

    private final Charter configured;
    private final DirectorySource source;
    private final Supplier<NodeState> rootSupplier;
    private final Proposer proposer;
    private final LongSupplier clock;
    private final long resendAfterMs;
    private ScheduledExecutorService scheduler;
    private volatile Status status = Status.UNKNOWN;
    private String lastProposal;
    private long lastProposalAtMs;

    public FederationCoordinator(@Nullable Charter configured, @Nullable DirectorySource source,
                                 Supplier<NodeState> rootSupplier, Proposer proposer, LongSupplier clock,
                                 long resendAfterMs) {
        this.configured = configured;
        this.source = source;
        this.rootSupplier = rootSupplier;
        this.proposer = proposer;
        this.clock = clock;
        this.resendAfterMs = resendAfterMs;
    }

    public static FederationCoordinator fromConfig(FederationConfig config, Supplier<NodeState> rootSupplier,
                                                   Proposer proposer) {
        return new FederationCoordinator(config.getCharter(), config.directorySource(), rootSupplier, proposer,
            System::currentTimeMillis,
            config.getPollMs() * 3);
    }

    public synchronized void start(long pollMs) {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "federation-coordinator");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::tickQuietly, pollMs, pollMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void tickQuietly() {
        try {
            tick();
        } catch (RuntimeException e) {
            log.warn("Federation tick failed: {}", e.getMessage(), e);
        }
    }

    @NotNull
    public synchronized Status tick() {
        NodeState root = rootSupplier.get();
        Charter committed = Charter.read(root);
        DirectorySnapshot directory = DirectorySnapshot.read(root);
        String action = null;
        String sourceError = null;
        boolean charterMatches = configured == null || committed == null || configured.equals(committed);
        if (!charterMatches) {
            log.error("Configured charter {} differs from the committed charter {}; refusing writes",
                configured, committed);
        } else if (proposer.canPropose()) {
            if (committed == null && configured != null) {
                action = propose(SimpleMessageHeader.TEMPLATE_ID_CHARTER, "charter", configured.toMap(),
                    "charter " + configured.digest());
            } else if (committed != null && source != null) {
                try {
                    DirectorySnapshot latest = source.load();
                    if (directory == null || latest.getDirectoryEpoch() > directory.getDirectoryEpoch()) {
                        DirectorySnapshot chained = latest.chainedAfter(directory);
                        action = propose(SimpleMessageHeader.TEMPLATE_ID_DIRECTORY_UPDATE, "directory",
                            chained.toMap(), "directory epoch " + chained.getDirectoryEpoch());
                    } else if (latest.getDirectoryEpoch() == directory.getDirectoryEpoch()
                        && !latest.sameContent(directory)) {
                        sourceError = source.describe() + " changed without raising directoryEpoch "
                            + directory.getDirectoryEpoch();
                    }
                } catch (Exception e) {
                    sourceError = source.describe() + ": " + e.getMessage();
                }
                if (sourceError != null) {
                    log.warn("Federation directory source: {}", sourceError);
                }
            }
        }
        Status next = new Status(configured, committed, directory, charterMatches,
            committed != null ? FederationApplicationService.genesisDigest(root) : null,
            action != null ? action : status.lastAction, sourceError, clock.getAsLong());
        status = next;
        return next;
    }

    private String propose(int templateId, String key, Map<String, Object> body, String what) {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put(key, body);
        String json = JsonParser.toJson(command);
        long now = clock.getAsLong();
        if (json.equals(lastProposal) && now - lastProposalAtMs < resendAfterMs) {
            return null;
        }
        boolean sent = proposer.propose(templateId, json);
        if (sent) {
            lastProposal = json;
            lastProposalAtMs = now;
            log.info("Proposed {}", what);
        }
        return (sent ? "proposed " : "failed to send ") + what;
    }

    public Status getStatus() {
        return status;
    }

    /** What this member knows about its federation state; node-local, never replicated. */
    public static final class Status {

        static final Status UNKNOWN = new Status(null, null, null, true, null, null, null, 0L);

        private final Charter configured;
        private final Charter committed;
        private final DirectorySnapshot directory;
        private final boolean charterMatches;
        private final String genesisDigest;
        private final String lastAction;
        private final String sourceError;
        private final long checkedAtMs;

        Status(@Nullable Charter configured, @Nullable Charter committed, @Nullable DirectorySnapshot directory,
               boolean charterMatches, @Nullable String genesisDigest, @Nullable String lastAction,
               @Nullable String sourceError, long checkedAtMs) {
            this.configured = configured;
            this.committed = committed;
            this.directory = directory;
            this.charterMatches = charterMatches;
            this.genesisDigest = genesisDigest;
            this.lastAction = lastAction;
            this.sourceError = sourceError;
            this.checkedAtMs = checkedAtMs;
        }

        /** False when this member is configured for a different charter than the one committed. */
        public boolean isCharterMatching() {
            return charterMatches;
        }

        @Nullable
        public String getClusterId() {
            return committed != null && genesisDigest != null ? committed.clusterId(genesisDigest) : null;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("configuredEnterpriseId", configured != null ? configured.getEnterpriseId() : null);
            map.put("charterDigest", committed != null ? committed.digest() : null);
            map.put("charter", committed != null ? committed.toMap() : null);
            map.put("charterMatchesConfig", charterMatches);
            map.put("clusterId", getClusterId());
            map.put("federated", directory != null);
            map.put("directoryEpoch", directory != null ? directory.getDirectoryEpoch() : null);
            map.put("directoryDigest", directory != null ? directory.digest() : null);
            map.put("directoryBlockNumber", directory != null ? directory.getBlockNumber() : null);
            DirectoryEntry local = directory != null && committed != null
                ? directory.entry(committed.getEnterpriseId()) : null;
            map.put("localStatus", local != null ? local.getStatus().name() : null);
            map.put("localNamespaces", local != null ? local.getNamespaces().size() : null);
            map.put("enterprises", directory != null ? directory.getEntries().size() : null);
            map.put("lastAction", lastAction);
            map.put("sourceError", sourceError);
            map.put("checkedAtMs", checkedAtMs);
            return map;
        }
    }
}
