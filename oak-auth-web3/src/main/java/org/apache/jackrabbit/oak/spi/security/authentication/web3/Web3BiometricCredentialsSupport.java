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

import org.apache.jackrabbit.oak.spi.security.authentication.credentials.CredentialsSupport;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.jcr.Credentials;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Implementation of {@link CredentialsSupport} for {@link Web3BiometricCredentials}.
 * 
 * <p>This class teaches Oak's authentication system how to extract information from
 * Web3 biometric credentials, similar to how {@code SimpleCredentialsSupport} handles
 * {@code SimpleCredentials}.</p>
 * 
 * <p>Used by {@link Web3BiometricLoginModule} and Oak's credential handling infrastructure.</p>
 * 
 * <h2>Usage</h2>
 * <pre>
 * CredentialsSupport support = Web3BiometricCredentialsSupport.getInstance();
 * 
 * // Check if credentials are supported
 * if (support.getCredentialClasses().contains(credentials.getClass())) {
 *     String userId = support.getUserId(credentials);
 *     Map&lt;String, ?&gt; attributes = support.getAttributes(credentials);
 * }
 * </pre>
 * 
 * @see Web3BiometricCredentials
 * @see org.apache.jackrabbit.oak.spi.security.authentication.credentials.SimpleCredentialsSupport
 */
public final class Web3BiometricCredentialsSupport implements CredentialsSupport {
    
    /**
     * Singleton instance (stateless, so safe to share).
     */
    private static final Web3BiometricCredentialsSupport INSTANCE = 
        new Web3BiometricCredentialsSupport();
    
    /**
     * Private constructor for singleton pattern.
     */
    private Web3BiometricCredentialsSupport() {}
    
    /**
     * Returns the singleton instance.
     * 
     * @return shared instance of Web3BiometricCredentialsSupport
     */
    @NotNull
    public static CredentialsSupport getInstance() {
        return INSTANCE;
    }
    
    /**
     * Returns the set of credential classes supported by this implementation.
     * 
     * @return singleton set containing {@link Web3BiometricCredentials}
     */
    @Override
    @NotNull
    public Set<Class> getCredentialClasses() {
        return Collections.singleton(Web3BiometricCredentials.class);
    }
    
    /**
     * Extracts the user ID from the credentials.
     * For Web3 credentials, this is the Ethereum wallet address.
     * 
     * @param credentials credentials to extract user ID from
     * @return wallet address if credentials are Web3BiometricCredentials, null otherwise
     */
    @Override
    @Nullable
    public String getUserId(@NotNull Credentials credentials) {
        if (credentials instanceof Web3BiometricCredentials) {
            return ((Web3BiometricCredentials) credentials).getWalletAddress();
        }
        return null;
    }
    
    /**
     * Returns the attributes stored in the credentials.
     * 
     * <p>Web3BiometricCredentials extends {@link org.apache.jackrabbit.oak.spi.security.authentication.credentials.AbstractCredentials},
     * which provides an attribute storage mechanism. This method returns those attributes.</p>
     * 
     * @param credentials credentials to extract attributes from
     * @return attributes map if Web3BiometricCredentials, empty map otherwise
     */
    @Override
    @NotNull
    public Map<String, ?> getAttributes(@NotNull Credentials credentials) {
        if (credentials instanceof Web3BiometricCredentials) {
            return ((Web3BiometricCredentials) credentials).getAttributes();
        }
        return Collections.emptyMap();
    }
    
    /**
     * Sets attributes on the credentials.
     * 
     * <p>Allows external systems to attach metadata to the credentials during
     * the authentication flow (e.g., token generation, session data).</p>
     * 
     * @param credentials credentials to set attributes on
     * @param attributes attributes to set
     * @return true if attributes were set successfully, false if not supported
     */
    @Override
    public boolean setAttributes(@NotNull Credentials credentials, @NotNull Map<String, ?> attributes) {
        if (credentials instanceof Web3BiometricCredentials) {
            // Cast is safe: AbstractCredentials.setAttributes accepts Map<String, Object>
            // and we're only putting values from Map<String, ?> which are compatible
            @SuppressWarnings("unchecked")
            Map<String, Object> objectMap = (Map<String, Object>) attributes;
            ((Web3BiometricCredentials) credentials).setAttributes(objectMap);
            return true;
        }
        return false;
    }
}

