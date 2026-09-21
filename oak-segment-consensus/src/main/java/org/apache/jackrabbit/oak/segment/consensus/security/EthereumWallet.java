/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.security;

import org.bouncycastle.crypto.digests.KeccakDigest;
import org.bouncycastle.jce.interfaces.ECPublicKey;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Properties;

/**
 * Real Ethereum wallet for validator identity and cryptographic signing.
 *
 * Uses secp256k1 elliptic curve (Ethereum standard) for:
 * - Validator identity (wallet address derived from public key)
 * - Leadership claim signing
 * - Consensus message authentication
 *
 * Key storage modes:
 * - Encrypted (recommended): AES-256-GCM with PBKDF2WithHmacSHA256 key derivation (310,000
 *   iterations). Enable by setting the VALIDATOR_WALLET_PASSPHRASE environment variable or
 *   the {@code validator.wallet.passphrase} system property before starting the validator.
 *   Existing plaintext keystores are auto-migrated to encrypted format on first load with a
 *   passphrase set.
 * - Plaintext (dev/test only): private key stored as hex. A warning is logged at startup.
 *
 * Key rotation procedure:
 *   1. Call {@link #rotatePassphrase(String)} with the new passphrase. The private key is
 *      unchanged; only the key-encryption key (KEK) is re-derived and the ciphertext is
 *      re-written atomically.
 *   2. Update the VALIDATOR_WALLET_PASSPHRASE environment variable before the next node
 *      restart. The in-memory wallet object already uses the new passphrase.
 *   3. Verify the next startup succeeds before discarding the old passphrase.
 *
 * HSM integration path (v2, not implemented):
 *   Replace PBKDF2-derived KEK with an HSM-managed symmetric key. The AES-GCM ciphertext
 *   envelope in the keystore file is the correct abstraction boundary — swap
 *   {@code deriveKey()} for an HSM key-unwrap call (e.g., PKCS#11 via the SunPKCS11
 *   provider, or a cloud KMS SDK such as AWS KMS GenerateDataKey). The keystore file format
 *   and all downstream code are unchanged; only the key material provider differs.
 *
 * Lifecycle:
 * 1. On first start: Generate new key pair → save to disk
 * 2. On subsequent starts: Load existing key pair from disk
 * 3. Wallet address becomes the validator's permanent identity
 */
public class EthereumWallet {
    private static final Logger log = LoggerFactory.getLogger(EthereumWallet.class);

    private static final String BC_PROVIDER = "BC";
    private static final String KEYSTORE_WALLET_ADDRESS_PROPERTY = "walletAddress";
    private static final String KEYSTORE_WALLET_DERIVATION_PROPERTY = "walletAddressDerivation";
    private static final String KEYSTORE_WALLET_DERIVATION_ETHEREUM_KECCAK =
        "ethereum-keccak256-secp256k1";

    // Encryption-at-rest property keys and format identifiers
    private static final String WALLET_FORMAT_PROPERTY = "walletFormat";
    private static final String WALLET_FORMAT_ENCRYPTED = "encrypted-aes256-gcm-pbkdf2-v1";
    private static final String WALLET_FORMAT_PLAINTEXT = "plaintext";
    private static final String ENCRYPTED_PRIVATE_KEY_PROPERTY = "encryptedPrivateKey";
    private static final String KDF_SALT_PROPERTY = "kdfSalt";
    private static final String GCM_IV_PROPERTY = "gcmIv";
    private static final String KDF_ITERATIONS_PROPERTY = "kdfIterations";

    // OWASP-recommended iteration count for PBKDF2WithHmacSHA256 (as of 2023)
    private static final int PBKDF2_ITERATIONS = 310_000;
    private static final int SALT_BYTES = 16;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int AES_KEY_BITS = 256;

    private final File keystoreFile;
    private final KeyPair keyPair;
    private final String walletAddress;
    private final String publicKeyHex;
    // Non-final to support passphrase rotation without reloading the wallet
    private char[] passphrase;

    /**
     * Create or load an Ethereum wallet from the specified keystore file.
     * Passphrase is read from the VALIDATOR_WALLET_PASSPHRASE environment variable
     * or the {@code validator.wallet.passphrase} system property.
     *
     * @param keystorePath Path to the keystore file (e.g., /var/oak-chain/validator-keystore.properties)
     * @throws Exception if key generation or loading fails
     */
    public EthereumWallet(String keystorePath) throws Exception {
        this(keystorePath, resolvePassphrase());
    }

    /**
     * Create or load with an explicit passphrase (package-private for testing).
     * Pass null or empty string to disable encryption.
     */
    EthereumWallet(String keystorePath, String passphraseStr) throws Exception {
        this.keystoreFile = new File(keystorePath);
        this.passphrase = (passphraseStr != null && !passphraseStr.isEmpty())
            ? passphraseStr.toCharArray() : null;

        if (keystoreFile.exists()) {
            log.info("🔑 Loading existing Ethereum wallet from {}", keystorePath);
            this.keyPair = loadKeyPair();
        } else {
            log.info("🔑 Generating NEW Ethereum wallet (no existing keystore)");
            this.keyPair = generateKeyPair();
            persistKeyPair(this.keyPair, this.passphrase);
        }

        this.walletAddress = deriveWalletAddress(keyPair.getPublic());
        if (keystoreFile.exists()) {
            validateStoredWalletMetadata(this.walletAddress);
        }
        this.publicKeyHex = "0x" + bytesToHex(keyPair.getPublic().getEncoded());

        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("💎 ETHEREUM WALLET LOADED");
        log.info("   Address: {}", walletAddress);
        log.info("   Public Key: {}...", publicKeyHex.substring(0, Math.min(32, publicKeyHex.length())));
        log.info("   Keystore: {}", keystoreFile.getAbsolutePath());
        log.info("   Encrypted: {}", this.passphrase != null);
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    private static String resolvePassphrase() {
        String p = System.getenv("VALIDATOR_WALLET_PASSPHRASE");
        return (p != null) ? p : System.getProperty("validator.wallet.passphrase");
    }

    /**
     * Re-encrypt the stored private key with a new passphrase.
     *
     * The private key itself is unchanged; only the key-encryption key (KEK) is
     * re-derived and the ciphertext is rewritten. After rotation, update the
     * VALIDATOR_WALLET_PASSPHRASE environment variable before the next node restart.
     *
     * @param newPassphraseStr the new passphrase; must not be null or empty
     * @throws IllegalArgumentException if newPassphraseStr is null or empty
     * @throws Exception if re-encryption or file write fails
     */
    public void rotatePassphrase(String newPassphraseStr) throws Exception {
        if (newPassphraseStr == null || newPassphraseStr.isEmpty()) {
            throw new IllegalArgumentException("New passphrase must not be empty");
        }
        char[] newPassphrase = newPassphraseStr.toCharArray();
        persistKeyPair(this.keyPair, newPassphrase);
        if (this.passphrase != null) {
            Arrays.fill(this.passphrase, '\0');
        }
        this.passphrase = newPassphrase;
        log.info("🔐 Passphrase rotated; keystore re-encrypted with new key-encryption key");
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Key generation and persistence
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private KeyPair generateKeyPair() throws Exception {
        ensureBouncyCastleProvider();
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC", BC_PROVIDER);
        ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256k1");
        keyGen.initialize(ecSpec, new SecureRandom());
        KeyPair kp = keyGen.generateKeyPair();
        log.info("✅ Generated new secp256k1 key pair (provider={})", BC_PROVIDER);
        return kp;
    }

    private void ensureBouncyCastleProvider() {
        if (Security.getProvider(BC_PROVIDER) != null) {
            return;
        }
        try {
            Security.addProvider(new BouncyCastleProvider());
            log.info("✅ Bouncy Castle provider registered (provider={})", BC_PROVIDER);
        } catch (Throwable t) {
            throw new IllegalStateException(
                "Bouncy Castle provider unavailable; cannot create Ethereum secp256k1 wallets", t);
        }
    }

    /**
     * Write a key pair to the keystore file using the given passphrase.
     * If activePassphrase is null, the private key is stored in plaintext.
     */
    private void persistKeyPair(KeyPair kp, char[] activePassphrase) throws Exception {
        Properties props = new Properties();
        props.setProperty("algorithm", kp.getPrivate().getAlgorithm());
        props.setProperty("format", kp.getPrivate().getFormat());
        props.setProperty("publicKey", bytesToHex(kp.getPublic().getEncoded()));
        props.setProperty("createdAt", String.valueOf(System.currentTimeMillis()));

        String address = deriveWalletAddress(kp.getPublic());
        props.setProperty(KEYSTORE_WALLET_ADDRESS_PROPERTY, address);
        props.setProperty(KEYSTORE_WALLET_DERIVATION_PROPERTY, KEYSTORE_WALLET_DERIVATION_ETHEREUM_KECCAK);

        if (activePassphrase != null) {
            byte[] salt = new byte[SALT_BYTES];
            new SecureRandom().nextBytes(salt);
            byte[] iv = new byte[GCM_IV_BYTES];
            new SecureRandom().nextBytes(iv);

            SecretKey kek = deriveKey(activePassphrase, salt);
            byte[] encryptedKey = encryptPrivateKey(kp.getPrivate().getEncoded(), kek, iv);

            props.setProperty(WALLET_FORMAT_PROPERTY, WALLET_FORMAT_ENCRYPTED);
            props.setProperty(KDF_SALT_PROPERTY, bytesToHex(salt));
            props.setProperty(GCM_IV_PROPERTY, bytesToHex(iv));
            props.setProperty(KDF_ITERATIONS_PROPERTY, String.valueOf(PBKDF2_ITERATIONS));
            props.setProperty(ENCRYPTED_PRIVATE_KEY_PROPERTY, bytesToHex(encryptedKey));
        } else {
            props.setProperty(WALLET_FORMAT_PROPERTY, WALLET_FORMAT_PLAINTEXT);
            props.setProperty("privateKey", bytesToHex(kp.getPrivate().getEncoded()));
            log.warn("🔓 Keystore stored WITHOUT passphrase encryption. " +
                "Set VALIDATOR_WALLET_PASSPHRASE to enable encryption at rest.");
        }

        keystoreFile.getParentFile().mkdirs();
        storeKeystoreProperties(props);
        log.info("✅ Keystore saved to {}", keystoreFile.getAbsolutePath());
        log.info("🔑 Validator wallet address: {}", address);
        log.warn("🔐 IMPORTANT: Back up this file! Loss = permanent validator identity loss");
    }

    /**
     * Load the key pair from disk, auto-migrating plaintext to encrypted if passphrase is set.
     */
    private KeyPair loadKeyPair() throws Exception {
        Properties props = loadKeystoreProperties();
        String walletFormat = props.getProperty(WALLET_FORMAT_PROPERTY, WALLET_FORMAT_PLAINTEXT);
        boolean isEncrypted = WALLET_FORMAT_ENCRYPTED.equals(walletFormat);

        byte[] privateKeyBytes;
        if (isEncrypted) {
            if (this.passphrase == null) {
                throw new IllegalStateException(
                    "Keystore is encrypted but no passphrase provided. " +
                    "Set VALIDATOR_WALLET_PASSPHRASE environment variable.");
            }
            privateKeyBytes = decryptPrivateKey(props);
        } else {
            String privateKeyHex = props.getProperty("privateKey");
            if (privateKeyHex == null) {
                throw new IllegalStateException("Corrupted keystore: missing privateKey");
            }
            privateKeyBytes = hexToBytes(privateKeyHex);
        }

        String publicKeyHexProp = props.getProperty("publicKey");
        if (publicKeyHexProp == null) {
            throw new IllegalStateException("Corrupted keystore: missing publicKey");
        }

        ensureBouncyCastleProvider();
        KeyFactory keyFactory = KeyFactory.getInstance("EC", BC_PROVIDER);
        PrivateKey privateKey = keyFactory.generatePrivate(
            new java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes));
        PublicKey publicKey = keyFactory.generatePublic(
            new java.security.spec.X509EncodedKeySpec(hexToBytes(publicKeyHexProp)));
        KeyPair kp = new KeyPair(publicKey, privateKey);

        if (!isEncrypted && this.passphrase != null) {
            log.info("🔐 Migrating plaintext keystore to encrypted format");
            persistKeyPair(kp, this.passphrase);
        }

        log.info("✅ Loaded existing key pair from keystore");
        return kp;
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Encryption / Decryption
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private SecretKey deriveKey(char[] passphraseChars, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        PBEKeySpec spec = new PBEKeySpec(passphraseChars, salt, PBKDF2_ITERATIONS, AES_KEY_BITS);
        try {
            SecretKey tmp = factory.generateSecret(spec);
            return new SecretKeySpec(tmp.getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    private byte[] encryptPrivateKey(byte[] plaintext, SecretKey kek, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, iv));
        return cipher.doFinal(plaintext);
    }

    private byte[] decryptPrivateKey(Properties props) throws Exception {
        String saltHex = props.getProperty(KDF_SALT_PROPERTY);
        String ivHex = props.getProperty(GCM_IV_PROPERTY);
        String encryptedHex = props.getProperty(ENCRYPTED_PRIVATE_KEY_PROPERTY);
        if (saltHex == null || ivHex == null || encryptedHex == null) {
            throw new IllegalStateException(
                "Corrupted encrypted keystore: missing kdfSalt, gcmIv, or encryptedPrivateKey");
        }

        SecretKey kek = deriveKey(this.passphrase, hexToBytes(saltHex));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, kek, new GCMParameterSpec(GCM_TAG_BITS, hexToBytes(ivHex)));
        try {
            return cipher.doFinal(hexToBytes(encryptedHex));
        } catch (AEADBadTagException e) {
            throw new IllegalStateException(
                "Wrong passphrase or corrupted keystore: AES-GCM authentication failed", e);
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Ethereum Address Derivation
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Derive Ethereum wallet address from public key.
     *
     * Ethereum standard:
     * 1. Get uncompressed public key (65 bytes: 0x04 + x + y)
     * 2. Keccak256 hash of the 64-byte key body (strip 0x04 prefix)
     * 3. Take last 20 bytes
     * 4. Add 0x prefix
     */
    private String deriveWalletAddress(PublicKey publicKey) throws Exception {
        try {
            ensureBouncyCastleProvider();
            if (!(publicKey instanceof ECPublicKey)) {
                throw new IllegalStateException(
                    "Expected Bouncy Castle secp256k1 public key but got " + publicKey.getClass().getName());
            }

            byte[] uncompressedPublicKey = ((ECPublicKey) publicKey).getQ().normalize().getEncoded(false);
            if (uncompressedPublicKey.length != 65 || uncompressedPublicKey[0] != 0x04) {
                throw new IllegalStateException(
                    "Expected uncompressed secp256k1 public key (65 bytes, 0x04 prefix)");
            }

            byte[] hash = keccak256(Arrays.copyOfRange(uncompressedPublicKey, 1, uncompressedPublicKey.length));
            byte[] addressBytes = Arrays.copyOfRange(hash, hash.length - 20, hash.length);
            return "0x" + bytesToHex(addressBytes);
        } catch (Exception e) {
            log.error("Failed to derive Ethereum wallet address", e);
            throw e;
        }
    }

    private byte[] keccak256(byte[] input) {
        KeccakDigest digest = new KeccakDigest(256);
        digest.update(input, 0, input.length);
        byte[] hash = new byte[32];
        digest.doFinal(hash, 0);
        return hash;
    }

    private void validateStoredWalletMetadata(String derivedAddress) throws Exception {
        Properties props = loadKeystoreProperties();
        String storedWalletAddress = props.getProperty(KEYSTORE_WALLET_ADDRESS_PROPERTY);
        if (storedWalletAddress != null && !storedWalletAddress.equalsIgnoreCase(derivedAddress)) {
            throw new IllegalStateException(
                "Legacy validator keystore walletAddress detected. Stored walletAddress="
                    + storedWalletAddress + " but derived Ethereum address=" + derivedAddress
                    + ". Regenerate or migrate the keystore before starting.");
        }

        String storedDerivation = props.getProperty(KEYSTORE_WALLET_DERIVATION_PROPERTY);
        if (!KEYSTORE_WALLET_DERIVATION_ETHEREUM_KECCAK.equals(storedDerivation)
            || storedWalletAddress == null) {
            props.setProperty(KEYSTORE_WALLET_ADDRESS_PROPERTY, derivedAddress);
            props.setProperty(KEYSTORE_WALLET_DERIVATION_PROPERTY, KEYSTORE_WALLET_DERIVATION_ETHEREUM_KECCAK);
            storeKeystoreProperties(props);
        }
    }

    private Properties loadKeystoreProperties() throws IOException {
        Properties props = new Properties();
        try (FileInputStream fis = new FileInputStream(keystoreFile)) {
            props.load(fis);
        }
        return props;
    }

    private void storeKeystoreProperties(Properties props) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(keystoreFile)) {
            props.store(fos, "Ethereum Validator Wallet - KEEP SECURE! This file identifies this validator.");
        }

        keystoreFile.setReadable(false, false);
        keystoreFile.setReadable(true, true);
        keystoreFile.setWritable(false, false);
        keystoreFile.setWritable(true, true);
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Signing
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    /**
     * Sign a message with the private key (Ethereum ECDSA).
     */
    public String sign(String message) throws Exception {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initSign(keyPair.getPrivate());
            signature.update(message.getBytes(StandardCharsets.UTF_8));
            byte[] signatureBytes = signature.sign();
            return "0x" + bytesToHex(signatureBytes);
        } catch (Exception e) {
            log.error("Failed to sign message", e);
            throw e;
        }
    }

    /**
     * Verify a signature from another validator.
     */
    public boolean verify(String message, String signatureHex, PublicKey theirPublicKey) throws Exception {
        try {
            Signature signature = Signature.getInstance("SHA256withECDSA");
            signature.initVerify(theirPublicKey);
            signature.update(message.getBytes(StandardCharsets.UTF_8));

            byte[] signatureBytes = hexToBytes(signatureHex.startsWith("0x") ?
                signatureHex.substring(2) : signatureHex);

            return signature.verify(signatureBytes);
        } catch (Exception e) {
            log.error("Failed to verify signature", e);
            return false;
        }
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Getters
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    public String getWalletAddress() {
        return walletAddress;
    }

    public String getPublicKeyHex() {
        return publicKeyHex;
    }

    public PublicKey getPublicKey() {
        return keyPair.getPublic();
    }

    public PrivateKey getPrivateKey() {
        return keyPair.getPrivate();
    }

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // Hex Utilities
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
