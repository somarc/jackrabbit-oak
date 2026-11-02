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
import org.apache.jackrabbit.oak.segment.consensus.federation.Charter;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectorySnapshot;
import org.apache.jackrabbit.oak.segment.consensus.genesis.CanonicalGenesisContent;
import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.function.Supplier;

/**
 * Applies the replicated CHARTER and DIRECTORY_UPDATE commands. Acceptance depends only on the command and
 * the committed store, so every member reaches the same decision; a rejection throws
 * {@link MutationRejectedException} and leaves the store untouched.
 */
public class FederationApplicationService {

    private static final Logger log = LoggerFactory.getLogger(FederationApplicationService.class);

    private final Supplier<NodeStore> nodeStoreSupplier;

    public FederationApplicationService(Supplier<NodeStore> nodeStoreSupplier) {
        this.nodeStoreSupplier = nodeStoreSupplier;
    }

    /** Commits the charter once; an identical charter is accepted as a no-op, a different one is rejected. */
    public void applyCharter(Map<String, Object> command, @Nullable AppliedLogPosition position) {
        NodeStore nodeStore = MutationApplySupport.requireNodeStore(nodeStoreSupplier);
        NodeState root = nodeStore.getRoot();
        Charter proposed = Charter.fromMap(object(command, "charter"));
        Charter committed = Charter.read(root);
        if (committed != null) {
            if (!committed.equals(proposed)) {
                throw new MutationRejectedException("Charter already committed for enterprise "
                    + committed.getEnterpriseId() + "; a cluster's charter never changes");
            }
            log.info("Charter {} already committed", committed.digest());
            return;
        }
        if (genesisDigest(root) == null) {
            throw new MutationRejectedException("Charter proposed before genesis");
        }
        NodeBuilder builder = root.builder();
        proposed.writeTo(builder);
        String message = "Commit charter " + proposed.digest();
        MutationApplySupport.mergeReplicated(nodeStore, builder, message, position, "Failed to commit: " + message);
        log.info("Committed charter {} for enterprise {}", proposed.digest(), proposed.getEnterpriseId());
    }

    /**
     * Commits a directory snapshot that names the committed one as its predecessor and carries a newer
     * directory epoch from the charter's registry.
     */
    public void applyDirectory(Map<String, Object> command, @Nullable AppliedLogPosition position) {
        NodeStore nodeStore = MutationApplySupport.requireNodeStore(nodeStoreSupplier);
        NodeState root = nodeStore.getRoot();
        Charter charter = Charter.read(root);
        if (charter == null) {
            throw new MutationRejectedException("Directory update before the charter is committed");
        }
        DirectorySnapshot proposed = DirectorySnapshot.fromMap(object(command, "directory"));
        if (proposed.getChainId() != charter.getChainId() || !proposed.getRegistry().equals(charter.getRegistry())) {
            throw new MutationRejectedException("Directory snapshot from chain " + proposed.getChainId() + " registry "
                + proposed.getRegistry() + " does not match the charter");
        }
        DirectorySnapshot committed = DirectorySnapshot.read(root);
        String expectedPrev = committed == null ? "" : committed.digest();
        if (!proposed.getPrevDigest().equals(expectedPrev)) {
            throw new MutationRejectedException("Stale directory update: chained after '" + proposed.getPrevDigest()
                + "', committed is '" + expectedPrev + "'");
        }
        if (committed != null && proposed.getDirectoryEpoch() <= committed.getDirectoryEpoch()) {
            throw new MutationRejectedException("Directory epoch " + proposed.getDirectoryEpoch()
                + " is not newer than committed epoch " + committed.getDirectoryEpoch());
        }
        NodeBuilder builder = root.builder();
        proposed.writeTo(builder);
        String message = "Commit directory epoch " + proposed.getDirectoryEpoch();
        MutationApplySupport.mergeReplicated(nodeStore, builder, message, position, "Failed to commit: " + message);
        log.info("Committed directory epoch {} ({} enterprises, digest {})", proposed.getDirectoryEpoch(),
            proposed.getEntries().size(), proposed.digest());
    }

    /** The committed genesis integrity digest, or null before genesis. */
    @Nullable
    public static String genesisDigest(NodeState root) {
        PropertyState digest = CanonicalGenesisContent.getGenesisNode(root).getProperty("integrityDigest");
        return digest != null ? digest.getValue(Type.STRING) : null;
    }

    private static Map<String, Object> object(Map<String, Object> command, String key) {
        Object value = command.get(key);
        if (!(value instanceof Map)) {
            throw new MutationRejectedException("Federation command has no " + key + " object");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }
}
