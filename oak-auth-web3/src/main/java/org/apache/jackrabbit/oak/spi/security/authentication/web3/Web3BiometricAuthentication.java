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

import org.apache.jackrabbit.oak.spi.security.authentication.Authentication;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.Credentials;
import javax.security.auth.login.LoginException;
import java.security.Principal;

/**
 * Authentication implementation for Web3 biometric credentials.
 * 
 * <p>This class performs the core authentication logic:
 * <ol>
 *   <li>Validates that credentials are {@link Web3BiometricCredentials}</li>
 *   <li>Verifies the P-256 signature using {@link LocalP256Verifier}</li>
 *   <li>Creates a {@link Web3Principal} if signature is valid</li>
 * </ol>
 * 
 * <h2>Authentication Flow</h2>
 * <pre>
 * 1. User scans biometric (Face ID) in browser
 * 2. WebAuthn creates P-256 signature over challenge
 * 3. Front-end sends Web3BiometricCredentials to Oak
 * 4. Web3BiometricAuthentication.authenticate() called
 *    ├─ Extract signature, public key, challenge
 *    ├─ Call LocalP256Verifier.verify()
 *    └─ If valid → Create Web3Principal(walletAddress)
 * 5. LoginModule adds principal to JAAS Subject
 * 6. Oak permission system uses principal for ACLs
 * </pre>
 * 
 * <h2>Security Considerations</h2>
 * <ul>
 *   <li><strong>Replay Protection</strong>: Challenge must be fresh (generated per-login attempt)</li>
 *   <li><strong>Public Key Binding</strong>: Public key should be registered/verified on first use</li>
 *   <li><strong>Local-Only Verification</strong>: Currently uses JVM crypto (no blockchain dependency)</li>
 *   <li><strong>Future: On-Chain Verification</strong>: Can add EIP-7951 smart contract checks</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * Web3BiometricCredentials creds = ... // From front-end
 * LocalP256Verifier verifier = new LocalP256Verifier();
 * 
 * Web3BiometricAuthentication auth = 
 *     new Web3BiometricAuthentication(creds, verifier);
 * 
 * if (auth.authenticate(creds)) {
 *     Principal principal = auth.getUserPrincipal();
 *     String userId = auth.getUserId();
 *     // Authentication successful
 * }
 * </pre>
 * 
 * @see Web3BiometricCredentials
 * @see Web3BiometricLoginModule
 * @see LocalP256Verifier
 */
public final class Web3BiometricAuthentication implements Authentication {
    
    private static final Logger log = LoggerFactory.getLogger(Web3BiometricAuthentication.class);
    
    /**
     * P-256 signature verifier.
     */
    private final LocalP256Verifier verifier;
    
    /**
     * Principal created after successful authentication (null until authenticated).
     */
    private Web3Principal principal;
    
    /**
     * User ID (wallet address) after successful authentication (null until authenticated).
     */
    private String userId;
    
    /**
     * Whether authentication has been attempted and succeeded.
     */
    private boolean authenticated = false;
    
    /**
     * Creates a new Web3BiometricAuthentication with the given verifier.
     * 
     * @param verifier P-256 signature verifier
     */
    public Web3BiometricAuthentication(@NotNull LocalP256Verifier verifier) {
        this.verifier = verifier;
    }
    
    /**
     * Authenticates the given credentials by verifying the P-256 signature.
     * 
     * <p>Steps performed:</p>
     * <ol>
     *   <li>Check if credentials are {@link Web3BiometricCredentials}</li>
     *   <li>Extract signature, public key, challenge, wallet address</li>
     *   <li>Verify signature using {@link LocalP256Verifier}</li>
     *   <li>If valid, create {@link Web3Principal} and store user ID</li>
     * </ol>
     * 
     * @param credentials credentials to authenticate
     * @return true if signature is valid and authentication succeeded, false if not supported
     * @throws LoginException if authentication fails (invalid signature, malformed data)
     */
    @Override
    public boolean authenticate(@Nullable Credentials credentials) throws LoginException {
        // Step 1: Validate credentials type
        if (!(credentials instanceof Web3BiometricCredentials)) {
            log.debug("Credentials are not Web3BiometricCredentials: {}", 
                     credentials != null ? credentials.getClass().getSimpleName() : "null");
            return false;
        }
        
        Web3BiometricCredentials web3Creds = (Web3BiometricCredentials) credentials;
        
        // Step 2: Extract authentication components
        byte[] challenge = web3Creds.getChallenge();
        byte[] signature = web3Creds.getSignature();
        byte[] publicKey = web3Creds.getPublicKey();
        String walletAddress = web3Creds.getWalletAddress();
        String credentialId = web3Creds.getCredentialId();
        
        if (log.isDebugEnabled()) {
            log.debug("Authenticating Web3 biometric credentials: wallet={}, credentialId={}", 
                     walletAddress, credentialId);
        }
        
        // Step 3: Verify P-256 signature
        boolean valid;
        try {
            valid = verifier.verify(challenge, signature, publicKey);
        } catch (IllegalArgumentException e) {
            // Invalid public key format or malformed signature
            log.warn("Web3 biometric authentication failed due to malformed data: {}", e.getMessage());
            throw new LoginException("Invalid biometric signature format: " + e.getMessage());
        }
        
        if (!valid) {
            log.info("Web3 biometric authentication failed: Invalid signature for wallet {}", walletAddress);
            throw new LoginException("Invalid biometric signature");
        }
        
        // Step 4: Authentication succeeded - create principal
        this.userId = walletAddress;
        this.principal = new Web3Principal(walletAddress);
        this.authenticated = true;
        
        log.info("Web3 biometric authentication succeeded for wallet: {}", walletAddress);
        return true;
    }
    
    /**
     * Returns the user ID (wallet address) after successful authentication.
     * 
     * @return wallet address if authenticated, null otherwise
     * @throws IllegalStateException if called before {@link #authenticate(Credentials)}
     */
    @Override
    @Nullable
    public String getUserId() {
        if (!authenticated) {
            throw new IllegalStateException("getUserId() called before successful authentication");
        }
        return userId;
    }
    
    /**
     * Returns the principal (Web3Principal) after successful authentication.
     * 
     * @return Web3Principal if authenticated, null otherwise
     * @throws IllegalStateException if called before {@link #authenticate(Credentials)}
     */
    @Override
    @Nullable
    public Principal getUserPrincipal() {
        if (!authenticated) {
            throw new IllegalStateException("getUserPrincipal() called before successful authentication");
        }
        return principal;
    }
}

