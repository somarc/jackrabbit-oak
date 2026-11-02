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
import org.apache.jackrabbit.oak.blob.cloud.ipfs.CidResolver;
import org.apache.jackrabbit.oak.spi.state.ChildNodeEntry;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Replicated lookup from Oak blob identifiers to IPFS CIDs.
 *
 * <p>Every validator writes the same entry when it applies a write that
 * carries a stored binary, so a validator can read a binary that was stored
 * through another validator's IPFS node. Entries live in the hidden subtree
 * {@code /:oak-chain-binaries/<aa>/<bb>/<sha-256>} with a {@code cid} and,
 * when the blob id encodes it, a {@code length}; both come only from the
 * replicated proposal, so every validator derives the same state.</p>
 *
 * <p>The index starts with the build that writes it: writes applied by older
 * builds have no entries, and replaying such history with this build adds
 * them. Entries are not removed when content is deleted, so an entry means
 * "this binary was stored", not "its bytes are still retained".</p>
 *
 * <p>Every apply that stores or references an indexed binary stamps its entry
 * with the entry's log position ({@code refPosition}) and cluster time
 * ({@code refTime}); entries written before the stamps existed read as 0.
 * Binary GC removes an entry only through a replicated release whose mark
 * precedes the last reference (see {@link #release}).</p>
 */
public final class ReplicatedBinaryIndex implements CidResolver {

    static final String ROOT = ":oak-chain-binaries";
    static final String CID = "cid";
    static final String LENGTH = "length";
    static final String REF_POSITION = "refPosition";
    static final String REF_TIME = "refTime";

    private static final Pattern SHA256_HEX = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CID_FORMAT = Pattern.compile("Qm[1-9A-HJ-NP-Za-km-z]{44}|b[a-z2-7]{20,}");

    private final NodeStore nodeStore;

    public ReplicatedBinaryIndex(@NotNull NodeStore nodeStore) {
        this.nodeStore = nodeStore;
    }

    /**
     * Records the CID of a binary in a replicated write; a later write of the
     * same identifier replaces it. Identifiers that are not SHA-256 hex (such as
     * inline binaries) and malformed CIDs are not recorded.
     */
    static void record(@NotNull NodeBuilder root, @NotNull String blobId, @NotNull String cid) {
        int separator = blobId.indexOf('#');
        String identifier = separator < 0 ? blobId : blobId.substring(0, separator);
        if (!SHA256_HEX.matcher(identifier).matches() || !CID_FORMAT.matcher(cid).matches()) {
            return;
        }
        NodeBuilder entry = root.child(ROOT).child(identifier.substring(0, 2)).child(identifier.substring(2, 4))
            .child(identifier);
        entry.setProperty(CID, cid);
        long length = separator < 0 ? -1 : parseLength(blobId.substring(separator + 1));
        if (length >= 0) {
            entry.setProperty(LENGTH, length);
        } else {
            entry.removeProperty(LENGTH);
        }
    }

    /**
     * Stamps the entry of {@code blobId}, when the index holds one, with the log position and cluster time of
     * the apply that stores or references it. A null position (a direct call outside the cluster log) leaves
     * {@code refPosition} unchanged.
     */
    static void stamp(@NotNull NodeBuilder root, @NotNull String blobId, @Nullable AppliedLogPosition position,
                      long time) {
        String identifier = identifier(blobId);
        if (identifier == null) {
            return;
        }
        NodeBuilder entry = entry(root, identifier);
        if (!entry.exists()) {
            return;
        }
        if (position != null) {
            entry.setProperty(REF_POSITION, position.position());
        }
        entry.setProperty(REF_TIME, time);
    }

    /**
     * Removes the entry of {@code identifier} if it exists and was last referenced before
     * {@code markedAtPosition}; empty fan-out nodes are pruned.
     *
     * @return true when the entry was removed; false when it is absent or was referenced at or after the mark
     */
    static boolean release(@NotNull NodeBuilder root, @NotNull String identifier, long markedAtPosition) {
        if (!SHA256_HEX.matcher(identifier).matches()) {
            return false;
        }
        NodeBuilder entry = entry(root, identifier);
        if (!entry.exists() || longValue(entry.getProperty(REF_POSITION)) >= markedAtPosition) {
            return false;
        }
        entry.remove();
        NodeBuilder second = root.getChildNode(ROOT).getChildNode(identifier.substring(0, 2))
            .getChildNode(identifier.substring(2, 4));
        if (second.getChildNodeCount(1) == 0) {
            second.remove();
            NodeBuilder first = root.getChildNode(ROOT).getChildNode(identifier.substring(0, 2));
            if (first.getChildNodeCount(1) == 0) {
                first.remove();
            }
        }
        return true;
    }

    /** True when {@code identifier} is SHA-256 hex, the only form the index records. */
    public static boolean isIdentifier(@Nullable String identifier) {
        return identifier != null && SHA256_HEX.matcher(identifier).matches();
    }

    /** The SHA-256 identifier of a blob id ({@code <sha-256>[#length]}), or null for any other form. */
    @Nullable
    public static String identifier(@NotNull String blobId) {
        int separator = blobId.indexOf('#');
        String identifier = separator < 0 ? blobId : blobId.substring(0, separator);
        return isIdentifier(identifier) ? identifier : null;
    }

    /** True when the index in {@code root} holds an entry for {@code identifier}. */
    public static boolean contains(@NotNull NodeState root, @NotNull String identifier) {
        return isIdentifier(identifier) && root.getChildNode(ROOT).getChildNode(identifier.substring(0, 2))
            .getChildNode(identifier.substring(2, 4)).hasChildNode(identifier);
    }

    /** Visits every entry of the index in {@code root}, in fan-out order. */
    public static void forEach(@NotNull NodeState root, @NotNull Consumer<Entry> visitor) {
        for (ChildNodeEntry first : root.getChildNode(ROOT).getChildNodeEntries()) {
            for (ChildNodeEntry second : first.getNodeState().getChildNodeEntries()) {
                for (ChildNodeEntry entry : second.getNodeState().getChildNodeEntries()) {
                    NodeState state = entry.getNodeState();
                    visitor.accept(new Entry(entry.getName(), longValue(state.getProperty(REF_POSITION)),
                        longValue(state.getProperty(REF_TIME))));
                }
            }
        }
    }

    /** An index entry: its identifier and the log position and cluster time of its last reference. */
    public static final class Entry {
        private final String identifier;
        private final long refPosition;
        private final long refTime;

        public Entry(@NotNull String identifier, long refPosition, long refTime) {
            this.identifier = identifier;
            this.refPosition = refPosition;
            this.refTime = refTime;
        }

        @NotNull
        public String identifier() {
            return identifier;
        }

        public long refPosition() {
            return refPosition;
        }

        public long refTime() {
            return refTime;
        }
    }

    private static NodeBuilder entry(NodeBuilder root, String identifier) {
        return root.getChildNode(ROOT).getChildNode(identifier.substring(0, 2))
            .getChildNode(identifier.substring(2, 4)).getChildNode(identifier);
    }

    private static long longValue(@Nullable PropertyState property) {
        return property == null || property.getType() != Type.LONG ? 0L : property.getValue(Type.LONG);
    }

    @Override
    @Nullable
    public Resolution resolve(@NotNull String identifier) {
        if (!SHA256_HEX.matcher(identifier).matches()) {
            return null;
        }
        NodeState entry = nodeStore.getRoot().getChildNode(ROOT).getChildNode(identifier.substring(0, 2))
            .getChildNode(identifier.substring(2, 4)).getChildNode(identifier);
        PropertyState cid = entry.getProperty(CID);
        if (cid == null) {
            return null;
        }
        PropertyState length = entry.getProperty(LENGTH);
        return new Resolution(cid.getValue(Type.STRING), length == null ? -1 : length.getValue(Type.LONG));
    }

    private static long parseLength(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
