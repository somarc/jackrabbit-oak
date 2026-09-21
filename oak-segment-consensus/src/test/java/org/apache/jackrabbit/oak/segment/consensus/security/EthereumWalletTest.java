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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class EthereumWalletTest {

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Plaintext (backward-compat) tests
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Test
    public void testWalletCreationAndReloadPreserveIdentity() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            EthereumWallet created = new EthereumWallet(keystore.toString(), null);
            assertTrue("Wallet address must be 0x-prefixed 20-byte hex",
                created.getWalletAddress().matches("^0x[a-fA-F0-9]{40}$"));
            assertTrue("Public key hex must be present", created.getPublicKeyHex().startsWith("0x"));
            assertTrue("Keystore should be created", Files.exists(keystore));
            Properties props = loadProperties(keystore);
            assertEquals(created.getWalletAddress(), props.getProperty("walletAddress"));
            assertEquals("ethereum-keccak256-secp256k1", props.getProperty("walletAddressDerivation"));
            assertEquals("plaintext", props.getProperty("walletFormat"));

            EthereumWallet reloaded = new EthereumWallet(keystore.toString(), null);
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
            EthereumWallet wallet = new EthereumWallet(keystore.toString(), null);
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
            EthereumWallet created = new EthereumWallet(keystore.toString(), null);
            Properties props = loadProperties(keystore);
            props.setProperty("walletAddress", "0x0000000000000000000000000000000000000000");
            props.remove("walletAddressDerivation");
            storeProperties(keystore, props);

            try {
                new EthereumWallet(keystore.toString(), null);
                fail("Expected legacy walletAddress mismatch to be rejected");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("Legacy validator keystore walletAddress detected"));
                assertTrue(e.getMessage().contains(created.getWalletAddress()));
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Encryption at rest tests (Track 6b)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    @Test
    public void testEncryptedWalletCreationAndReload() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-encrypted-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            EthereumWallet created = new EthereumWallet(keystore.toString(), "s3cr3t-passphrase");
            assertTrue("Wallet address must be 0x-prefixed 20-byte hex",
                created.getWalletAddress().matches("^0x[a-fA-F0-9]{40}$"));

            Properties props = loadProperties(keystore);
            assertEquals("encrypted-aes256-gcm-pbkdf2-v1", props.getProperty("walletFormat"));
            assertFalse("Encrypted keystore must not contain plaintext privateKey",
                props.containsKey("privateKey"));
            assertTrue("Encrypted keystore must contain encryptedPrivateKey",
                props.containsKey("encryptedPrivateKey"));
            assertTrue("Encrypted keystore must contain kdfSalt", props.containsKey("kdfSalt"));
            assertTrue("Encrypted keystore must contain gcmIv", props.containsKey("gcmIv"));

            EthereumWallet reloaded = new EthereumWallet(keystore.toString(), "s3cr3t-passphrase");
            assertEquals(created.getWalletAddress(), reloaded.getWalletAddress());
            assertEquals(created.getPublicKeyHex(), reloaded.getPublicKeyHex());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testEncryptedKeystoreRejectedWithoutPassphrase() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-no-passphrase-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            new EthereumWallet(keystore.toString(), "correct-passphrase");

            try {
                new EthereumWallet(keystore.toString(), null);
                fail("Expected encrypted keystore load without passphrase to fail");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("Keystore is encrypted but no passphrase provided"));
                assertTrue(e.getMessage().contains("VALIDATOR_WALLET_PASSPHRASE"));
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testWrongPassphraseFails() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-wrong-passphrase-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            new EthereumWallet(keystore.toString(), "correct-passphrase");

            try {
                new EthereumWallet(keystore.toString(), "wrong-passphrase");
                fail("Expected wrong passphrase to fail AES-GCM auth");
            } catch (IllegalStateException e) {
                assertTrue("Error must mention wrong passphrase or AES-GCM",
                    e.getMessage().contains("Wrong passphrase or corrupted keystore"));
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testPlaintextKeystoreMigratedToEncryptedOnLoad() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-migration-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            // Create without passphrase — plaintext
            EthereumWallet plaintext = new EthereumWallet(keystore.toString(), null);
            assertEquals("plaintext", loadProperties(keystore).getProperty("walletFormat"));

            // Reload with passphrase — triggers migration
            EthereumWallet migrated = new EthereumWallet(keystore.toString(), "migration-passphrase");
            assertEquals(plaintext.getWalletAddress(), migrated.getWalletAddress());

            Properties props = loadProperties(keystore);
            assertEquals("encrypted-aes256-gcm-pbkdf2-v1", props.getProperty("walletFormat"));
            assertFalse("Migrated keystore must not contain plaintext privateKey",
                props.containsKey("privateKey"));

            // Reload with same passphrase succeeds
            EthereumWallet reloaded = new EthereumWallet(keystore.toString(), "migration-passphrase");
            assertEquals(plaintext.getWalletAddress(), reloaded.getWalletAddress());
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testPassphraseRotation() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-rotation-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            EthereumWallet wallet = new EthereumWallet(keystore.toString(), "original-passphrase");
            String address = wallet.getWalletAddress();

            // Rotate to new passphrase
            wallet.rotatePassphrase("rotated-passphrase");

            // Load with new passphrase succeeds and preserves identity
            EthereumWallet reloaded = new EthereumWallet(keystore.toString(), "rotated-passphrase");
            assertEquals("Identity must be preserved after rotation", address, reloaded.getWalletAddress());

            // Load with old passphrase fails
            try {
                new EthereumWallet(keystore.toString(), "original-passphrase");
                fail("Expected old passphrase to fail after rotation");
            } catch (IllegalStateException e) {
                assertTrue(e.getMessage().contains("Wrong passphrase or corrupted keystore"));
            }
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testRotatePassphraseRequiresNonEmptyValue() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-rotate-empty-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            EthereumWallet wallet = new EthereumWallet(keystore.toString(), "original");
            try {
                wallet.rotatePassphrase("");
                fail("Expected empty passphrase to be rejected");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("must not be empty"));
            }
            try {
                wallet.rotatePassphrase(null);
                fail("Expected null passphrase to be rejected");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage().contains("must not be empty"));
            }
            // Original passphrase still works after failed rotations
            new EthereumWallet(keystore.toString(), "original");
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void testEncryptedPrivateKeyNotVisibleInPlaintextFile() throws Exception {
        Path tempDir = Files.createTempDirectory("ethereum-wallet-no-plaintext-key-test");
        Path keystore = tempDir.resolve("validator-keystore.properties");
        try {
            EthereumWallet wallet = new EthereumWallet(keystore.toString(), "secure-passphrase");

            // Read raw file bytes and verify the private key hex does not appear verbatim
            String fileContents = new String(Files.readAllBytes(keystore));
            String privateKeyHex = bytesToHex(wallet.getPrivateKey().getEncoded());
            assertFalse("Private key must not appear in plaintext in the keystore file",
                fileContents.contains(privateKeyHex));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Helpers
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static Properties loadProperties(Path keystore) throws IOException {
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(keystore)) {
            props.load(in);
        }
        return props;
    }

    private static void storeProperties(Path keystore, Properties props) throws IOException {
        try (OutputStream out = Files.newOutputStream(keystore)) {
            props.store(out, "test");
        }
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
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
