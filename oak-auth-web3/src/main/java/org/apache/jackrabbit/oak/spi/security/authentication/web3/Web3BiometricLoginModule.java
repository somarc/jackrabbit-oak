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
import org.apache.jackrabbit.api.security.user.Group;
import org.apache.jackrabbit.api.security.user.User;
import org.apache.jackrabbit.api.security.user.UserManager;
import org.apache.jackrabbit.oak.api.AuthInfo;
import org.apache.jackrabbit.oak.api.Root;
import org.apache.jackrabbit.oak.commons.collections.IterableUtils;
import org.apache.jackrabbit.oak.spi.security.authentication.AbstractLoginModule;
import org.apache.jackrabbit.oak.spi.security.authentication.AuthInfoImpl;
import org.apache.jackrabbit.oak.spi.security.principal.PrincipalImpl;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Credentials;
import javax.jcr.RepositoryException;
import javax.security.auth.Subject;
import javax.security.auth.login.LoginException;
import java.security.Principal;
import java.util.Collections;
import java.util.Set;

/**
 * LoginModule implementation for Web3 biometric authentication via P-256 signatures.
 * 
 * <p>This LoginModule integrates biometric authentication into Oak's JAAS-based security system.
 * It handles {@link Web3BiometricCredentials} containing P-256 signatures from device biometrics
 * (Face ID, Touch ID, Windows Hello) via WebAuthn/FIDO2.</p>
 * 
 * <h2>Authentication Flow</h2>
 * <pre>
 * 1. {@link #login()} - Verify P-256 signature, create principal
 * 2. {@link #commit()} - Add principal to JAAS Subject
 * 3. Oak permission system uses Web3Principal for ACL evaluation
 * </pre>
 * 
 * <h2>JAAS Configuration Example</h2>
 * <pre>
 * jackrabbit.oak {
 *     org.apache.jackrabbit.oak.spi.security.authentication.web3.Web3BiometricLoginModule sufficient;
 *     org.apache.jackrabbit.oak.security.authentication.user.LoginModuleImpl required;
 * };
 * </pre>
 * 
 * <p>In this configuration:
 * <ul>
 *   <li>Web3BiometricLoginModule handles biometric credentials (wallet-based auth)</li>
 *   <li>LoginModuleImpl handles traditional username/password (fallback)</li>
 *   <li>"sufficient" means if biometric succeeds, skip password check</li>
 * </ul>
 * 
 * <h2>Configuration Options</h2>
 * <p>No configuration required for basic usage (local P-256 verification).
 * Future options may include:
 * <ul>
 *   <li>{@code ethereum.rpc.url} - For on-chain verification via EIP-7951</li>
 *   <li>{@code verifier.contract.address} - Smart contract for registration checks</li>
 *   <li>{@code verification.mode} - "local" or "on-chain"</li>
 * </ul>
 * 
 * <h2>Usage from Application Code</h2>
 * <pre>
 * Repository repo = ...;
 * 
 * // Front-end sends biometric assertion → Convert to credentials
 * Web3BiometricCredentials creds = new Web3BiometricCredentials(
 *     credentialId,
 *     signature,
 *     publicKey,
 *     challenge,
 *     walletAddress
 * );
 * 
 * // Standard JCR login - Web3BiometricLoginModule handles it
 * Session session = repo.login(creds, "default");
 * 
 * // Check authenticated principal
 * Web3Principal principal = (Web3Principal) session.getUserID(); // Returns wallet address
 * </pre>
 * 
 * <h2>Security Notes</h2>
 * <ul>
 *   <li><strong>Challenge Freshness</strong>: Application must generate unique challenges per login attempt</li>
 *   <li><strong>Public Key Registration</strong>: Consider storing registered public keys in Oak (future enhancement)</li>
 *   <li><strong>Local Verification</strong>: Currently uses JVM's EC crypto (no blockchain dependency)</li>
 *   <li><strong>Hardware Backed</strong>: Private keys never leave device secure enclaves (iOS Secure Enclave, Android Keystore)</li>
 * </ul>
 * 
 * @see Web3BiometricCredentials
 * @see Web3Principal
 * @see LocalP256Verifier
 */
public final class Web3BiometricLoginModule extends AbstractLoginModule {
    
    private static final Logger log = LoggerFactory.getLogger(Web3BiometricLoginModule.class);
    
    /**
     * P-256 signature verifier (initialized on first use).
     */
    private LocalP256Verifier verifier;
    
    /**
     * Credentials from the login attempt.
     */
    private Web3BiometricCredentials credentials;
    
    /**
     * Principal created after successful authentication.
     */
    private Web3Principal principal;
    
    /**
     * All principals (currently just the Web3Principal, but Oak expects a Set).
     */
    private Set<? extends Principal> principals;
    
    /**
     * Auth info for the authenticated user.
     */
    private AuthInfo authInfo;
    
    /**
     * Whether login succeeded.
     */
    private boolean success = false;
    
    /**
     * Whether this is a pre-verified MetaMask login (signature already verified by servlet).
     */
    private boolean preVerified = false;
    
    /**
     * User ID for pre-verified logins.
     */
    private String userId;
    
    /**
     * Factory options from OSGi ConfigAdmin.
     */
    private java.util.Map<String, Object> factoryOptions = java.util.Collections.emptyMap();
    
    /**
     * Default constructor for JAAS and OSGi LoginModuleFactory.
     */
    public Web3BiometricLoginModule() {
        // Empty constructor required by JAAS
    }
    
    /**
     * Sets factory options from the OSGi LoginModuleFactory.
     * Called by {@link Web3BiometricLoginModuleFactory} after creating the module.
     * 
     * @param options configuration options from OSGi ConfigAdmin
     */
    public void setFactoryOptions(java.util.Map<String, Object> options) {
        this.factoryOptions = options != null ? options : java.util.Collections.emptyMap();
    }
    
    /**
     * Gets a factory option value.
     * 
     * @param key the option key
     * @param defaultValue default value if not set
     * @param <T> the value type
     * @return the option value or default
     */
    @SuppressWarnings("unchecked")
    protected <T> T getFactoryOption(String key, T defaultValue) {
        Object value = factoryOptions.get(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return (T) value;
        } catch (ClassCastException e) {
            log.warn("Invalid type for factory option {}: expected {}, got {}", 
                    key, defaultValue.getClass().getSimpleName(), value.getClass().getSimpleName());
            return defaultValue;
        }
    }
    
    //--------------------------------------------------------< LoginModule >---
    
    /**
     * Returns the supported credential classes.
     * Used by Oak's credential handling to route credentials to the right LoginModule.
     * 
     * @return set containing {@link Web3BiometricCredentials} and SimpleCredentials (for servlet integration)
     */
    @Override
    @NotNull
    protected Set<Class> getSupportedCredentials() {
        Set<Class> supported = new java.util.HashSet<>();
        supported.add(Web3BiometricCredentials.class);
        supported.add(javax.jcr.SimpleCredentials.class); // Support servlet-created credentials
        return supported;
    }
    
    /**
     * Phase 1: Login - Authenticate the user via biometric signature verification.
     * 
     * <p>Steps:
     * <ol>
     *   <li>Retrieve credentials from JAAS callback handler</li>
     *   <li>Check if credentials are {@link Web3BiometricCredentials}</li>
     *   <li>Verify P-256 signature using {@link LocalP256Verifier}</li>
     *   <li>Create {@link Web3Principal} if valid</li>
     *   <li>Add credentials and user ID to shared state</li>
     * </ol>
     * 
     * @return true if authentication succeeded, false if credentials not supported
     * @throws LoginException if authentication fails (invalid signature)
     */
    @Override
    public boolean login() throws LoginException {
        log.info("🔐 Web3BiometricLoginModule.login() called!");
        
        // Step 1: Get credentials from callback handler
        Credentials creds = getCredentials();
        log.info("   Credentials type: {}", creds != null ? creds.getClass().getName() : "null");
        
        // Support both Web3BiometricCredentials and SimpleCredentials (from servlet)
        if (creds instanceof Web3BiometricCredentials) {
            this.credentials = (Web3BiometricCredentials) creds;
        } else if (creds instanceof javax.jcr.SimpleCredentials) {
            // Convert SimpleCredentials with biometric attributes to Web3BiometricCredentials
            javax.jcr.SimpleCredentials simpleCreds = (javax.jcr.SimpleCredentials) creds;
            
            // ═══════════════════════════════════════════════════════════════════
            // OPTION 1: MetaMask pre-verified credentials
            // The servlet has already verified the ECDSA signature with web3j
            // ═══════════════════════════════════════════════════════════════════
            log.info("   User ID from credentials: {}", simpleCreds.getUserID());
            Object metamaskVerified = simpleCreds.getAttribute("web3.metamask.verified");
            Object metamaskAddress = simpleCreds.getAttribute("web3.metamask.address");
            log.info("   web3.metamask.verified = {}", metamaskVerified);
            log.info("   web3.metamask.address = {}", metamaskAddress);
            
            if (Boolean.TRUE.equals(metamaskVerified)) {
                String walletAddress = (String) simpleCreds.getAttribute("web3.metamask.address");
                log.info("🦊 MetaMask pre-verified login for wallet: {}", walletAddress);
                
                // Create a "pre-verified" credential - no additional verification needed
                // The servlet has already done ECDSA signature verification!
                this.userId = walletAddress;
                this.preVerified = true;
                // Don't return yet - we need to set up the principal below!
            } else {
                // ═══════════════════════════════════════════════════════════════════
                // OPTION 2: Biometric credentials with P-256 signature
                // ═══════════════════════════════════════════════════════════════════
                Object credentialIdAttr = simpleCreds.getAttribute("web3.biometric.credentialId");
                if (credentialIdAttr == null) {
                    // Not a Web3 credential - let another LoginModule handle it
                    log.debug("SimpleCredentials without web3 attributes, skipping");
                    return false;
                }
                
                // Extract biometric data from attributes
                String credentialId = (String) credentialIdAttr;
                byte[] publicKey = (byte[]) simpleCreds.getAttribute("web3.biometric.publicKey");
                byte[] signature = (byte[]) simpleCreds.getAttribute("web3.biometric.signature");
                byte[] challenge = (byte[]) simpleCreds.getAttribute("web3.biometric.challenge");
                String walletAddress = (String) simpleCreds.getAttribute("web3.biometric.walletAddress");
                
                log.info("🔄 Converting SimpleCredentials to Web3BiometricCredentials for wallet: {}", walletAddress);
                log.info("   📐 Public key size: {} bytes, Signature size: {} bytes", 
                    publicKey != null ? publicKey.length : 0,
                    signature != null ? signature.length : 0);
                
                // Create Web3BiometricCredentials from servlet data
                // Constructor order: credentialId, signature, publicKey, challenge, walletAddress
                this.credentials = new Web3BiometricCredentials(
                    credentialId,
                    signature,   // signature comes BEFORE publicKey!
                    publicKey,   // publicKey comes AFTER signature!
                    challenge,
                    walletAddress
                );
            }
        } else {
            // Not our credentials - let another LoginModule handle it
            log.debug("Credentials are not Web3BiometricCredentials or SimpleCredentials, skipping");
            return false;
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // Handle pre-verified MetaMask credentials
        // ═══════════════════════════════════════════════════════════════════
        if (preVerified && userId != null) {
            // MetaMask signature was already verified by servlet using web3j
            // We trust the servlet's ECDSA verification
            this.principal = new Web3Principal(userId);
            this.success = true;
            
            // Add to shared state
            sharedState.put(SHARED_KEY_LOGIN_NAME, userId);
            
            log.info("🦊 MetaMask authentication succeeded for wallet: {}", userId);
            log.info("   Signature verification: Done by servlet (ECDSA/secp256k1)");
            return true;
        }
        
        // ═══════════════════════════════════════════════════════════════════
        // Handle biometric credentials - verify P-256 signature
        // ═══════════════════════════════════════════════════════════════════
        
        // Step 2: Initialize verifier (lazy)
        if (verifier == null) {
            verifier = new LocalP256Verifier();
        }
        
        // Step 3: Authenticate via P-256 signature verification
        Web3BiometricAuthentication authentication = new Web3BiometricAuthentication(verifier);
        
        try {
            success = authentication.authenticate(credentials);
        } catch (LoginException e) {
            // Authentication failed - clear state and re-throw
            clearState();
            throw e;
        }
        
        if (success) {
            // Step 4: Extract principal and user ID
            this.principal = (Web3Principal) authentication.getUserPrincipal();
            this.userId = authentication.getUserId();
            
            // Step 5: Add to shared state for other LoginModules or token generation
            sharedState.put(SHARED_KEY_CREDENTIALS, credentials);
            sharedState.put(SHARED_KEY_LOGIN_NAME, userId);
            
            log.info("🔐 Biometric authentication succeeded for wallet: {}", userId);
        }
        
        return success;
    }
    
    /**
     * Phase 2: Commit - Add principals and credentials to the JAAS Subject.
     * 
     * <p>This is called after all LoginModules in the chain have completed their {@link #login()}
     * phase. Only called if at least one LoginModule succeeded.</p>
     * 
     * <p>Steps:
     * <ol>
     *   <li>Check if this module handled authentication (success flag)</li>
     *   <li>Retrieve principals associated with the wallet address</li>
     *   <li>Create {@link AuthInfo} for Oak</li>
     *   <li>Add principals and credentials to the Subject</li>
     * </ol>
     * 
     * @return true if commit succeeded, false if this module didn't handle login
     * @throws LoginException if commit fails
     */
    @Override
    public boolean commit() throws LoginException {
        if (!success) {
            // This module didn't handle the login, nothing to commit
            clearState();
            return false;
        }
        
        try {
            // Step 1: Get principals (Oak may add additional group principals)
            principals = getPrincipals(principal);
            
            // Step 1.5: Auto-create user if doesn't exist (similar to ExternalLoginModule)
            // For MetaMask pre-verified login, credentials is null, so use userId
            String walletAddress = (credentials != null) ? credentials.getWalletAddress() : userId;
            Root root = getRoot();
            UserManager userManager = getUserManager();
            
            if (root != null && userManager != null) {
                try {
                    // Check if user exists
                    Authorizable authorizable = userManager.getAuthorizable(walletAddress);
                    
                    if (authorizable == null) {
                        // Create user with wallet address as ID
                        log.info("🆕 Creating new user for wallet: {}", walletAddress);
                        User user = userManager.createUser(
                            walletAddress,
                            null,  // No password (biometric-only auth)
                            new PrincipalImpl(walletAddress),
                            null   // Use default path (sharded under /rep:security/rep:authorizables/rep:users)
                        );
                        
                        // Add to administrators group for demo purposes
                        Group adminGroup = (Group) userManager.getAuthorizable("administrators");
                        if (adminGroup != null) {
                            adminGroup.addMember(user);
                            log.info("✅ Added {} to administrators group", walletAddress);
                        } else {
                            log.warn("⚠️ administrators group not found - user will have default permissions");
                        }
                        
                        // Save changes
                        root.commit();
                        log.info("✅ User created and persisted: {}", walletAddress);
                    } else {
                        log.debug("User already exists: {}", walletAddress);
                    }
                } catch (RepositoryException e) {
                    log.error("Failed to auto-create user: {}", e.getMessage());
                    // Don't fail login if user creation fails - they might already exist
                }
            }
            
            // Step 2: Create AuthInfo
            // For MetaMask pre-verified login, credentials is null
            authInfo = new AuthInfoImpl(
                walletAddress,
                (credentials != null) ? credentials.getAttributes() : java.util.Collections.emptyMap(),
                IterableUtils.chainedIterable(principals, subject.getPrincipals())
            );
            
            // Step 3: Update Subject with principals and credentials
            // This is what makes the authentication visible to Oak's permission system
            // For MetaMask pre-verified login, credentials is null (user already has password)
            if (credentials != null) {
                updateSubject(subject, credentials, authInfo);
            } else {
                // MetaMask: Just add principals and authInfo, no credentials needed
                setAuthInfo(authInfo, subject);
                subject.getPrincipals().addAll(principals);
            }
            
            // Step 4: Close any system sessions opened during authentication
            closeSystemSession();
            
            log.debug("Web3 biometric login committed for wallet: {}", walletAddress);
            return true;
            
        } catch (Exception e) {
            // Commit failed - call error handler and clear state
            onError();
            clearState();
            throw new LoginException("Failed to commit Web3 biometric authentication: " + e.getMessage());
        }
    }
    
    /**
     * Phase 3: Abort - Called if any LoginModule in the chain failed.
     * Clears all state accumulated during login.
     * 
     * @return true if abort succeeded
     * @throws LoginException if abort fails
     */
    @Override
    public boolean abort() throws LoginException {
        clearState();
        return true;
    }
    
    /**
     * Logout - Remove principals and credentials from the Subject.
     * Called when session is explicitly logged out.
     * 
     * @return true if logout succeeded
     * @throws LoginException if logout fails
     */
    @Override
    public boolean logout() throws LoginException {
        // Remove credentials and authInfo from Subject
        Set<Object> credsToRemove = (credentials != null || authInfo != null) 
            ? Set.of(credentials, authInfo)
            : Collections.emptySet();
        
        boolean result = logout(credsToRemove, principals);
        
        // Clear local state
        clearState();
        
        return result;
    }
    
    //------------------------------------------------------------< Private >---
    
    /**
     * Clears all local state variables.
     * Called on abort, logout, or failed commit.
     */
    @Override
    protected void clearState() {
        super.clearState();
        credentials = null;
        principal = null;
        principals = null;
        authInfo = null;
        success = false;
    }
    
    /**
     * Updates the JAAS Subject with credentials, principals, and auth info.
     * 
     * @param subject JAAS subject to update
     * @param credentials credentials to add
     * @param authInfo auth info to add (may be null)
     */
    private static void updateSubject(@NotNull Subject subject, 
                                       @NotNull Web3BiometricCredentials credentials, 
                                       @Nullable AuthInfo authInfo) {
        if (!subject.isReadOnly()) {
            // Add credentials to public credentials
            subject.getPublicCredentials().add(credentials);
            
            if (authInfo != null) {
                // Add all principals from authInfo
                subject.getPrincipals().addAll(authInfo.getPrincipals());
                // Set authInfo (removes old one if present)
                setAuthInfo(authInfo, subject);
            }
        }
    }
}

