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

import org.bouncycastle.jce.interfaces.ECPrivateKey;
import org.junit.Test;
import org.web3j.crypto.Keys;
import org.web3j.crypto.Sign;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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
            Properties props = loadProperties(keystore);
            assertEquals(created.getWalletAddress(), props.getProperty("walletAddress"));
            assertEquals("ethereum-keccak256-secp256k1", props.getProperty("walletAddressDerivation"));

            EthereumWallet reloaded = new EthereumWallet(keystore.toString());
            assertEquals(created.getWalletAddress(), reloaded.getWalletAddress());
            assertEquals(created.getPublicKeyHex(), reloaded.getPublicKeyHex());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testWalletAddressMatchesEthereumKeyMaterial() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-address-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            EthereumWallet wallet = new EthereumWallet(keystore.toString());
            ECPrivateKey privateKey = (ECPrivateKey) wallet.getPrivateKey();
            String expectedAddress = "0x" + Keys.getAddress(Sign.publicKeyFromPrivate(privateKey.getD()));
            assertEquals(expectedAddress.toLowerCase(), wallet.getWalletAddress().toLowerCase());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testLegacyStoredWalletAddressIsRejected() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-legacy-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            EthereumWallet created = new EthereumWallet(keystore.toString());
            Properties props = loadProperties(keystore);
            props.setProperty("walletAddress", "0x0000000000000000000000000000000000000000");
            props.remove("walletAddressDerivation");
            storeProperties(keystore, props);

            try {
                new EthereumWallet(keystore.toString());
                fail("Expected legacy walletAddress mismatch to be rejected");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("Legacy validator keystore walletAddress detected"));
                assertTrue(e.getMessage().contains(created.getWalletAddress()));
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static Properties loadProperties(Path keystore) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(keystore)) {
            props.load(in);
        }
        return props;
    }

    private static void storeProperties(Path keystore, Properties props) throws IOException {
        try (java.io.OutputStream out = Files.newOutputStream(keystore)) {
            props.store(out, "test");
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
