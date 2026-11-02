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
package org.apache.jackrabbit.oak.segment.consensus.service;

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Replicated record of every client proposal id that has taken effect, so a proposal has one effect ever.
 *
 * <p>A write or delete records its proposal id in the same Oak merge as its mutation, under
 * {@code /:consensus/proposals/<id[2..4]>/<id[4..6]>/<id>} with the {@code kind}, the {@code path} and the
 * cluster-log time {@code appliedAt}. Apply consults it in log order, so every member ignores the same resends;
 * the leader consults its head to refuse a resend before queueing it. Ids are 0x-prefixed 32-byte hex, keyed in
 * lower case (the on-chain bytes32); other ids are not recorded. Entries are never removed.</p>
 *
 * <p>The index starts with the build that writes it: proposals applied by older builds have no entry.</p>
 */
public final class AppliedProposalIndex {

    static final String NODE_NAME = "proposals";
    static final String KIND = "kind";
    static final String PATH = "path";
    static final String APPLIED_AT = "appliedAt";

    private static final Pattern PROPOSAL_ID = Pattern.compile("0x[0-9a-f]{64}");

    private AppliedProposalIndex() {
    }

    /** The index key of {@code proposalId} (lower-case hex), or null when it is not a 32-byte hex id. */
    @Nullable
    static String key(@Nullable String proposalId) {
        if (proposalId == null) {
            return null;
        }
        String key = proposalId.trim().toLowerCase(Locale.ROOT);
        return PROPOSAL_ID.matcher(key).matches() ? key : null;
    }

    /** The entry of {@code proposalId} in {@code root}, or null when it has not taken effect (or is not indexable). */
    @Nullable
    public static Entry find(@Nullable NodeState root, @Nullable String proposalId) {
        String key = key(proposalId);
        if (root == null || key == null) {
            return null;
        }
        NodeState entry = root.getChildNode(AppliedLogPosition.NODE_NAME).getChildNode(NODE_NAME)
            .getChildNode(key.substring(2, 4)).getChildNode(key.substring(4, 6)).getChildNode(key);
        if (!entry.exists()) {
            return null;
        }
        return new Entry(key, string(entry.getProperty(KIND)), string(entry.getProperty(PATH)),
            entry.getProperty(APPLIED_AT) != null ? entry.getProperty(APPLIED_AT).getValue(Type.LONG) : 0L);
    }

    /**
     * Records {@code proposalId} in {@code rootBuilder}; call before merging the mutation it belongs to.
     *
     * @return false when the id is not indexable and nothing was recorded
     */
    static boolean record(@NotNull NodeBuilder rootBuilder, @Nullable String proposalId,
                          @NotNull MutationAuditMetadata.Operation kind, @NotNull String path, long appliedAt) {
        String key = key(proposalId);
        if (key == null) {
            return false;
        }
        NodeBuilder entry = rootBuilder.child(AppliedLogPosition.NODE_NAME).child(NODE_NAME)
            .child(key.substring(2, 4)).child(key.substring(4, 6)).child(key);
        entry.setProperty(KIND, kind.name());
        entry.setProperty(PATH, path);
        entry.setProperty(APPLIED_AT, appliedAt);
        return true;
    }

    @Nullable
    private static String string(@Nullable PropertyState property) {
        return property != null ? property.getValue(Type.STRING) : null;
    }

    /** A proposal that has taken effect: its id, kind ({@code WRITE} or {@code DELETE}), path and log time. */
    public static final class Entry {
        private final String proposalId;
        private final String kind;
        private final String path;
        private final long appliedAt;

        Entry(@NotNull String proposalId, @Nullable String kind, @Nullable String path, long appliedAt) {
            this.proposalId = proposalId;
            this.kind = kind;
            this.path = path;
            this.appliedAt = appliedAt;
        }

        @NotNull
        public String proposalId() {
            return proposalId;
        }

        @Nullable
        public String kind() {
            return kind;
        }

        @Nullable
        public String path() {
            return path;
        }

        public long appliedAt() {
            return appliedAt;
        }
    }
}
