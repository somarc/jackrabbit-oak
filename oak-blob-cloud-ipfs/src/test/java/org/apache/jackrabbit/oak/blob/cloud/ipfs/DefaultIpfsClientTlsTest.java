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

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.ClassRule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.TrustManagerFactory;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Proves the client speaks TLS to an HTTPS endpoint, sends its credentials
 * there, and verifies the server certificate.
 */
public class DefaultIpfsClientTlsTest {

    @ClassRule
    public static final TemporaryFolder FOLDER = new TemporaryFolder();

    private static final char[] PASSWORD = "changeit".toCharArray();
    private static final AtomicReference<String> AUTHORIZATION = new AtomicReference<>();

    private static HttpsServer server;
    private static SSLContext trustingContext;

    @BeforeClass
    public static void startServer() throws Exception {
        File keystoreFile = new File(FOLDER.getRoot(), "server.p12");
        Process keytool = new ProcessBuilder(
            new File(System.getProperty("java.home"), "bin/keytool").getPath(),
            "-genkeypair", "-alias", "kubo", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
            "-dname", "CN=localhost", "-ext", "SAN=ip:127.0.0.1",
            "-storetype", "PKCS12", "-keystore", keystoreFile.getPath(),
            "-storepass", new String(PASSWORD), "-keypass", new String(PASSWORD))
            .redirectErrorStream(true).start();
        String output = new String(keytool.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(output, 0, keytool.waitFor());

        KeyStore keystore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(keystoreFile.toPath())) {
            keystore.load(in, PASSWORD);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(keystore, PASSWORD);
        SSLContext serverContext = SSLContext.getInstance("TLS");
        serverContext.init(keys.getKeyManagers(), null, null);

        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(keystore);
        trustingContext = SSLContext.getInstance("TLS");
        trustingContext.init(null, trust.getTrustManagers(), null);

        server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(serverContext));
        server.createContext("/api/v0/version", exchange -> {
            AUTHORIZATION.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"Version\":\"tls-test\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void testHttpsEndpointWithCredentials() throws Exception {
        for (String endpoint : new String[] {
            "https://127.0.0.1:" + server.getAddress().getPort(),
            "/ip4/127.0.0.1/tcp/" + server.getAddress().getPort() + "/https"}) {
            IpfsClientSettings settings = new IpfsClientSettings(endpoint, "Bearer over-tls",
                Duration.ofSeconds(5), Duration.ofSeconds(5), 1);

            assertEquals("tls-test", new DefaultIpfsClient(settings, trustingContext).version());
            assertEquals("Bearer over-tls", AUTHORIZATION.get());
        }
    }

    @Test
    public void testUntrustedCertificateIsRejected() throws Exception {
        IpfsClientSettings settings = new IpfsClientSettings("https://127.0.0.1:" + server.getAddress().getPort(),
            null, Duration.ofSeconds(5), Duration.ofSeconds(5), 1);
        try {
            new DefaultIpfsClient(settings).version();
            fail("Expected the self-signed certificate to be rejected");
        } catch (SSLHandshakeException e) {
            // expected: the JVM default trust store does not trust the test certificate
        }
    }
}
