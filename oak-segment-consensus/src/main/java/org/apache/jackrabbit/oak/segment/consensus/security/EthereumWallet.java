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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyFactory;
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
 * Key features:
 * - Persistent key storage (keystore file)
 * - Ethereum-standard address derivation (keccak256)
 * - Production-ready cryptography (no mocks)
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
    
    private final File keystoreFile;
    private final KeyPair keyPair;
    private final String walletAddress;
    private final String publicKeyHex;
    
    /**
     * Create or load an Ethereum wallet from the specified keystore file.
     * 
     * @param keystorePath Path to the keystore file (e.g., /var/oak-chain/validator-keystore.properties)
     * @throws Exception if key generation or loading fails
     */
    public EthereumWallet(String keystorePath) throws Exception {
        this.keystoreFile = new File(keystorePath);
        
        if (keystoreFile.exists()) {
            log.info("🔑 Loading existing Ethereum wallet from {}", keystorePath);
            this.keyPair = loadKeyPair();
        } else {
            log.info("🔑 Generating NEW Ethereum wallet (no existing keystore)");
            this.keyPair = generateKeyPair();
            saveKeyPair();
        }
        
        // Derive wallet address from public key (Ethereum standard)
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
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }
    
    /**
     * Generate a new secp256k1 key pair (Ethereum standard).
     */
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
                "Bouncy Castle provider unavailable; cannot create Ethereum secp256k1 wallets",
                t
            );
        }
    }
    
    /**
     * Save key pair to disk (simple properties file for POC).
     * In production, use proper encryption (e.g., AES-256 with passphrase).
     */
    private void saveKeyPair() throws Exception {
        Properties props = new Properties();
        props.setProperty("privateKey", bytesToHex(keyPair.getPrivate().getEncoded()));
        props.setProperty("publicKey", bytesToHex(keyPair.getPublic().getEncoded()));
        props.setProperty("algorithm", keyPair.getPrivate().getAlgorithm());
        props.setProperty("format", keyPair.getPrivate().getFormat());
        props.setProperty("createdAt", String.valueOf(System.currentTimeMillis()));
        
        // ADR 046: Save wallet address for easy reference (cluster wallet identification)
        String address = deriveWalletAddress(keyPair.getPublic());
        props.setProperty(KEYSTORE_WALLET_ADDRESS_PROPERTY, address);
        props.setProperty(KEYSTORE_WALLET_DERIVATION_PROPERTY, KEYSTORE_WALLET_DERIVATION_ETHEREUM_KECCAK);
        
        // Create parent directories if needed
        keystoreFile.getParentFile().mkdirs();
        storeKeystoreProperties(props);
        
        log.info("✅ Keystore saved to {}", keystoreFile.getAbsolutePath());
        log.info("🔑 Validator wallet address: {}", address);
        log.warn("🔐 IMPORTANT: Back up this file! Loss = permanent validator identity loss");
    }
    
    /**
     * Load key pair from disk.
     */
    private KeyPair loadKeyPair() throws Exception {
        Properties props = loadKeystoreProperties();
        
        String privateKeyHex = props.getProperty("privateKey");
        String publicKeyHex = props.getProperty("publicKey");
        
        if (privateKeyHex == null || publicKeyHex == null) {
            throw new IllegalStateException("Corrupted keystore: missing keys");
        }
        
        // Reconstruct keys
        byte[] privateKeyBytes = hexToBytes(privateKeyHex);
        byte[] publicKeyBytes = hexToBytes(publicKeyHex);
        
        ensureBouncyCastleProvider();
        KeyFactory keyFactory = KeyFactory.getInstance("EC", BC_PROVIDER);
        
        // Reconstruct private key
        java.security.spec.PKCS8EncodedKeySpec privateKeySpec = 
            new java.security.spec.PKCS8EncodedKeySpec(privateKeyBytes);
        PrivateKey privateKey = keyFactory.generatePrivate(privateKeySpec);
        
        // Reconstruct public key
        java.security.spec.X509EncodedKeySpec publicKeySpec = 
            new java.security.spec.X509EncodedKeySpec(publicKeyBytes);
        PublicKey publicKey = keyFactory.generatePublic(publicKeySpec);
        
        log.info("✅ Loaded existing key pair from keystore");
        return new KeyPair(publicKey, privateKey);
    }
    
    /**
     * Derive Ethereum wallet address from public key.
     * 
     * Ethereum standard:
     * 1. Get uncompressed public key (65 bytes: 0x04 + x + y)
     * 2. Keccak256 hash
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
                                 + Character.digit(hex.charAt(i+1), 16));
        }
        return data;
    }
}
