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

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class IpfsNetworkPostureTest {

    private static final String DEFAULT_CONFIG = "{\"Identity\":{\"PeerID\":\"12D3KooW\"},"
        + "\"Bootstrap\":[\"auto\"],\"AutoConf\":{},\"Provide\":{},"
        + "\"Routing\":{\"Type\":null,\"DelegatedRouters\":[\"auto\"]},\"Swarm\":{\"RelayClient\":{}}}";

    private static final String PRIVATE_CONFIG = "{\"Bootstrap\":[],\"AutoConf\":{\"Enabled\":false},"
        + "\"Provide\":{\"Enabled\":false},\"Routing\":{\"Type\":\"none\",\"DelegatedRouters\":[]},"
        + "\"Swarm\":{\"RelayClient\":{\"Enabled\":false}}}";

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Test
    public void testADefaultNodeExposesContent() throws Exception {
        IpfsNetworkPosture.Report report = IpfsNetworkPosture.inspect(
            client(DEFAULT_CONFIG, "/ip4/0.0.0.0/tcp/4001", "/ip4/0.0.0.0/udp/4001/quic-v1"), null, TIMEOUT);

        assertNull(report.privateSwarm());
        String exposures = String.join("\n", report.exposures());
        for (String expected : List.of("public HTTP routers", "AutoConf", "Bootstrap", "Provide.Enabled",
                "RelayClient", "quic-v1")) {
            assertTrue(expected + " in " + exposures, exposures.contains(expected));
        }
        assertFalse(report.provenPrivate());
    }

    @Test
    public void testAPrivateConfigurationIsNotEnoughWithoutTheWireProbe() throws Exception {
        IpfsNetworkPosture.Report report = IpfsNetworkPosture.inspect(
            client(PRIVATE_CONFIG, "/ip4/0.0.0.0/tcp/4001"), null, TIMEOUT);

        assertEquals(List.of(), report.exposures());
        assertFalse(report.provenPrivate());
        try {
            IpfsNetworkPosture.enforce(IpfsNetworkPosture.Policy.PRIVATE, report, "/ip4/127.0.0.1/tcp/5001");
            fail("Expected the policy to require a proven network key");
        } catch (IOException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("ipfsSwarmProbeAddress"));
        }
        IpfsNetworkPosture.enforce(IpfsNetworkPosture.Policy.WARN, report, "/ip4/127.0.0.1/tcp/5001");
    }

    @Test
    public void testAProvenPrivateNodePassesEvenWithDefaultAnnouncing() throws Exception {
        String config = PRIVATE_CONFIG.replace("\"Provide\":{\"Enabled\":false}", "\"Provide\":{}");
        try (FakeSwarm swarm = new FakeSwarm((in, out) -> write(out, new byte[24]))) {
            IpfsNetworkPosture.Report report = IpfsNetworkPosture.inspect(
                client(config, "/ip4/0.0.0.0/tcp/4001"), swarm.address(), TIMEOUT);

            assertEquals(Boolean.TRUE, report.privateSwarm());
            assertTrue(report.exposures().toString(), report.provenPrivate());
            IpfsNetworkPosture.enforce(IpfsNetworkPosture.Policy.PRIVATE, report, "kubo");
        }
    }

    @Test
    public void testHttpRoutersAreExposuresEvenOnAPrivateNetwork() throws Exception {
        String config = PRIVATE_CONFIG.replace("\"Type\":\"none\",\"DelegatedRouters\":[]",
            "\"Type\":\"custom\",\"Routers\":{\"cid.contact\":{\"Type\":\"http\",\"Parameters\":{}}}");
        try (FakeSwarm swarm = new FakeSwarm((in, out) -> write(out, new byte[24]))) {
            IpfsNetworkPosture.Report report = IpfsNetworkPosture.inspect(
                client(config, "/ip4/0.0.0.0/tcp/4001"), swarm.address(), TIMEOUT);

            assertEquals(1, report.exposures().size());
            assertTrue(report.exposures().get(0).contains("HTTP routers"));
            assertFalse(report.provenPrivate());
        }
    }

    @Test
    public void testAPublicSwarmIsReportedAndRefused() throws Exception {
        try (FakeSwarm swarm = new FakeSwarm((in, out) -> {
            in.readNBytes(IpfsNetworkPosture.MULTISTREAM.length);
            write(out, IpfsNetworkPosture.MULTISTREAM);
        })) {
            IpfsNetworkPosture.Report report = IpfsNetworkPosture.inspect(
                client(PRIVATE_CONFIG, "/ip4/0.0.0.0/tcp/4001"), swarm.address(), TIMEOUT);

            assertEquals(Boolean.FALSE, report.privateSwarm());
            assertTrue(report.exposures().get(0).contains("no private network key"));
            try {
                IpfsNetworkPosture.enforce(IpfsNetworkPosture.Policy.PRIVATE, report, "kubo");
                fail("Expected a public swarm to be refused");
            } catch (IOException e) {
                assertTrue(e.getMessage().contains("swarm.key"));
            }
        }
    }

    @Test
    public void testAnUnreachableOrSilentListenerIsInconclusive() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        assertNull(IpfsNetworkPosture.probe("127.0.0.1:" + closedPort, TIMEOUT));
        try (FakeSwarm swarm = new FakeSwarm((in, out) -> { })) {
            assertNull(IpfsNetworkPosture.probe(swarm.address(), Duration.ofMillis(300)));
        }
    }

    @Test
    public void testPolicyParsing() {
        assertEquals(IpfsNetworkPosture.Policy.WARN, IpfsNetworkPosture.Policy.parse(null));
        assertEquals(IpfsNetworkPosture.Policy.WARN, IpfsNetworkPosture.Policy.parse(" "));
        assertEquals(IpfsNetworkPosture.Policy.PRIVATE, IpfsNetworkPosture.Policy.parse(" Private "));
        assertEquals(IpfsNetworkPosture.Policy.OFF, IpfsNetworkPosture.Policy.parse("off"));
        try {
            IpfsNetworkPosture.Policy.parse("strict");
            fail("Expected an unknown policy to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("strict"));
        }
        try {
            IpfsNetworkPosture.probe("kubo", TIMEOUT);
            fail("Expected a probe address without a port to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("host:port"));
        }
    }

    private static IpfsClient client(String config, String... listenAddresses) throws Exception {
        IpfsClient client = mock(IpfsClient.class);
        when(client.config()).thenReturn(config);
        when(client.swarmListenAddresses()).thenReturn(List.of(listenAddresses));
        return client;
    }

    private static void write(OutputStream out, byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    private interface Exchange {
        void handle(InputStream in, OutputStream out) throws IOException;
    }

    /**
     * A one-connection TCP listener that answers like a swarm listener would.
     */
    private static final class FakeSwarm implements AutoCloseable {

        private final ServerSocket server = new ServerSocket(0);
        private final Thread thread;

        private FakeSwarm(Exchange exchange) throws IOException {
            thread = new Thread(() -> {
                try (Socket socket = server.accept()) {
                    exchange.handle(socket.getInputStream(), socket.getOutputStream());
                    Thread.sleep(1_000);
                } catch (IOException | InterruptedException ignored) {
                    // the probe closed the connection or the test ended
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        private String address() {
            return "127.0.0.1:" + server.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            server.close();
            thread.interrupt();
        }
    }
}
