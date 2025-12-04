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
/**
 * Web3 biometric authentication support for Apache Jackrabbit Oak.
 * 
 * <h2>Overview</h2>
 * <p>This package provides JAAS LoginModule support for biometric authentication using P-256 (secp256r1)
 * signatures from device secure enclaves (Apple Secure Enclave, Android Keystore, Windows TPM).
 * It enables passwordless authentication via WebAuthn/FIDO2 passkeys with Ethereum wallet addresses
 * as user identifiers.</p>
 * 
 * <h2>Core Components</h2>
 * <ul>
 *   <li>{@link org.apache.jackrabbit.oak.spi.security.authentication.web3.Web3BiometricLoginModule} - 
 *       JAAS LoginModule for biometric authentication</li>
 *   <li>{@link org.apache.jackrabbit.oak.spi.security.authentication.web3.Web3BiometricCredentials} - 
 *       Credentials containing P-256 signature from WebAuthn</li>
 *   <li>{@link org.apache.jackrabbit.oak.spi.security.authentication.web3.Web3Principal} - 
 *       Principal representing Ethereum wallet address</li>
 *   <li>{@link org.apache.jackrabbit.oak.spi.security.authentication.web3.LocalP256Verifier} - 
 *       P-256 signature verifier using JVM crypto</li>
 * </ul>
 * 
 * <h2>Usage Example</h2>
 * <pre>
 * // 1. Front-end: User scans Face ID/Touch ID
 * const assertion = await navigator.credentials.get({ publicKey: {...} });
 * 
 * // 2. Send to Oak backend
 * POST /repository/login
 * {
 *   credentials: {
 *     "@class": "org.apache.jackrabbit.oak...Web3BiometricCredentials",
 *     "credentialId": "...",
 *     "signature": "...",
 *     "publicKey": "...",
 *     "challenge": "...",
 *     "walletAddress": "0x1234..."
 *   }
 * }
 * 
 * // 3. Oak authenticates via Web3BiometricLoginModule
 * Repository repo = ...;
 * Web3BiometricCredentials creds = new Web3BiometricCredentials(...);
 * Session session = repo.login(creds, "default");
 * 
 * // 4. Session has Web3Principal with wallet address
 * String userId = session.getUserID(); // "0x1234567890abcdef..."
 * </pre>
 * 
 * <h2>JAAS Configuration</h2>
 * <pre>
 * jackrabbit.oak {
 *     org.apache.jackrabbit.oak.spi.security.authentication.web3.Web3BiometricLoginModule sufficient;
 *     org.apache.jackrabbit.oak.security.authentication.user.LoginModuleImpl required;
 * };
 * </pre>
 * 
 * <h2>Security Model</h2>
 * <ul>
 *   <li><strong>Hardware-Backed Keys</strong>: Private keys never leave device secure enclaves</li>
 *   <li><strong>P-256 Signatures</strong>: Industry-standard elliptic curve (NIST P-256 / secp256r1)</li>
 *   <li><strong>Challenge-Response</strong>: Each authentication requires fresh challenge (replay protection)</li>
 *   <li><strong>Local Verification</strong>: Uses JVM's EC crypto (no blockchain dependency required)</li>
 *   <li><strong>Optional On-Chain</strong>: Can add EIP-7951 smart contract verification (future)</li>
 * </ul>
 * 
 * <h2>Compatibility</h2>
 * <p>Works with any Oak-based system:
 * <ul>
 *   <li>Adobe AEM (Experience Manager)</li>
 *   <li>Apache Sling</li>
 *   <li>Magnolia CMS</li>
 *   <li>Day CQ</li>
 *   <li>Custom Oak applications</li>
 * </ul>
 * 
 * <h2>Standards Compliance</h2>
 * <ul>
 *   <li>FIPS 186-4: P-256 elliptic curve specification</li>
 *   <li>WebAuthn Level 2: Web Authentication API</li>
 *   <li>FIDO2: Fast Identity Online 2.0</li>
 *   <li>EIP-7951: Ethereum secp256r1 precompile (optional on-chain verification)</li>
 * </ul>
 * 
 * @see <a href="https://w3c.github.io/webauthn/">WebAuthn Specification</a>
 * @see <a href="https://fidoalliance.org/fido2/">FIDO2 Specifications</a>
 * @see <a href="https://eips.ethereum.org/EIPS/eip-7951">EIP-7951: secp256r1 Precompile</a>
 */
@org.osgi.annotation.versioning.Version("1.0")
package org.apache.jackrabbit.oak.spi.security.authentication.web3;

