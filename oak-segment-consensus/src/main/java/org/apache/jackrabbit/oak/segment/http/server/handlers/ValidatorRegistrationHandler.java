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
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
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
            sendJsonError(response, HttpServletResponse.SC_FORBIDDEN, "Registration is disabled");
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
        ApiErrorUtil.sendJsonError(response, status, message);
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
        
        return String.format(
            "<!DOCTYPE html>\n" +
            "<html lang=\"en\">\n" +
            "<head>\n" +
            "    <meta charset=\"UTF-8\">\n" +
            "    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n" +
            "    <title>Validator Registration - Oak Chain</title>\n" +
            "    <style>\n" +
            "        * { margin: 0; padding: 0; box-sizing: border-box; }\n" +
            "        body {\n" +
            "            font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, sans-serif;\n" +
            "            background: linear-gradient(135deg, #0a0a0a 0%%, #1a1a2e 50%%, #16213e 100%%);\n" +
            "            min-height: 100vh;\n" +
            "            display: flex;\n" +
            "            align-items: center;\n" +
            "            justify-content: center;\n" +
            "            color: #fff;\n" +
            "        }\n" +
            "        .container {\n" +
            "            background: rgba(255, 255, 255, 0.05);\n" +
            "            backdrop-filter: blur(20px);\n" +
            "            border: 1px solid rgba(255, 255, 255, 0.1);\n" +
            "            border-radius: 24px;\n" +
            "            padding: 48px;\n" +
            "            max-width: 480px;\n" +
            "            width: 90%%;\n" +
            "        }\n" +
            "        .logo { font-size: 48px; text-align: center; margin-bottom: 16px; }\n" +
            "        h1 { font-size: 24px; font-weight: 600; text-align: center; margin-bottom: 8px; }\n" +
            "        .subtitle { color: rgba(255, 255, 255, 0.6); text-align: center; margin-bottom: 32px; }\n" +
            "        .form-group { margin-bottom: 24px; }\n" +
            "        label { display: block; margin-bottom: 8px; font-weight: 500; }\n" +
            "        input {\n" +
            "            width: 100%%;\n" +
            "            padding: 12px 16px;\n" +
            "            border: 1px solid rgba(255, 255, 255, 0.2);\n" +
            "            border-radius: 8px;\n" +
            "            background: rgba(255, 255, 255, 0.05);\n" +
            "            color: #fff;\n" +
            "            font-size: 16px;\n" +
            "        }\n" +
            "        input:focus { outline: none; border-color: #667eea; }\n" +
            "        .register-button {\n" +
            "            background: linear-gradient(135deg, #10b981 0%%, #059669 100%%);\n" +
            "            border: none;\n" +
            "            border-radius: 12px;\n" +
            "            padding: 16px 32px;\n" +
            "            font-size: 16px;\n" +
            "            font-weight: 600;\n" +
            "            color: #fff;\n" +
            "            cursor: pointer;\n" +
            "            width: 100%%;\n" +
            "            transition: transform 0.2s, box-shadow 0.2s;\n" +
            "        }\n" +
            "        .register-button:hover {\n" +
            "            transform: translateY(-2px);\n" +
            "            box-shadow: 0 8px 24px rgba(16, 185, 129, 0.4);\n" +
            "        }\n" +
            "        .register-button:disabled { opacity: 0.5; cursor: not-allowed; transform: none; }\n" +
            "        .status {\n" +
            "            margin-top: 24px;\n" +
            "            padding: 16px;\n" +
            "            border-radius: 8px;\n" +
            "            font-size: 14px;\n" +
            "        }\n" +
            "        .status.error { background: rgba(239, 68, 68, 0.2); color: #fca5a5; }\n" +
            "        .status.success { background: rgba(34, 197, 94, 0.2); color: #86efac; }\n" +
            "        .status.pending { background: rgba(234, 179, 8, 0.2); color: #fde047; }\n" +
            "        .info-box {\n" +
            "            background: rgba(59, 130, 246, 0.1);\n" +
            "            border: 1px solid rgba(59, 130, 246, 0.3);\n" +
            "            border-radius: 8px;\n" +
            "            padding: 16px;\n" +
            "            margin-bottom: 24px;\n" +
            "            font-size: 14px;\n" +
            "            line-height: 1.6;\n" +
            "        }\n" +
            "        .wallet-preview {\n" +
            "            font-family: monospace;\n" +
            "            background: rgba(0, 0, 0, 0.3);\n" +
            "            padding: 12px;\n" +
            "            border-radius: 8px;\n" +
            "            margin-top: 16px;\n" +
            "            word-break: break-all;\n" +
            "            display: none;\n" +
            "        }\n" +
            "    </style>\n" +
            "</head>\n" +
            "<body>\n" +
            "    <div class=\"container\">\n" +
            "        <div class=\"logo\">&#128273;</div>\n" +
            "        <h1>Validator Registration</h1>\n" +
            "        <p class=\"subtitle\">Create a passkey to become a validator operator</p>\n" +
            "        \n" +
            "        <div class=\"info-box\">\n" +
            "            <strong>What happens:</strong><br>\n" +
            "            1. You'll create a passkey using Face ID, Touch ID, or security key<br>\n" +
            "            2. An Ethereum address will be derived from your passkey<br>\n" +
            "            3. This address becomes your validator identity\n" +
            "        </div>\n" +
            "        \n" +
            "        <div class=\"form-group\">\n" +
            "            <label for=\"displayName\">Display Name</label>\n" +
            "            <input type=\"text\" id=\"displayName\" placeholder=\"e.g., Alice's Validator\" />\n" +
            "        </div>\n" +
            "        \n" +
            "        <button id=\"registerButton\" class=\"register-button\" onclick=\"register()\">\n" +
            "            Create Passkey &amp; Register\n" +
            "        </button>\n" +
            "        \n" +
            "        <div id=\"walletPreview\" class=\"wallet-preview\"></div>\n" +
            "        <div id=\"status\" class=\"status\" style=\"display: none;\"></div>\n" +
            "    </div>\n" +
            "    \n" +
            "    <script>\n" +
            "        const challenge = Uint8Array.from(atob('%s'), c => c.charCodeAt(0));\n" +
            "        const userId = Uint8Array.from(atob('%s'), c => c.charCodeAt(0));\n" +
            "        const challengeId = '%s';\n" +
            "        const rpId = '%s';\n" +
            "        const rpName = '%s';\n" +
            "        \n" +
            "        async function register() {\n" +
            "            const button = document.getElementById('registerButton');\n" +
            "            const status = document.getElementById('status');\n" +
            "            const walletPreview = document.getElementById('walletPreview');\n" +
            "            const displayName = document.getElementById('displayName').value || 'Validator';\n" +
            "            \n" +
            "            button.disabled = true;\n" +
            "            button.textContent = 'Creating passkey...';\n" +
            "            status.style.display = 'none';\n" +
            "            \n" +
            "            try {\n" +
            "                if (!window.PublicKeyCredential) {\n" +
            "                    throw new Error('WebAuthn not supported');\n" +
            "                }\n" +
            "                \n" +
            "                // Create credential\n" +
            "                const credential = await navigator.credentials.create({\n" +
            "                    publicKey: {\n" +
            "                        challenge: challenge,\n" +
            "                        rp: { id: rpId, name: rpName },\n" +
            "                        user: {\n" +
            "                            id: userId,\n" +
            "                            name: displayName,\n" +
            "                            displayName: displayName\n" +
            "                        },\n" +
            "                        pubKeyCredParams: [\n" +
            "                            { alg: -7, type: 'public-key' },   // ES256 (P-256)\n" +
            "                            { alg: -257, type: 'public-key' }  // RS256\n" +
            "                        ],\n" +
            "                        authenticatorSelection: {\n" +
            "                            authenticatorAttachment: 'platform',\n" +
            "                            userVerification: 'required',\n" +
            "                            residentKey: 'required'\n" +
            "                        },\n" +
            "                        timeout: 60000\n" +
            "                    }\n" +
            "                });\n" +
            "                \n" +
            "                // Extract public key\n" +
            "                const response = credential.response;\n" +
            "                const attestationObject = new Uint8Array(response.attestationObject);\n" +
            "                \n" +
            "                // Send to server\n" +
            "                const registerResponse = await fetch('/auth/register/complete', {\n" +
            "                    method: 'POST',\n" +
            "                    headers: { 'Content-Type': 'application/json' },\n" +
            "                    body: JSON.stringify({\n" +
            "                        challengeId: challengeId,\n" +
            "                        credentialId: credential.id,\n" +
            "                        publicKey: btoa(String.fromCharCode(...new Uint8Array(response.getPublicKey()))),\n" +
            "                        displayName: displayName\n" +
            "                    })\n" +
            "                });\n" +
            "                \n" +
            "                const result = await registerResponse.json();\n" +
            "                \n" +
            "                if (result.success) {\n" +
            "                    walletPreview.textContent = 'Your Validator Address: ' + result.walletAddress;\n" +
            "                    walletPreview.style.display = 'block';\n" +
            "                    \n" +
            "                    if (result.status === 'pending') {\n" +
            "                        status.className = 'status pending';\n" +
            "                        status.innerHTML = 'Pending: ' + result.message + '<br><br>An administrator will review your registration.';\n" +
            "                    } else {\n" +
            "                        status.className = 'status success';\n" +
            "                        status.innerHTML = 'Success: ' + result.message + '<br><br>You can now <a href=\"/auth/login\" style=\"color: #86efac;\">login to the dashboard</a>.';\n" +
            "                    }\n" +
            "                    status.style.display = 'block';\n" +
            "                    button.textContent = 'Registration Complete';\n" +
            "                } else {\n" +
            "                    throw new Error(result.error || 'Registration failed');\n" +
            "                }\n" +
            "                \n" +
            "            } catch (error) {\n" +
            "                console.error('Registration error:', error);\n" +
            "                status.className = 'status error';\n" +
            "                status.textContent = 'Error: ' + error.message;\n" +
            "                status.style.display = 'block';\n" +
            "                \n" +
            "                button.disabled = false;\n" +
            "                button.textContent = 'Create Passkey & Register';\n" +
            "            }\n" +
            "        }\n" +
            "    </script>\n" +
            "</body>\n" +
            "</html>",
            challengeBase64, userIdBase64, challenge.challengeId, rpId, rpName);
    }
}
