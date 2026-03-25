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
package org.apache.jackrabbit.oak.segment.consensus.security;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class EthereumWalletTest {

    @Test
    public void testWalletCreationAndReloadPreserveIdentity() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            EthereumWallet created = new EthereumWallet(keystore.toString());
            assertTrue("Wallet address must be 0x-prefixed 20-byte hex",
                created.getWalletAddress().matches("^0x[a-fA-F0-9]{40}$"));
            assertTrue("Public key hex must be present", created.getPublicKeyHex().startsWith("0x"));
            assertTrue("Keystore should be created", Files.exists(keystore));

            EthereumWallet reloaded = new EthereumWallet(keystore.toString());
            assertEquals(created.getWalletAddress(), reloaded.getWalletAddress());
            assertEquals(created.getPublicKeyHex(), reloaded.getPublicKeyHex());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
    }
}
