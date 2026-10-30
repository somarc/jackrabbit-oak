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

import org.apache.jackrabbit.oak.spi.blob.data.DataIdentifier;
import org.apache.jackrabbit.oak.spi.blob.data.DataStoreException;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Properties;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Runs the DataStore against a Kubo node whose RPC API requires a bearer token.
 */
public class IPFSKuboAuthTest {

    private static final String TOKEN = "oak-test-token";

    @ClassRule
    public static final KuboDockerRule KUBO = new KuboDockerRule(TOKEN);

    @Rule
    public final TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void testConfiguredTokenIsAcceptedAndMissingTokenIsRefused() throws Exception {
        IPFSDataStore authenticated = dataStore("Bearer " + TOKEN);
        byte[] content = "authenticated content".getBytes(StandardCharsets.UTF_8);
        File file = folder.newFile();
        Files.write(file.toPath(), content);
        DataIdentifier id = new DataIdentifier("auth-blob");
        IPFSBackend backend = (IPFSBackend) authenticated.getBackend();
        backend.write(id, file);
        try (InputStream stream = backend.read(id)) {
            assertArrayEquals(content, stream.readAllBytes());
        }
        authenticated.close();

        for (String authorization : new String[] {null, "Bearer wrong-token"}) {
            try {
                dataStore(authorization);
                fail("Expected Kubo to refuse authorization " + authorization);
            } catch (DataStoreException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("403"));
                for (Throwable t = e; t != null; t = t.getCause()) {
                    assertFalse(String.valueOf(t.getMessage()).contains("wrong-token"));
                    assertFalse(String.valueOf(t.getMessage()).contains(TOKEN));
                }
            }
        }
    }

    private IPFSDataStore dataStore(String authorization) throws Exception {
        Properties properties = new Properties();
        properties.setProperty(IpfsClientSettings.ENDPOINT, KUBO.apiEndpoint());
        properties.setProperty(IpfsClientSettings.MAX_ATTEMPTS, "1");
        if (authorization != null) {
            properties.setProperty(IpfsClientSettings.AUTHORIZATION, authorization);
        }
        IPFSDataStore dataStore = new IPFSDataStore();
        dataStore.setProperties(properties);
        dataStore.setCacheSize(0);
        dataStore.init(folder.newFolder().getAbsolutePath());
        return dataStore;
    }
}
