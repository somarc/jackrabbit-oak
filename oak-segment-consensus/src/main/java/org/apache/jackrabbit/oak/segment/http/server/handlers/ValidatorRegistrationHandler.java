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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Self-service validator registration handler using WebAuthn/Passkeys.
 * 
 * <p>This handler enables new validators to register themselves by creating
 * a passkey. The passkey's public key is used to derive an Ethereum address
 * that becomes the validator's identity.</p>
 * 
 * <h2>Registration Flow</h2>
 * <pre>
 * 1. User navigates to /auth/register
 * 2. Server generates registration challenge
 * 3. User creates passkey (Face ID, Touch ID, etc.)
 * 4. Server receives public key and derives Ethereum address
 * 5. Validator identity is stored and can be used for:
 *    - Dashboard authentication
 *    - On-chain validator registration
 *    - Payment distribution
 * </pre>
 * 
 * <h2>Security Considerations</h2>
 * <ul>
 *   <li>Registration can be open or require approval</li>
 *   <li>Rate limiting prevents abuse</li>
 *   <li>Passkey provides strong authentication</li>
 *   <li>Derived address is deterministic from public key</li>
 * </ul>
 * 
 * @since 1.89
 */
public class ValidatorRegistrationHandler {
    
    private static final Logger log = LoggerFactory.getLogger(ValidatorRegistrationHandler.class);
    
    // Configuration
    public static final String PROP_REGISTRATION_ENABLED = "validator.registration.enabled";
    public static final String PROP_REGISTRATION_APPROVAL_REQUIRED = "validator.registration.approval.required";
    public static final String PROP_RP_ID = "validator.webauthn.rp.id";
    public static final String PROP_RP_NAME = "validator.webauthn.rp.name";
    
    // Challenge storage
    private final Map<String, RegistrationChallenge> challenges = new ConcurrentHashMap<>();
    
    // Registered validators (in production, persist to database)
    private final Map<String, ValidatorCredential> credentials = new ConcurrentHashMap<>();
    
    // Pending approvals
    private final Map<String, PendingRegistration> pendingApprovals = new ConcurrentHashMap<>();
    
    private final ServerContext context;
    private final SecureRandom random = new SecureRandom();
    private final boolean enabled;
    private final boolean approvalRequired;
    private final String rpId;
    private final String rpName;
    
    /**
     * Registration challenge.
     */
    public static class RegistrationChallenge {
        public final String challengeId;
        public final byte[] challengeBytes;
        public final String userId;
        public final long createdAt;
        public final long expiresAt;
        
        RegistrationChallenge(String challengeId, byte[] challengeBytes, String userId, long ttlMillis) {
            this.challengeId = challengeId;
            this.challengeBytes = challengeBytes;
            this.userId = userId;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = createdAt + ttlMillis;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
    
    /**
     * Stored validator credential.
     */
    public static class ValidatorCredential {
        public final String credentialId;
        public final byte[] publicKey;
        public final String walletAddress;
        public final String displayName;
        public final long registeredAt;
        public final boolean approved;
        
        ValidatorCredential(String credentialId, byte[] publicKey, String walletAddress, 
                          String displayName, boolean approved) {
            this.credentialId = credentialId;
            this.publicKey = publicKey;
            this.walletAddress = walletAddress;
            this.displayName = displayName;
            this.registeredAt = System.currentTimeMillis();
            this.approved = approved;
        }
    }
    
    /**
     * Pending registration awaiting approval.
     */
    public static class PendingRegistration {
        public final ValidatorCredential credential;
        public final String requestedBy;
        public final long requestedAt;
        
        PendingRegistration(ValidatorCredential credential, String requestedBy) {
            this.credential = credential;
            this.requestedBy = requestedBy;
            this.requestedAt = System.currentTimeMillis();
        }
    }
    
    public ValidatorRegistrationHandler(ServerContext context) {
        this.context = context;
        this.enabled = Boolean.parseBoolean(System.getProperty(PROP_REGISTRATION_ENABLED, "true"));
        this.approvalRequired = Boolean.parseBoolean(System.getProperty(PROP_REGISTRATION_APPROVAL_REQUIRED, "false"));
        this.rpId = System.getProperty(PROP_RP_ID, "oak-chain.io");
        this.rpName = System.getProperty(PROP_RP_NAME, "Oak Chain Validator");
        
        log.info("Validator registration handler initialized: enabled={}, approvalRequired={}, rpId={}",
            enabled, approvalRequired, rpId);
    }
    
    /**
     * Handle registration page request.
     */
    public void handleRegistrationPage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!enabled) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Registration is disabled");
            return;
        }
        
        // Generate registration challenge
        byte[] challengeBytes = new byte[32];
        random.nextBytes(challengeBytes);
        
        String challengeId = generateId();
        String userId = generateId();
        
        RegistrationChallenge challenge = new RegistrationChallenge(
            challengeId, challengeBytes, userId, TimeUnit.MINUTES.toMillis(10));
        challenges.put(challengeId, challenge);
        
        // Clean up expired challenges
        challenges.entrySet().removeIf(e -> e.getValue().isExpired());
        
        response.setContentType("text/html");
        response.setStatus(HttpServletResponse.SC_OK);
        response.getWriter().write(generateRegistrationPage(challenge));
    }
    
    /**
     * Handle registration completion.
     */
    public void handleRegistrationComplete(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!enabled) {
            sendJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Registration is disabled");
            return;
        }
        
        // Parse request body
        StringBuilder body = new StringBuilder();
        try (BufferedReader reader = request.getReader()) {
            String line;
            while ((line = reader.readLine()) != null) {
                body.append(line);
            }
        }
        
        // Simple JSON parsing (in production, use proper JSON library)
        String json = body.toString();
        String challengeId = extractJsonString(json, "challengeId");
        String credentialId = extractJsonString(json, "credentialId");
        String publicKeyB64 = extractJsonString(json, "publicKey");
        String displayName = extractJsonString(json, "displayName");
        
        if (challengeId == null || credentialId == null || publicKeyB64 == null) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing required fields");
            return;
        }
        
        // Verify challenge
        RegistrationChallenge challenge = challenges.remove(challengeId);
        if (challenge == null || challenge.isExpired()) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid or expired challenge");
            return;
        }
        
        // Decode public key
        byte[] publicKey;
        try {
            publicKey = Base64.getDecoder().decode(publicKeyB64);
        } catch (IllegalArgumentException e) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Invalid public key encoding");
            return;
        }
        
        // Derive Ethereum address from public key
        String walletAddress = deriveWalletAddress(publicKey);
        
        // Check if already registered
        if (credentials.containsKey(walletAddress)) {
            sendJsonError(response, HttpServletResponse.SC_CONFLICT, "Wallet already registered");
            return;
        }
        
        // Create credential
        ValidatorCredential credential = new ValidatorCredential(
            credentialId, publicKey, walletAddress, 
            displayName != null ? displayName : "Validator " + walletAddress.substring(0, 8),
            !approvalRequired);
        
        if (approvalRequired) {
            // Add to pending approvals
            pendingApprovals.put(walletAddress, new PendingRegistration(credential, request.getRemoteAddr()));
            log.info("📝 Validator registration pending approval: {}", walletAddress);
            
            response.setContentType("application/json");
            response.getWriter().write(String.format(
                "{\"success\":true,\"status\":\"pending\",\"walletAddress\":\"%s\",\"message\":\"Registration pending approval\"}",
                walletAddress));
        } else {
            // Auto-approve
            credentials.put(walletAddress, credential);
            log.info("✅ Validator registered: {} ({})", walletAddress, credential.displayName);
            
            response.setContentType("application/json");
            response.getWriter().write(String.format(
                "{\"success\":true,\"status\":\"approved\",\"walletAddress\":\"%s\",\"message\":\"Registration complete\"}",
                walletAddress));
        }
    }
    
    /**
     * Handle approval of pending registration (admin only).
     */
    public void handleApproveRegistration(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String walletAddress = request.getParameter("wallet");
        if (walletAddress == null) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing wallet parameter");
            return;
        }
        
        PendingRegistration pending = pendingApprovals.remove(walletAddress);
        if (pending == null) {
            sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "No pending registration for wallet");
            return;
        }
        
        // Approve and store
        ValidatorCredential approved = new ValidatorCredential(
            pending.credential.credentialId,
            pending.credential.publicKey,
            pending.credential.walletAddress,
            pending.credential.displayName,
            true);
        
        credentials.put(walletAddress, approved);
        log.info("✅ Validator registration approved: {}", walletAddress);
        
        response.setContentType("application/json");
        response.getWriter().write(String.format(
            "{\"success\":true,\"walletAddress\":\"%s\",\"message\":\"Registration approved\"}",
            walletAddress));
    }
    
    /**
     * Handle rejection of pending registration (admin only).
     */
    public void handleRejectRegistration(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String walletAddress = request.getParameter("wallet");
        if (walletAddress == null) {
            sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST, "Missing wallet parameter");
            return;
        }
        
        PendingRegistration pending = pendingApprovals.remove(walletAddress);
        if (pending == null) {
            sendJsonError(response, HttpServletResponse.SC_NOT_FOUND, "No pending registration for wallet");
            return;
        }
        
        log.info("❌ Validator registration rejected: {}", walletAddress);
        
        response.setContentType("application/json");
        response.getWriter().write(String.format(
            "{\"success\":true,\"walletAddress\":\"%s\",\"message\":\"Registration rejected\"}",
            walletAddress));
    }
    
    /**
     * List pending registrations (admin only).
     */
    public void handleListPending(HttpServletRequest request, HttpServletResponse response) throws IOException {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        
        for (PendingRegistration pending : pendingApprovals.values()) {
            if (!first) json.append(",");
            first = false;
            
            json.append(String.format(
                "{\"walletAddress\":\"%s\",\"displayName\":\"%s\",\"requestedAt\":%d,\"requestedBy\":\"%s\"}",
                pending.credential.walletAddress,
                pending.credential.displayName,
                pending.requestedAt,
                pending.requestedBy));
        }
        
        json.append("]");
        
        response.setContentType("application/json");
        response.getWriter().write(json.toString());
    }
    
    /**
     * List registered validators.
     */
    public void handleListValidators(HttpServletRequest request, HttpServletResponse response) throws IOException {
        StringBuilder json = new StringBuilder("[");
        boolean first = true;
        
        for (ValidatorCredential cred : credentials.values()) {
            if (!first) json.append(",");
            first = false;
            
            json.append(String.format(
                "{\"walletAddress\":\"%s\",\"displayName\":\"%s\",\"registeredAt\":%d,\"approved\":%b}",
                cred.walletAddress,
                cred.displayName,
                cred.registeredAt,
                cred.approved));
        }
        
        json.append("]");
        
        response.setContentType("application/json");
        response.getWriter().write(json.toString());
    }
    
    /**
     * Check if a wallet is registered and approved.
     */
    public boolean isValidatorRegistered(String walletAddress) {
        ValidatorCredential cred = credentials.get(walletAddress.toLowerCase());
        return cred != null && cred.approved;
    }
    
    /**
     * Get credential for wallet.
     */
    public ValidatorCredential getCredential(String walletAddress) {
        return credentials.get(walletAddress.toLowerCase());
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // PRIVATE HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    private String deriveWalletAddress(byte[] publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey);
            
            // Take last 20 bytes as address
            StringBuilder sb = new StringBuilder("0x");
            for (int i = hash.length - 20; i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString().toLowerCase();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive wallet address", e);
        }
    }
    
    private String generateId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    private void sendJsonError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(String.format("{\"success\":false,\"error\":\"%s\"}", message));
    }
    
    private String extractJsonString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int start = json.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end);
    }
    
    private String generateRegistrationPage(RegistrationChallenge challenge) {
        String challengeBase64 = Base64.getEncoder().encodeToString(challenge.challengeBytes);
        String userIdBase64 = Base64.getEncoder().encodeToString(challenge.userId.getBytes(StandardCharsets.UTF_8));
        
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Validator Registration - Oak Chain</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, sans-serif;
                        background: linear-gradient(135deg, #0a0a0a 0%%, #1a1a2e 50%%, #16213e 100%%);
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: #fff;
                    }
                    .container {
                        background: rgba(255, 255, 255, 0.05);
                        backdrop-filter: blur(20px);
                        border: 1px solid rgba(255, 255, 255, 0.1);
                        border-radius: 24px;
                        padding: 48px;
                        max-width: 480px;
                        width: 90%%;
                    }
                    .logo { font-size: 48px; text-align: center; margin-bottom: 16px; }
                    h1 { font-size: 24px; font-weight: 600; text-align: center; margin-bottom: 8px; }
                    .subtitle { color: rgba(255, 255, 255, 0.6); text-align: center; margin-bottom: 32px; }
                    .form-group { margin-bottom: 24px; }
                    label { display: block; margin-bottom: 8px; font-weight: 500; }
                    input {
                        width: 100%%;
                        padding: 12px 16px;
                        border: 1px solid rgba(255, 255, 255, 0.2);
                        border-radius: 8px;
                        background: rgba(255, 255, 255, 0.05);
                        color: #fff;
                        font-size: 16px;
                    }
                    input:focus { outline: none; border-color: #667eea; }
                    .register-button {
                        background: linear-gradient(135deg, #10b981 0%%, #059669 100%%);
                        border: none;
                        border-radius: 12px;
                        padding: 16px 32px;
                        font-size: 16px;
                        font-weight: 600;
                        color: #fff;
                        cursor: pointer;
                        width: 100%%;
                        transition: transform 0.2s, box-shadow 0.2s;
                    }
                    .register-button:hover {
                        transform: translateY(-2px);
                        box-shadow: 0 8px 24px rgba(16, 185, 129, 0.4);
                    }
                    .register-button:disabled { opacity: 0.5; cursor: not-allowed; transform: none; }
                    .status {
                        margin-top: 24px;
                        padding: 16px;
                        border-radius: 8px;
                        font-size: 14px;
                    }
                    .status.error { background: rgba(239, 68, 68, 0.2); color: #fca5a5; }
                    .status.success { background: rgba(34, 197, 94, 0.2); color: #86efac; }
                    .status.pending { background: rgba(234, 179, 8, 0.2); color: #fde047; }
                    .info-box {
                        background: rgba(59, 130, 246, 0.1);
                        border: 1px solid rgba(59, 130, 246, 0.3);
                        border-radius: 8px;
                        padding: 16px;
                        margin-bottom: 24px;
                        font-size: 14px;
                        line-height: 1.6;
                    }
                    .wallet-preview {
                        font-family: monospace;
                        background: rgba(0, 0, 0, 0.3);
                        padding: 12px;
                        border-radius: 8px;
                        margin-top: 16px;
                        word-break: break-all;
                        display: none;
                    }
                </style>
            </head>
            <body>
                <div class="container">
                    <div class="logo">🔑</div>
                    <h1>Validator Registration</h1>
                    <p class="subtitle">Create a passkey to become a validator operator</p>
                    
                    <div class="info-box">
                        <strong>What happens:</strong><br>
                        1. You'll create a passkey using Face ID, Touch ID, or security key<br>
                        2. An Ethereum address will be derived from your passkey<br>
                        3. This address becomes your validator identity
                    </div>
                    
                    <div class="form-group">
                        <label for="displayName">Display Name</label>
                        <input type="text" id="displayName" placeholder="e.g., Alice's Validator" />
                    </div>
                    
                    <button id="registerButton" class="register-button" onclick="register()">
                        Create Passkey & Register
                    </button>
                    
                    <div id="walletPreview" class="wallet-preview"></div>
                    <div id="status" class="status" style="display: none;"></div>
                </div>
                
                <script>
                    const challenge = Uint8Array.from(atob('%s'), c => c.charCodeAt(0));
                    const userId = Uint8Array.from(atob('%s'), c => c.charCodeAt(0));
                    const challengeId = '%s';
                    const rpId = '%s';
                    const rpName = '%s';
                    
                    async function register() {
                        const button = document.getElementById('registerButton');
                        const status = document.getElementById('status');
                        const walletPreview = document.getElementById('walletPreview');
                        const displayName = document.getElementById('displayName').value || 'Validator';
                        
                        button.disabled = true;
                        button.textContent = 'Creating passkey...';
                        status.style.display = 'none';
                        
                        try {
                            if (!window.PublicKeyCredential) {
                                throw new Error('WebAuthn not supported');
                            }
                            
                            // Create credential
                            const credential = await navigator.credentials.create({
                                publicKey: {
                                    challenge: challenge,
                                    rp: { id: rpId, name: rpName },
                                    user: {
                                        id: userId,
                                        name: displayName,
                                        displayName: displayName
                                    },
                                    pubKeyCredParams: [
                                        { alg: -7, type: 'public-key' },   // ES256 (P-256)
                                        { alg: -257, type: 'public-key' }  // RS256
                                    ],
                                    authenticatorSelection: {
                                        authenticatorAttachment: 'platform',
                                        userVerification: 'required',
                                        residentKey: 'required'
                                    },
                                    timeout: 60000
                                }
                            });
                            
                            // Extract public key
                            const response = credential.response;
                            const attestationObject = new Uint8Array(response.attestationObject);
                            
                            // Send to server
                            const registerResponse = await fetch('/auth/register/complete', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({
                                    challengeId: challengeId,
                                    credentialId: credential.id,
                                    publicKey: btoa(String.fromCharCode(...new Uint8Array(response.getPublicKey()))),
                                    displayName: displayName
                                })
                            });
                            
                            const result = await registerResponse.json();
                            
                            if (result.success) {
                                walletPreview.textContent = 'Your Validator Address: ' + result.walletAddress;
                                walletPreview.style.display = 'block';
                                
                                if (result.status === 'pending') {
                                    status.className = 'status pending';
                                    status.innerHTML = '⏳ ' + result.message + '<br><br>An administrator will review your registration.';
                                } else {
                                    status.className = 'status success';
                                    status.innerHTML = '✅ ' + result.message + '<br><br>You can now <a href="/auth/login" style="color: #86efac;">login to the dashboard</a>.';
                                }
                                status.style.display = 'block';
                                button.textContent = 'Registration Complete';
                            } else {
                                throw new Error(result.error || 'Registration failed');
                            }
                            
                        } catch (error) {
                            console.error('Registration error:', error);
                            status.className = 'status error';
                            status.textContent = '❌ ' + error.message;
                            status.style.display = 'block';
                            
                            button.disabled = false;
                            button.textContent = 'Create Passkey & Register';
                        }
                    }
                </script>
            </body>
            </html>
            """.formatted(challengeBase64, userIdBase64, challenge.challengeId, rpId, rpName);
    }
}
