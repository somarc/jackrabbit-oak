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
package org.apache.jackrabbit.oak.segment.http.server;

import org.eclipse.jetty.server.Server;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class TlsConfigurationTest {

    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void pemConfigurationFailsFastWithActionableMessage() throws Exception {
        File cert = temp.newFile("cert.pem");
        File key = temp.newFile("key.pem");

        TlsConfiguration tls = new TlsConfiguration(
            true,
            null,
            "",
            "PKCS12",
            cert.getAbsolutePath(),
            key.getAbsolutePath(),
            null,
            "",
            "none",
            null,
            null
        );

        try {
            tls.configureServer(new Server(), 0, 8443);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Direct PEM TLS loading is not supported"));
            assertTrue(e.getMessage().contains("openssl pkcs12 -export"));
        }
    }

    @Test
    public void ambiguousKeystoreAndPemConfigurationFailsFast() throws Exception {
        File cert = temp.newFile("cert.pem");
        File key = temp.newFile("key.pem");
        File keystore = temp.newFile("keystore.p12");

        TlsConfiguration tls = new TlsConfiguration(
            true,
            keystore.getAbsolutePath(),
            "secret",
            "PKCS12",
            cert.getAbsolutePath(),
            key.getAbsolutePath(),
            null,
            "",
            "none",
            null,
            null
        );

        try {
            tls.configureServer(new Server(), 0, 8443);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Ambiguous TLS configuration"));
        }
    }

    @Test
    public void incompletePemConfigurationFailsFast() throws Exception {
        File cert = temp.newFile("cert.pem");

        TlsConfiguration tls = new TlsConfiguration(
            true,
            null,
            "",
            "PKCS12",
            cert.getAbsolutePath(),
            null,
            null,
            "",
            "none",
            null,
            null
        );

        try {
            tls.configureServer(new Server(), 0, 8443);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Incomplete PEM TLS configuration"));
        }
    }

    @Test
    public void strictMtlsRequiresTruststore() throws Exception {
        File keystore = temp.newFile("keystore.p12");

        TlsConfiguration tls = new TlsConfiguration(
            true,
            keystore.getAbsolutePath(),
            "secret",
            "PKCS12",
            null,
            null,
            null,
            "",
            "need",
            null,
            null
        );

        try {
            tls.configureServer(new Server(), 0, 8443);
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("mTLS strict mode requires truststore configuration"));
        }
    }
}
