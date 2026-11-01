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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import org.apache.jackrabbit.oak.spi.blob.data.DataStoreException;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Properties;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Against real Kubo nodes, one with a private network key and one without:
 * with {@code ipfsNetworkPolicy=private} the DataStore starts on the first and
 * refuses the second, proven on the wire through the swarm listener.
 */
public class IPFSNetworkPostureKuboTest {

    @ClassRule
    public static final KuboDockerRule PRIVATE_NODE = new KuboDockerRule()
        .online("/key/swarm/psk/1.0.0/\n/base16/\n" + HexFormat.of().formatHex(SecureRandom.getSeed(32)))
        .configured("Provide.Enabled", "false")
        .configured("Swarm.RelayClient.Enabled", "false");

    @ClassRule
    public static final KuboDockerRule PUBLIC_NODE = new KuboDockerRule().online(null);

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testAPrivatePolicyStartsOnlyOnAProvenPrivateNode() throws Exception {
        IPFSDataStore privateStore = dataStore(PRIVATE_NODE);
        privateStore.close();

        try {
            dataStore(PUBLIC_NODE);
            fail("Expected a node without a swarm key to be refused");
        } catch (DataStoreException e) {
            String messages = causes(e);
            assertTrue(messages, messages.contains("no private network key"));
            assertTrue(messages, messages.contains("Provide.Enabled"));
        }
    }

    @Test
    public void testTheProbeTellsTheNodesApart() {
        assertEquals(Boolean.TRUE, IpfsNetworkPosture.probe(PRIVATE_NODE.swarmAddress(), Duration.ofSeconds(5)));
        assertEquals(Boolean.FALSE, IpfsNetworkPosture.probe(PUBLIC_NODE.swarmAddress(), Duration.ofSeconds(5)));
    }

    private IPFSDataStore dataStore(KuboDockerRule node) throws Exception {
        Properties properties = new Properties();
        properties.setProperty(IpfsClientSettings.ENDPOINT, node.apiEndpoint());
        properties.setProperty(IPFSDataStore.NETWORK_POLICY, "private");
        properties.setProperty(IPFSDataStore.SWARM_PROBE_ADDRESS, node.swarmAddress());
        IPFSDataStore dataStore = new IPFSDataStore();
        dataStore.setProperties(properties);
        dataStore.setIpfsFilesRoot("/oak/posture-" + UUID.randomUUID());
        dataStore.setCacheSize(0);
        dataStore.init(folder.newFolder().getAbsolutePath());
        return dataStore;
    }

    private static String causes(Throwable e) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            text.append(t.getMessage()).append(" <- ");
        }
        return text.toString();
    }
}
