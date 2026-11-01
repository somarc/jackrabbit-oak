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

import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * Who may write where, decided from the committed charter and directory alone. Ingress uses it to redirect
 * or refuse early; replicated apply uses the same decision so that every member accepts or rejects a mutation
 * identically. Without a committed directory the cluster runs unfederated and nothing here applies.
 */
public final class NamespaceAuthority {

    public enum Verdict {
        /** No directory committed: legacy single-cluster rules apply. */
        UNFEDERATED,
        ALLOW,
        /** The namespace belongs to another enterprise's cluster. */
        REMOTE,
        /** No enterprise owns the namespace. */
        UNCLAIMED,
        /** This cluster's enterprise owns the namespace but is not active in the directory. */
        INACTIVE,
        /** The signer is neither the namespace wallet nor one of the enterprise's writers. */
        NOT_WRITER,
        /** A directory is committed without a charter; cannot happen through apply. */
        NO_CHARTER
    }

    public static final class Decision {
        private final Verdict verdict;
        private final String namespace;
        private final DirectoryEntry owner;

        Decision(Verdict verdict, String namespace, @Nullable DirectoryEntry owner) {
            this.verdict = verdict;
            this.namespace = namespace;
            this.owner = owner;
        }

        public Verdict getVerdict() {
            return verdict;
        }

        public String getNamespace() {
            return namespace;
        }

        @Nullable
        public DirectoryEntry getOwner() {
            return owner;
        }

        public String describe() {
            switch (verdict) {
                case REMOTE:
                    return "Namespace " + namespace + " belongs to enterprise " + owner.getEnterpriseId();
                case UNCLAIMED:
                    return "Namespace " + namespace + " is not claimed by any enterprise";
                case INACTIVE:
                    return "Enterprise " + owner.getEnterpriseId() + " is " + owner.getStatus();
                case NOT_WRITER:
                    return "Signer is not a writer for namespace " + namespace;
                case NO_CHARTER:
                    return "Directory committed without a charter";
                default:
                    return verdict.name();
            }
        }
    }

    private NamespaceAuthority() {
    }

    @NotNull
    public static Decision decide(@Nullable Charter charter, @Nullable DirectorySnapshot directory,
                                  String signer, String namespace) {
        if (directory == null) {
            return new Decision(Verdict.UNFEDERATED, namespace, null);
        }
        if (charter == null) {
            return new Decision(Verdict.NO_CHARTER, namespace, null);
        }
        DirectoryEntry owner = directory.ownerOf(namespace);
        if (owner == null) {
            return new Decision(Verdict.UNCLAIMED, namespace, null);
        }
        if (!owner.getEnterpriseId().equals(charter.getEnterpriseId())) {
            return new Decision(Verdict.REMOTE, namespace, owner);
        }
        if (owner.getStatus() != DirectoryEntry.Status.ACTIVE) {
            return new Decision(Verdict.INACTIVE, namespace, owner);
        }
        if (!owner.mayWrite(signer.toLowerCase(Locale.ROOT), namespace)) {
            return new Decision(Verdict.NOT_WRITER, namespace, owner);
        }
        return new Decision(Verdict.ALLOW, namespace, owner);
    }

    /**
     * Replicated-apply check for a mutation of {@code path} signed by {@code signer}.
     *
     * @return the namespace written into, or null when the cluster is unfederated
     * @throws MutationRejectedException when the committed directory does not allow the mutation
     */
    @Nullable
    public static String requireWritable(@NotNull NodeState root, @NotNull String signer, @NotNull String path) {
        DirectorySnapshot directory = DirectorySnapshot.read(root);
        if (directory == null) {
            return null;
        }
        String namespace = namespaceOf(path);
        Decision decision = decide(Charter.read(root), directory, signer, namespace);
        if (decision.getVerdict() != Verdict.ALLOW) {
            throw new MutationRejectedException(decision.describe());
        }
        return namespace;
    }

    /**
     * The address root a path lives under: {@code /oak-chain/XX/YY/ZZ/0x<address>/...} with the three levels
     * derived from the address.
     *
     * @throws MutationRejectedException for any other shape
     */
    @NotNull
    public static String namespaceOf(@NotNull String path) {
        String[] parts = path.split("/");
        if (parts.length < 6 || !parts[0].isEmpty() || !"oak-chain".equals(parts[1])
            || !FederationJson.ADDRESS.matcher(parts[5]).matches()) {
            throw new MutationRejectedException("Path is not under a namespace root: " + path);
        }
        String[] levels = WalletPathUtil.getShardLevels(parts[5]);
        if (!levels[0].equals(parts[2]) || !levels[1].equals(parts[3]) || !levels[2].equals(parts[4])) {
            throw new MutationRejectedException("Path levels do not match namespace " + parts[5] + ": " + path);
        }
        return parts[5];
    }
}
