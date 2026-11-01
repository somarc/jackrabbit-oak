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
package org.apache.jackrabbit.oak.segment.consensus.queue;

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A2;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_B;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.STRANGER;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.WRITER_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.pathUnder;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ProposalQueueAuthorizationTest {

    @Test
    public void withoutADirectoryAWalletWritesOnlyUnderItsOwnRoot() throws Exception {
        NodeState unfederated = FederationFixtures.store(FederationFixtures.charterA(), null).getRoot();
        for (NodeState root : new NodeState[] {null, unfederated}) {
            assertNull(ProposalQueueManagerOptimized.authorizationError(root, WRITER_A, pathUnder(WRITER_A)));
            assertTrue(ProposalQueueManagerOptimized.authorizationError(root, WRITER_A, pathUnder(NAMESPACE_A2))
                .contains("outside its shard"));
        }
    }

    @Test
    public void withADirectoryTheCommittedOwnershipDecides() throws Exception {
        MemoryNodeStore store = FederationFixtures.store(FederationFixtures.charterA(), FederationFixtures.directory(1,
            FederationFixtures.entryA("ACTIVE"), FederationFixtures.entryB()));
        NodeState root = store.getRoot();
        assertNull(ProposalQueueManagerOptimized.authorizationError(root, WRITER_A, pathUnder(NAMESPACE_A2)));
        assertNull(ProposalQueueManagerOptimized.authorizationError(root, NAMESPACE_A, pathUnder(NAMESPACE_A)));
        assertTrue(ProposalQueueManagerOptimized.authorizationError(root, STRANGER, pathUnder(NAMESPACE_A))
            .contains("not a writer"));
        assertTrue(ProposalQueueManagerOptimized.authorizationError(root, STRANGER, pathUnder(STRANGER))
            .contains("not claimed"));
        assertTrue(ProposalQueueManagerOptimized.authorizationError(root, NAMESPACE_B, pathUnder(NAMESPACE_B))
            .contains("belongs to enterprise"));
    }
}
