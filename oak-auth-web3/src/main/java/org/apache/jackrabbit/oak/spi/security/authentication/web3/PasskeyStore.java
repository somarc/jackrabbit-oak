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
package org.apache.jackrabbit.oak.spi.security.authentication.web3;

import org.apache.jackrabbit.api.security.user.Authorizable;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.api.Type;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.RepositoryException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/**
 * Storage service for WebAuthn passkeys (public keys) in Oak's user nodes.
 * 
 * <p>This service manages the registration and lookup of passkeys for Web3 biometric
 * authentication. Passkeys are stored as properties on the user's authorizable node
 * in Oak's security tree.</p>
 * 
 * <h2>Storage Structure</h2>
 * <pre>
 * /rep:security/rep:authorizables/rep:users/.../{walletAddress}/
 *   └── web3:passkeys/
 *       ├── {credentialId1}/
 *       │   ├── web3:publicKey (BINARY)
 *       │   ├── web3:credentialId (STRING)
 *       │   ├── web3:createdAt (LONG)
 *       │   ├── web3:lastUsedAt (LONG)
 *       │   └── web3:deviceName (STRING, optional)
 *       └── {credentialId2}/
 *           └── ...
 * </pre>
 * 
 * <h2>Security Considerations</h2>
 * <ul>
 *   <li>Public keys are stored, not private keys (safe to store)</li>
 *   <li>Credential IDs are hashed for node names (prevent enumeration)</li>
 *   <li>Access controlled by Oak's security model</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * PasskeyStore store = new PasskeyStore(root, userManager);
 * 
 * // Register a new passkey
 * store.registerPasskey(walletAddress, credentialId, publicKey, "iPhone 15");
 * 
 * // Lookup passkey for authentication
 * RegisteredPasskey passkey = store.getPasskey(walletAddress, credentialId);
 * if (passkey != null) {
 *     // Verify signature against passkey.getPublicKey()
 * }
 * </pre>
 * 
 * @see Web3BiometricLoginModule
 * @see ChallengeService
 */
public class PasskeyStore {
    
    private static final Logger log = LoggerFactory.getLogger(PasskeyStore.class);
    
    /** Node name for passkeys container */
    private static final String PASSKEYS_NODE = "web3:passkeys";
    
    /** Property names */
    private static final String PROP_PUBLIC_KEY = "web3:publicKey";
    private static final String PROP_CREDENTIAL_ID = "web3:credentialId";
    private static final String PROP_CREATED_AT = "web3:createdAt";
    private static final String PROP_LAST_USED_AT = "web3:lastUsedAt";
    private static final String PROP_DEVICE_NAME = "web3:deviceName";
    private static final String PROP_KEY_ALGORITHM = "web3:keyAlgorithm";
    
    /** Default key algorithm */
    private static final String DEFAULT_KEY_ALGORITHM = "P-256";
    
    private final Root root;
    private final UserManager userManager;
    
    /**
     * Represents a registered passkey.
     */
    public static class RegisteredPasskey {
        private final String credentialId;
        private final byte[] publicKey;
        private final String walletAddress;
        private final long createdAt;
        private final long lastUsedAt;
        private final String deviceName;
        private final String keyAlgorithm;
        
        public RegisteredPasskey(String credentialId, byte[] publicKey, String walletAddress,
                                long createdAt, long lastUsedAt, String deviceName, String keyAlgorithm) {
            this.credentialId = credentialId;
            this.publicKey = publicKey.clone();
            this.walletAddress = walletAddress;
            this.createdAt = createdAt;
            this.lastUsedAt = lastUsedAt;
            this.deviceName = deviceName;
            this.keyAlgorithm = keyAlgorithm;
        }
        
        @NotNull
        public String getCredentialId() {
            return credentialId;
        }
        
        @NotNull
        public byte[] getPublicKey() {
            return publicKey.clone();
        }
        
        @NotNull
        public String getWalletAddress() {
            return walletAddress;
        }
        
        public long getCreatedAt() {
            return createdAt;
        }
        
        public long getLastUsedAt() {
            return lastUsedAt;
        }
        
        @Nullable
        public String getDeviceName() {
            return deviceName;
        }
        
        @NotNull
        public String getKeyAlgorithm() {
            return keyAlgorithm;
        }
    }
    
    /**
     * Creates a new PasskeyStore.
     * 
     * @param root Oak root for tree operations
     * @param userManager UserManager for authorizable lookups
     */
    public PasskeyStore(@NotNull Root root, @NotNull UserManager userManager) {
        this.root = root;
        this.userManager = userManager;
    }
    
    /**
     * Registers a new passkey for a wallet address.
     * 
     * @param walletAddress Ethereum wallet address (user ID)
     * @param credentialId WebAuthn credential ID
     * @param publicKey P-256 public key bytes
     * @param deviceName Optional device name (e.g., "iPhone 15")
     * @return true if registration succeeded
     * @throws RepositoryException if storage fails
     */
    public boolean registerPasskey(@NotNull String walletAddress, 
                                   @NotNull String credentialId,
                                   @NotNull byte[] publicKey,
                                   @Nullable String deviceName) throws RepositoryException {
        return registerPasskey(walletAddress, credentialId, publicKey, deviceName, DEFAULT_KEY_ALGORITHM);
    }
    
    /**
     * Registers a new passkey with specified key algorithm.
     * 
     * @param walletAddress Ethereum wallet address (user ID)
     * @param credentialId WebAuthn credential ID
     * @param publicKey Public key bytes
     * @param deviceName Optional device name
     * @param keyAlgorithm Key algorithm (e.g., "P-256", "secp256k1")
     * @return true if registration succeeded
     * @throws RepositoryException if storage fails
     */
    public boolean registerPasskey(@NotNull String walletAddress, 
                                   @NotNull String credentialId,
                                   @NotNull byte[] publicKey,
                                   @Nullable String deviceName,
                                   @NotNull String keyAlgorithm) throws RepositoryException {
        
        // Get or create user
        Authorizable authorizable = userManager.getAuthorizable(walletAddress);
        if (authorizable == null || !authorizable.isGroup()) {
            log.warn("User not found for wallet: {}", walletAddress);
            return false;
        }
        
        User user = (User) authorizable;
        String userPath = user.getPath();
        
        // Get user tree
        Tree userTree = root.getTree(userPath);
        if (!userTree.exists()) {
            log.error("User tree not found: {}", userPath);
            return false;
        }
        
        // Get or create passkeys container
        Tree passkeysTree = userTree.getChild(PASSKEYS_NODE);
        if (!passkeysTree.exists()) {
            passkeysTree = userTree.addChild(PASSKEYS_NODE);
            log.debug("Created passkeys container for user: {}", walletAddress);
        }
        
        // Create node name from credential ID (hash to prevent enumeration)
        String nodeName = hashCredentialId(credentialId);
        
        // Check if passkey already exists
        Tree passkeyTree = passkeysTree.getChild(nodeName);
        if (passkeyTree.exists()) {
            log.warn("Passkey already registered: {} for wallet: {}", credentialId, walletAddress);
            return false;
        }
        
        // Create passkey node
        passkeyTree = passkeysTree.addChild(nodeName);
        
        long now = System.currentTimeMillis();
        
        // Store passkey properties
        passkeyTree.setProperty(PROP_CREDENTIAL_ID, credentialId, Type.STRING);
        passkeyTree.setProperty(PROP_PUBLIC_KEY, Base64.getEncoder().encodeToString(publicKey), Type.STRING);
        passkeyTree.setProperty(PROP_CREATED_AT, now, Type.LONG);
        passkeyTree.setProperty(PROP_LAST_USED_AT, now, Type.LONG);
        passkeyTree.setProperty(PROP_KEY_ALGORITHM, keyAlgorithm, Type.STRING);
        
        if (deviceName != null && !deviceName.isEmpty()) {
            passkeyTree.setProperty(PROP_DEVICE_NAME, deviceName, Type.STRING);
        }
        
        log.info("✅ Registered passkey for wallet: {} (device: {})", walletAddress, deviceName);
        return true;
    }
    
    /**
     * Gets a registered passkey by credential ID.
     * 
     * @param walletAddress Ethereum wallet address
     * @param credentialId WebAuthn credential ID
     * @return the registered passkey, or null if not found
     * @throws RepositoryException if lookup fails
     */
    @Nullable
    public RegisteredPasskey getPasskey(@NotNull String walletAddress, 
                                        @NotNull String credentialId) throws RepositoryException {
        
        Authorizable authorizable = userManager.getAuthorizable(walletAddress);
        if (authorizable == null) {
            return null;
        }
        
        String userPath = authorizable.getPath();
        Tree userTree = root.getTree(userPath);
        if (!userTree.exists()) {
            return null;
        }
        
        Tree passkeysTree = userTree.getChild(PASSKEYS_NODE);
        if (!passkeysTree.exists()) {
            return null;
        }
        
        String nodeName = hashCredentialId(credentialId);
        Tree passkeyTree = passkeysTree.getChild(nodeName);
        if (!passkeyTree.exists()) {
            return null;
        }
        
        return treeToPasskey(passkeyTree, walletAddress);
    }
    
    /**
     * Gets all registered passkeys for a wallet address.
     * 
     * @param walletAddress Ethereum wallet address
     * @return list of registered passkeys (may be empty)
     * @throws RepositoryException if lookup fails
     */
    @NotNull
    public List<RegisteredPasskey> getPasskeys(@NotNull String walletAddress) throws RepositoryException {
        
        Authorizable authorizable = userManager.getAuthorizable(walletAddress);
        if (authorizable == null) {
            return Collections.emptyList();
        }
        
        String userPath = authorizable.getPath();
        Tree userTree = root.getTree(userPath);
        if (!userTree.exists()) {
            return Collections.emptyList();
        }
        
        Tree passkeysTree = userTree.getChild(PASSKEYS_NODE);
        if (!passkeysTree.exists()) {
            return Collections.emptyList();
        }
        
        List<RegisteredPasskey> passkeys = new ArrayList<>();
        for (Tree child : passkeysTree.getChildren()) {
            RegisteredPasskey passkey = treeToPasskey(child, walletAddress);
            if (passkey != null) {
                passkeys.add(passkey);
            }
        }
        
        return passkeys;
    }
    
    /**
     * Updates the last used timestamp for a passkey.
     * 
     * @param walletAddress Ethereum wallet address
     * @param credentialId WebAuthn credential ID
     * @return true if update succeeded
     * @throws RepositoryException if update fails
     */
    public boolean updateLastUsed(@NotNull String walletAddress, 
                                  @NotNull String credentialId) throws RepositoryException {
        
        Authorizable authorizable = userManager.getAuthorizable(walletAddress);
        if (authorizable == null) {
            return false;
        }
        
        String userPath = authorizable.getPath();
        Tree userTree = root.getTree(userPath);
        if (!userTree.exists()) {
            return false;
        }
        
        Tree passkeysTree = userTree.getChild(PASSKEYS_NODE);
        if (!passkeysTree.exists()) {
            return false;
        }
        
        String nodeName = hashCredentialId(credentialId);
        Tree passkeyTree = passkeysTree.getChild(nodeName);
        if (!passkeyTree.exists()) {
            return false;
        }
        
        passkeyTree.setProperty(PROP_LAST_USED_AT, System.currentTimeMillis(), Type.LONG);
        log.debug("Updated lastUsedAt for passkey: {} (wallet: {})", credentialId, walletAddress);
        return true;
    }
    
    /**
     * Removes a registered passkey.
     * 
     * @param walletAddress Ethereum wallet address
     * @param credentialId WebAuthn credential ID
     * @return true if removal succeeded
     * @throws RepositoryException if removal fails
     */
    public boolean removePasskey(@NotNull String walletAddress, 
                                 @NotNull String credentialId) throws RepositoryException {
        
        Authorizable authorizable = userManager.getAuthorizable(walletAddress);
        if (authorizable == null) {
            return false;
        }
        
        String userPath = authorizable.getPath();
        Tree userTree = root.getTree(userPath);
        if (!userTree.exists()) {
            return false;
        }
        
        Tree passkeysTree = userTree.getChild(PASSKEYS_NODE);
        if (!passkeysTree.exists()) {
            return false;
        }
        
        String nodeName = hashCredentialId(credentialId);
        Tree passkeyTree = passkeysTree.getChild(nodeName);
        if (!passkeyTree.exists()) {
            return false;
        }
        
        passkeyTree.remove();
        log.info("🗑️  Removed passkey: {} for wallet: {}", credentialId, walletAddress);
        return true;
    }
    
    /**
     * Counts registered passkeys for a wallet address.
     * 
     * @param walletAddress Ethereum wallet address
     * @return number of registered passkeys
     * @throws RepositoryException if lookup fails
     */
    public int getPasskeyCount(@NotNull String walletAddress) throws RepositoryException {
        return getPasskeys(walletAddress).size();
    }
    
    /**
     * Checks if a passkey is registered.
     * 
     * @param walletAddress Ethereum wallet address
     * @param credentialId WebAuthn credential ID
     * @return true if passkey is registered
     * @throws RepositoryException if lookup fails
     */
    public boolean hasPasskey(@NotNull String walletAddress, 
                              @NotNull String credentialId) throws RepositoryException {
        return getPasskey(walletAddress, credentialId) != null;
    }
    
    /**
     * Converts a tree node to a RegisteredPasskey object.
     */
    @Nullable
    private RegisteredPasskey treeToPasskey(Tree tree, String walletAddress) {
        try {
            String credentialId = tree.getProperty(PROP_CREDENTIAL_ID).getValue(Type.STRING);
            String publicKeyBase64 = tree.getProperty(PROP_PUBLIC_KEY).getValue(Type.STRING);
            byte[] publicKey = Base64.getDecoder().decode(publicKeyBase64);
            long createdAt = tree.getProperty(PROP_CREATED_AT).getValue(Type.LONG);
            long lastUsedAt = tree.getProperty(PROP_LAST_USED_AT).getValue(Type.LONG);
            
            String deviceName = null;
            if (tree.hasProperty(PROP_DEVICE_NAME)) {
                deviceName = tree.getProperty(PROP_DEVICE_NAME).getValue(Type.STRING);
            }
            
            String keyAlgorithm = DEFAULT_KEY_ALGORITHM;
            if (tree.hasProperty(PROP_KEY_ALGORITHM)) {
                keyAlgorithm = tree.getProperty(PROP_KEY_ALGORITHM).getValue(Type.STRING);
            }
            
            return new RegisteredPasskey(credentialId, publicKey, walletAddress,
                                        createdAt, lastUsedAt, deviceName, keyAlgorithm);
        } catch (Exception e) {
            log.warn("Failed to parse passkey tree: {}", tree.getPath(), e);
            return null;
        }
    }
    
    /**
     * Hashes a credential ID for use as a node name.
     * This prevents enumeration attacks on credential IDs.
     */
    private String hashCredentialId(String credentialId) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(credentialId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            // Use first 16 bytes (32 hex chars) for node name
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // Fallback to Base64 encoding
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                credentialId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }
}
