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

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.Test;

import java.util.Map;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.charterA;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class CharterTest {

    private static final String REGISTRY = "0x" + "e1".repeat(20);

    @Test
    public void canonicalJsonSortsKeysAndNormalizesCase() {
        Charter charter = Charter.of(ENTERPRISE_A.toUpperCase().replace("0X", "0x"), "sepolia", 11155111L,
            REGISTRY.toUpperCase().replace("0X", "0x"));
        assertEquals("{\"chainId\":11155111,\"charterVersion\":1,\"enterpriseId\":\"" + ENTERPRISE_A
            + "\",\"mode\":\"sepolia\",\"registry\":\"" + REGISTRY + "\"}", charter.canonicalJson());
        assertTrue(charter.digest().matches("^0x[0-9a-f]{64}$"));
    }

    @Test
    public void digestAndClusterIdTrackEveryField() {
        Charter mock = charterA();
        assertEquals(mock.digest(), Charter.fromMap(mock.toMap()).digest());
        assertNotEquals(mock.digest(), Charter.of(ENTERPRISE_A, "local", 0L, Charter.STATIC_REGISTRY).digest());
        assertNotEquals(mock.clusterId("g1"), mock.clusterId("g2"));
        assertEquals(mock.clusterId("g1"), charterA().clusterId("g1"));
    }

    @Test
    public void rejectsChainsThatDoNotMatchTheMode() {
        assertThrows(MutationRejectedException.class, () -> Charter.of(ENTERPRISE_A, "sepolia", 1L, REGISTRY));
        assertThrows(MutationRejectedException.class, () -> Charter.of(ENTERPRISE_A, "mainnet", 11155111L, REGISTRY));
        assertThrows(MutationRejectedException.class,
            () -> Charter.of(ENTERPRISE_A, "mainnet", 1L, Charter.STATIC_REGISTRY));
        assertEquals(1L, Charter.of(ENTERPRISE_A, "mainnet", 1L, REGISTRY).getChainId());
    }

    @Test
    public void rejectsMalformedFields() {
        assertThrows(MutationRejectedException.class, () -> Charter.of("0x1234", "mock", 0L, Charter.STATIC_REGISTRY));
        assertThrows(MutationRejectedException.class, () -> Charter.of(ENTERPRISE_A, "devnet", 0L, Charter.STATIC_REGISTRY));
        assertThrows(MutationRejectedException.class, () -> Charter.of(ENTERPRISE_A, "local", 31337L, "0xnope"));
        Map<String, Object> wrongVersion = charterA().toMap();
        wrongVersion.put("charterVersion", 2L);
        assertThrows(MutationRejectedException.class, () -> Charter.fromMap(wrongVersion));
    }

    @Test
    public void roundTripsThroughTheStore() {
        MemoryNodeStore store = new MemoryNodeStore();
        assertNull(Charter.read(store.getRoot()));
        assertNull(Charter.read(null));
        NodeBuilder root = store.getRoot().builder();
        charterA().writeTo(root);
        assertEquals(charterA(), Charter.read(root.getNodeState()));
        assertEquals(charterA().digest(),
            root.getNodeState().getChildNode(Charter.NODE_NAME).getString("digest"));
    }
}
