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
package org.apache.jackrabbit.oak.segment.consensus.wallet;

import org.jetbrains.annotations.NotNull;

import java.security.*;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.UUID;

/**
 * A cryptographic wallet for signing transactions in the consensus network.
 * <p>
 * Uses ECDSA (secp256r1/P-256) for signing.
 * The wallet ID is derived from the public key hash.
 * 
 * Note: Using secp256r1 instead of secp256k1 (Ethereum's curve) because
 * secp256k1 requires BouncyCastle provider. For production, we'd add BC provider
 * to support Ethereum-compatible signatures.
 */
public class Wallet {

    private final UUID walletId;
    private final KeyPair keyPair;
    private final Signature signature;

    /**
     * Creates a new wallet with a generated key pair.
     *
     * @throws RuntimeException if key generation fails
     */
    public Wallet() {
        try {
            // Generate ECDSA key pair (secp256r1/P-256 - supported by default JDK)
            KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
            ECGenParameterSpec ecSpec = new ECGenParameterSpec("secp256r1");
            keyGen.initialize(ecSpec, new SecureRandom());
            this.keyPair = keyGen.generateKeyPair();
            
            // Derive wallet ID from public key
            this.walletId = deriveWalletId(keyPair.getPublic());
            
            // Initialize signature instance
            this.signature = Signature.getInstance("SHA256withECDSA");
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new RuntimeException("Failed to create wallet", e);
        }
    }

    /**
     * Creates a wallet from an existing key pair.
     *
     * @param privateKey the private key bytes (PKCS#8 format)
     * @param publicKey the public key bytes (X.509 format)
     * @throws RuntimeException if key restoration fails
     */
    public Wallet(@NotNull byte[] privateKey, @NotNull byte[] publicKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            
            // Restore private key
            PKCS8EncodedKeySpec privateKeySpec = new PKCS8EncodedKeySpec(privateKey);
            PrivateKey privKey = keyFactory.generatePrivate(privateKeySpec);
            
            // Restore public key
            X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKey);
            PublicKey pubKey = keyFactory.generatePublic(publicKeySpec);
            
            this.keyPair = new KeyPair(pubKey, privKey);
            this.walletId = deriveWalletId(pubKey);
            this.signature = Signature.getInstance("SHA256withECDSA");
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("Failed to restore wallet from keys", e);
        }
    }

    /**
     * Signs data with this wallet's private key.
     *
     * @param data the data to sign
     * @return the signature bytes
     * @throws SignatureException if signing fails
     */
    @NotNull
    public synchronized byte[] sign(@NotNull byte[] data) throws SignatureException {
        try {
            signature.initSign(keyPair.getPrivate());
            signature.update(data);
            return signature.sign();
        } catch (InvalidKeyException e) {
            throw new SignatureException("Invalid private key", e);
        }
    }

    /**
     * Verifies a signature against this wallet's public key.
     *
     * @param data the original data
     * @param signatureBytes the signature to verify
     * @return true if the signature is valid
     */
    public synchronized boolean verify(@NotNull byte[] data, @NotNull byte[] signatureBytes) {
        try {
            signature.initVerify(keyPair.getPublic());
            signature.update(data);
            return signature.verify(signatureBytes);
        } catch (InvalidKeyException | SignatureException e) {
            return false;
        }
    }

    /**
     * Verifies a signature against a given public key.
     *
     * @param data the original data
     * @param signatureBytes the signature to verify
     * @param publicKey the public key to verify against
     * @return true if the signature is valid
     */
    public static boolean verifySignature(@NotNull byte[] data, 
                                         @NotNull byte[] signatureBytes, 
                                         @NotNull byte[] publicKey) {
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("EC");
            X509EncodedKeySpec publicKeySpec = new X509EncodedKeySpec(publicKey);
            PublicKey pubKey = keyFactory.generatePublic(publicKeySpec);
            
            Signature sig = Signature.getInstance("SHA256withECDSA");
            sig.initVerify(pubKey);
            sig.update(data);
            return sig.verify(signatureBytes);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Returns this wallet's unique identifier.
     *
     * @return the wallet UUID
     */
    @NotNull
    public UUID getWalletId() {
        return walletId;
    }

    /**
     * Returns the public key bytes.
     *
     * @return public key in X.509 format
     */
    @NotNull
    public byte[] getPublicKey() {
        return keyPair.getPublic().getEncoded();
    }

    /**
     * Returns the private key bytes.
     * <p>
     * DANGER: Keep this secure! Never transmit over network.
     *
     * @return private key in PKCS#8 format
     */
    @NotNull
    public byte[] getPrivateKey() {
        return keyPair.getPrivate().getEncoded();
    }

    /**
     * Checks if this wallet owns a given content path.
     * <p>
     * A wallet owns paths under /oak-chain/content/[wallet-id]/
     *
     * @param path the JCR path to check
     * @return true if this wallet owns the path
     */
    public boolean ownsPath(@NotNull String path) {
        String expectedPrefix = "/oak-chain/content/" + walletId + "/";
        return path.startsWith(expectedPrefix);
    }

    /**
     * Returns the base content path for this wallet.
     *
     * @return /oak-chain/content/[wallet-id]
     */
    @NotNull
    public String getBasePath() {
        return "/oak-chain/content/" + walletId;
    }

    /**
     * Derives a deterministic UUID from a public key.
     * <p>
     * Uses SHA-256 hash of the public key bytes.
     *
     * @param publicKey the public key
     * @return a UUID derived from the public key
     */
    private static UUID deriveWalletId(PublicKey publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey.getEncoded());
            
            // Use first 16 bytes of hash to create UUID
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xff);
            }
            
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    @Override
    public String toString() {
        return "Wallet{" +
                "id=" + walletId +
                ", basePath=" + getBasePath() +
                '}';
    }
}

