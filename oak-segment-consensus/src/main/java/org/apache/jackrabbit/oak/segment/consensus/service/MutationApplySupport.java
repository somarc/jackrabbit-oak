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
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Collections;
import java.util.function.Supplier;

/**
 * Steps shared by the replicated write and delete appliers.
 */
final class MutationApplySupport {

    private MutationApplySupport() {
    }

    @NotNull
    static NodeStore requireNodeStore(Supplier<NodeStore> nodeStoreSupplier) {
        NodeStore nodeStore = nodeStoreSupplier.get();
        if (nodeStore == null) {
            throw new IllegalStateException("NodeStore supplier returned null");
        }
        return nodeStore;
    }

    /**
     * Commit a replicated mutation, recording the applied log position (watermark) in the same merge.
     */
    static void mergeReplicated(NodeStore nodeStore, NodeBuilder rootBuilder, String commitMessage,
                                @Nullable MutationAuditMetadata auditMetadata, String failureMessage) {
        CommitInfo commitInfo = new CommitInfo(commitMessage, null, Collections.singletonMap("replicated", "true"));
        if (auditMetadata != null && auditMetadata.getAppliedLogPosition() != null) {
            auditMetadata.getAppliedLogPosition().writeTo(rootBuilder);
        }
        try {
            nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, commitInfo);
        } catch (CommitFailedException e) {
            throw new RuntimeException(failureMessage, e);
        }
    }

    /** The durability notification to run once the applied change is flushed, or {@code null} if none is due. */
    @Nullable
    static Runnable durabilityRunnable(@Nullable WriteApplicationService.DurabilityCallback callback,
                                       FileStore fileStore, @Nullable String proposalId) {
        if (callback == null || proposalId == null || proposalId.isEmpty()) {
            return null;
        }
        String appliedHead = fileStore.getHead().getRecordId().toString10();
        return () -> callback.onDurable(proposalId, appliedHead);
    }

    /**
     * Log and wrap an apply failure, keeping deterministic rejections distinguishable from node-local faults.
     */
    static RuntimeException applyFailure(Logger log, String what, Exception e) {
        String message = "Failed to apply replicated " + what;
        log.error("❌ " + message, e);
        return e instanceof MutationRejectedException
            ? new MutationRejectedException(message, e)
            : new RuntimeException(message, e);
    }

    /**
     * Extract the organization from {@code /oak-chain/XX/YY/ZZ/0xWALLET/{organization}/content/{contentId}} (ADR 037).
     */
    @Nullable
    static String extractOrganizationFromPath(@Nullable String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        String[] parts = path.split("/");
        if (parts.length < 8) {
            return null;
        }
        String potentialOrg = parts[6];
        if (!"content".equals(potentialOrg) && !potentialOrg.startsWith("0x")) {
            return potentialOrg;
        }
        return null;
    }

    static String truncate(String value, int maxLength) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
