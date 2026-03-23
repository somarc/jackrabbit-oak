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

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class AeronClusterTopologyTest {

    @Test
    public void calculatePortUsesConfiguredBasePortSystemProperty() {
        String previous = System.getProperty(AeronClusterTopology.PORT_BASE_PROPERTY);
        System.setProperty(AeronClusterTopology.PORT_BASE_PROPERTY, "9400");
        try {
            assertEquals(9402, AeronClusterTopology.calculatePort(0, AeronClusterTopology.CLIENT_FACING_PORT_OFFSET));
            assertEquals(9502, AeronClusterTopology.calculatePort(1, AeronClusterTopology.CLIENT_FACING_PORT_OFFSET));
            assertEquals(9607, AeronClusterTopology.calculatePort(2, 7));
        } finally {
            restorePortBase(previous);
        }
    }

    @Test
    public void calculatePortUsesNodeStrideFromBasePort() {
        assertEquals(9002, AeronClusterTopology.calculatePort(0, AeronClusterTopology.CLIENT_FACING_PORT_OFFSET));
        assertEquals(9102, AeronClusterTopology.calculatePort(1, AeronClusterTopology.CLIENT_FACING_PORT_OFFSET));
        assertEquals(9207, AeronClusterTopology.calculatePort(2, 7));
    }

    @Test
    public void archiveControlChannelUsesTermLengthAndEndpoint() {
        String channel = AeronClusterTopology.archiveControlChannel(1, "10.0.0.8", 262144);

        assertTrue(channel.startsWith("aeron:udp?"));
        assertTrue(channel.contains("endpoint=10.0.0.8:9101"));
        assertTrue(channel.contains("term-length=256k"));
    }

    @Test
    public void consensusLogChannelUsesManualControlEndpoint() {
        String channel = AeronClusterTopology.consensusLogChannel(0, "10.0.0.7", 131072);

        assertTrue(channel.contains("aeron:udp"));
        assertTrue(channel.contains("term-length=128k"));
        assertTrue(channel.contains("control-mode=manual"));
        assertTrue(channel.contains("10.0.0.7:9006"));
    }

    @Test
    public void replicationChannelUsesEphemeralPort() {
        assertEquals("aeron:udp?endpoint=10.0.0.9:0", AeronClusterTopology.replicationChannel("10.0.0.9"));
    }

    @Test
    public void clusterMembersPreservesAddressOrderAndPendingHostnames() {
        String members = AeronClusterTopology.clusterMembers(Arrays.asList("10.0.0.1", "validator-1"));

        assertEquals(
            "0,10.0.0.1:9002,10.0.0.1:9003,10.0.0.1:9004,10.0.0.1:9005,10.0.0.1:9001|"
                + "1,validator-1:9102,validator-1:9103,validator-1:9104,validator-1:9105,validator-1:9101|",
            members
        );
    }

    private static void restorePortBase(String previous) {
        if (previous == null) {
            System.clearProperty(AeronClusterTopology.PORT_BASE_PROPERTY);
        } else {
            System.setProperty(AeronClusterTopology.PORT_BASE_PROPERTY, previous);
        }
    }
}
