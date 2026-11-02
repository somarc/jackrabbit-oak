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

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Applies a replicated BINARY_RELEASE: {@code {"markedAtPosition":n,"maxLastModified":t,"blobIds":[...]}}.
 *
 * <p>Each listed index entry that still exists and was last referenced before {@code markedAtPosition} is
 * removed; entries referenced at or after the mark are skipped. The removal and the applied watermark are one
 * merge, so replay below the floor skips the command. The decision depends only on the command and the
 * committed index, so every member removes the same entries. Deleting the bytes is member-local and happens
 * after the merge, outside this class.</p>
 */
public class BinaryReleaseApplicationService {

    /** Upper bound on the blob ids in one command. */
    public static final int MAX_BLOB_IDS = 1000;

    private static final Logger log = LoggerFactory.getLogger(BinaryReleaseApplicationService.class);

    private final Supplier<NodeStore> nodeStoreSupplier;

    public BinaryReleaseApplicationService(@NotNull Supplier<NodeStore> nodeStoreSupplier) {
        this.nodeStoreSupplier = nodeStoreSupplier;
    }

    @NotNull
    public Result apply(@NotNull Map<String, Object> command, @Nullable AppliedLogPosition position) {
        long markedAtPosition = nonNegative(command, "markedAtPosition", true);
        long maxLastModified = nonNegative(command, "maxLastModified", false);
        Set<String> blobIds = blobIds(command);
        NodeStore nodeStore = MutationApplySupport.requireNodeStore(nodeStoreSupplier);
        NodeBuilder root = nodeStore.getRoot().builder();
        List<String> released = new ArrayList<>();
        for (String blobId : blobIds) {
            if (ReplicatedBinaryIndex.release(root, blobId, markedAtPosition)) {
                released.add(blobId);
            }
        }
        if (position != null) {
            position.writeTo(root);
        }
        try {
            nodeStore.merge(root, EmptyHook.INSTANCE, new CommitInfo("Release " + released.size() + " binaries",
                null, Collections.singletonMap("replicated", "true")));
        } catch (CommitFailedException e) {
            throw new RuntimeException("Failed to commit binary release", e);
        }
        int skipped = blobIds.size() - released.size();
        log.info("Binary release marked at {}: released {}, skipped {} (absent or referenced since the mark)",
            markedAtPosition, released.size(), skipped);
        return new Result(markedAtPosition, maxLastModified, released, skipped, position);
    }

    private static long nonNegative(Map<String, Object> command, String field, boolean required) {
        Object value = command.get(field);
        if (value == null && !required) {
            return 0L;
        }
        if (!(value instanceof Long) || (Long) value < 0) {
            throw new MutationRejectedException("Binary release needs a non-negative " + field);
        }
        return (Long) value;
    }

    private static Set<String> blobIds(Map<String, Object> command) {
        Object value = command.get("blobIds");
        if (!(value instanceof List) || ((List<?>) value).isEmpty() || ((List<?>) value).size() > MAX_BLOB_IDS) {
            throw new MutationRejectedException("Binary release needs 1 to " + MAX_BLOB_IDS + " blobIds");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Object id : (List<?>) value) {
            if (!(id instanceof String) || !ReplicatedBinaryIndex.isIdentifier((String) id)) {
                throw new MutationRejectedException("Binary release blob id is not a SHA-256 identifier: " + id);
            }
            ids.add((String) id);
        }
        return ids;
    }

    /** What one release changed on this member; the same on every member. */
    public static final class Result {
        private final long markedAtPosition;
        private final long maxLastModified;
        private final List<String> released;
        private final int skipped;
        private final AppliedLogPosition position;

        public Result(long markedAtPosition, long maxLastModified, @NotNull List<String> released, int skipped,
                      @Nullable AppliedLogPosition position) {
            this.markedAtPosition = markedAtPosition;
            this.maxLastModified = maxLastModified;
            this.released = Collections.unmodifiableList(new ArrayList<>(released));
            this.skipped = skipped;
            this.position = position;
        }

        public long markedAtPosition() {
            return markedAtPosition;
        }

        /** Records modified after this time (ms) are not deleted; 0 for no bound. */
        public long maxLastModified() {
            return maxLastModified;
        }

        @NotNull
        public List<String> released() {
            return released;
        }

        public int skipped() {
            return skipped;
        }

        @Nullable
        public AppliedLogPosition position() {
            return position;
        }
    }
}
