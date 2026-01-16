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
package org.apache.jackrabbit.oak.segment.http.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Authentication handler for validator dashboards using WebAuthn/Passkeys.
 * 
 * <p>Integrates with oak-auth-web3 to provide passkey-based authentication
 * for validator operators. This ensures only authorized operators can access
 * validator dashboards and administrative endpoints.</p>
 * 
 * <h2>Authentication Flow</h2>
 * <pre>
 * 1. User navigates to /dashboard or /explorer
 * 2. If no valid session, redirect to /auth/login
 * 3. Login page initiates WebAuthn ceremony
 * 4. User authenticates with passkey (Face ID, Touch ID, etc.)
 * 5. Server verifies P-256 signature
 * 6. If valid, create session and redirect to original URL
 * </pre>
 * 
 * <h2>Validator Identity</h2>
 * <p>The Ethereum address derived from the passkey's public key becomes
 * the validator's identity. This address can be used for:</p>
 * <ul>
 *   <li>Dashboard access control</li>
 *   <li>Validator registration on-chain</li>
 *   <li>Payment distribution</li>
 * </ul>
 * 
 * <h2>Configuration</h2>
 * <ul>
 *   <li>{@code dashboard.auth.enabled} - Enable authentication (default: true in production)</li>
 *   <li>{@code dashboard.auth.session.ttl} - Session TTL in hours (default: 24)</li>
 *   <li>{@code dashboard.auth.allowed.wallets} - Comma-separated list of allowed wallet addresses</li>
 * </ul>
 * 
 * @since 1.89
 */
public class ValidatorAuthHandler {
    
    private static final Logger log = LoggerFactory.getLogger(ValidatorAuthHandler.class);
    
    // Configuration keys
    public static final String PROP_AUTH_ENABLED = "dashboard.auth.enabled";
    public static final String PROP_SESSION_TTL = "dashboard.auth.session.ttl";
    public static final String PROP_ALLOWED_WALLETS = "dashboard.auth.allowed.wallets";
    
    // Cookie/session names
    private static final String SESSION_COOKIE = "oak_validator_session";
    private static final String CHALLENGE_COOKIE = "oak_auth_challenge";
    
    // Defaults
    private static final int DEFAULT_SESSION_TTL_HOURS = 24;
    private static final int CHALLENGE_TTL_MINUTES = 5;
    
    // Session storage (in production, use Redis or similar)
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    
    // Allowed wallet addresses (null = allow all authenticated users)
    private final String[] allowedWallets;
    
    private final boolean enabled;
    private final int sessionTtlHours;
    private final SecureRandom random = new SecureRandom();
    
    /**
     * Represents an authenticated session.
     */
    public static class Session {
        public final String sessionId;
        public final String walletAddress;
        public final long createdAt;
        public final long expiresAt;
        
        Session(String sessionId, String walletAddress, long ttlMillis) {
            this.sessionId = sessionId;
            this.walletAddress = walletAddress;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = createdAt + ttlMillis;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
    
    /**
     * Represents an authentication challenge.
     */
    public static class Challenge {
        public final String challengeId;
        public final byte[] challengeBytes;
        public final long createdAt;
        public final long expiresAt;
        
        Challenge(String challengeId, byte[] challengeBytes, long ttlMillis) {
            this.challengeId = challengeId;
            this.challengeBytes = challengeBytes;
            this.createdAt = System.currentTimeMillis();
            this.expiresAt = createdAt + ttlMillis;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
    
    /**
     * Create auth handler from system properties.
     */
    public ValidatorAuthHandler() {
        this.enabled = Boolean.parseBoolean(System.getProperty(PROP_AUTH_ENABLED, "true"));
        this.sessionTtlHours = Integer.getInteger(PROP_SESSION_TTL, DEFAULT_SESSION_TTL_HOURS);
        
        String walletsStr = System.getProperty(PROP_ALLOWED_WALLETS);
        if (walletsStr != null && !walletsStr.isEmpty()) {
            this.allowedWallets = walletsStr.toLowerCase().split(",");
        } else {
            this.allowedWallets = null;
        }
        
        log.info("Validator auth handler initialized: enabled={}, sessionTtl={}h, allowedWallets={}",
            enabled, sessionTtlHours, allowedWallets != null ? allowedWallets.length : "all");
    }
    
    /**
     * Check if authentication is required for the given path.
     */
    public boolean requiresAuth(String path) {
        if (!enabled) {
            return false;
        }
        
        // Protected paths
        return path.equals("/") ||
               path.equals("/dashboard") ||
               path.startsWith("/explorer") ||
               path.startsWith("/api-browser") ||
               path.startsWith("/chat") ||
               path.startsWith("/v1/admin") ||
               path.startsWith("/v1/gc/propose");
    }
    
    /**
     * Check if request is authenticated.
     * 
     * @return Session if authenticated, null otherwise
     */
    public Session getSession(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        
        for (Cookie cookie : cookies) {
            if (SESSION_COOKIE.equals(cookie.getName())) {
                String sessionId = cookie.getValue();
                Session session = sessions.get(sessionId);
                
                if (session != null && !session.isExpired()) {
                    return session;
                } else if (session != null) {
                    // Clean up expired session
                    sessions.remove(sessionId);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Check if request is authenticated and handle redirect if not.
     * 
     * @return true if authenticated, false if redirect sent
     */
    public boolean checkAuth(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (!enabled) {
            return true;
        }
        
        String path = request.getRequestURI();
        
        // Skip auth for public endpoints
        if (!requiresAuth(path)) {
            return true;
        }
        
        // Skip auth for auth endpoints themselves
        if (path.startsWith("/auth/")) {
            return true;
        }
        
        Session session = getSession(request);
        if (session != null) {
            // Check if wallet is allowed
            if (isWalletAllowed(session.walletAddress)) {
                return true;
            } else {
                log.warn("Access denied for wallet: {}", session.walletAddress);
                sendForbidden(response, "Wallet not authorized");
                return false;
            }
        }
        
        // Not authenticated - redirect to login
        String returnUrl = request.getRequestURI();
        if (request.getQueryString() != null) {
            returnUrl += "?" + request.getQueryString();
        }
        
        response.sendRedirect("/auth/login?return=" + 
            Base64.getUrlEncoder().encodeToString(returnUrl.getBytes(StandardCharsets.UTF_8)));
        return false;
    }
    
    /**
     * Generate a new authentication challenge.
     */
    public Challenge createChallenge() {
        byte[] challengeBytes = new byte[32];
        random.nextBytes(challengeBytes);
        
        String challengeId = generateId();
        long ttl = TimeUnit.MINUTES.toMillis(CHALLENGE_TTL_MINUTES);
        
        Challenge challenge = new Challenge(challengeId, challengeBytes, ttl);
        challenges.put(challengeId, challenge);
        
        // Clean up expired challenges
        challenges.entrySet().removeIf(e -> e.getValue().isExpired());
        
        return challenge;
    }
    
    /**
     * Verify challenge and create session.
     * 
     * @param challengeId The challenge ID
     * @param signature The P-256 signature
     * @param publicKey The public key bytes
     * @param walletAddress The derived wallet address
     * @return Session if verification succeeds, null otherwise
     */
    public Session verifyAndCreateSession(String challengeId, byte[] signature, 
                                          byte[] publicKey, String walletAddress) {
        Challenge challenge = challenges.remove(challengeId);
        
        if (challenge == null) {
            log.warn("Challenge not found: {}", challengeId);
            return null;
        }
        
        if (challenge.isExpired()) {
            log.warn("Challenge expired: {}", challengeId);
            return null;
        }
        
        // Verify signature using LocalP256Verifier
        try {
            // Import verifier dynamically to avoid hard dependency
            Class<?> verifierClass = Class.forName(
                "org.apache.jackrabbit.oak.spi.security.authentication.web3.LocalP256Verifier");
            Object verifier = verifierClass.getDeclaredConstructor().newInstance();
            
            java.lang.reflect.Method verifyMethod = verifierClass.getMethod(
                "verify", byte[].class, byte[].class, byte[].class);
            
            boolean valid = (boolean) verifyMethod.invoke(verifier, 
                challenge.challengeBytes, signature, publicKey);
            
            if (!valid) {
                log.warn("Invalid signature for wallet: {}", walletAddress);
                return null;
            }
        } catch (Exception e) {
            log.error("Failed to verify signature", e);
            return null;
        }
        
        // Verify wallet address matches public key
        String derivedAddress = deriveWalletAddress(publicKey);
        if (!derivedAddress.equalsIgnoreCase(walletAddress)) {
            log.warn("Wallet address mismatch: expected={}, got={}", derivedAddress, walletAddress);
            return null;
        }
        
        // Check if wallet is allowed
        if (!isWalletAllowed(walletAddress)) {
            log.warn("Wallet not in allowed list: {}", walletAddress);
            return null;
        }
        
        // Create session
        String sessionId = generateId();
        long ttl = TimeUnit.HOURS.toMillis(sessionTtlHours);
        Session session = new Session(sessionId, walletAddress, ttl);
        sessions.put(sessionId, session);
        
        log.info("✅ Session created for wallet: {}", walletAddress);
        return session;
    }
    
    /**
     * Set session cookie on response.
     */
    public void setSessionCookie(HttpServletResponse response, Session session) {
        Cookie cookie = new Cookie(SESSION_COOKIE, session.sessionId);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);  // HTTPS only
        cookie.setMaxAge((int) TimeUnit.HOURS.toSeconds(sessionTtlHours));
        response.addCookie(cookie);
    }
    
    /**
     * Clear session cookie (logout).
     */
    public void clearSession(HttpServletRequest request, HttpServletResponse response) {
        Session session = getSession(request);
        if (session != null) {
            sessions.remove(session.sessionId);
        }
        
        Cookie cookie = new Cookie(SESSION_COOKIE, "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }
    
    /**
     * Check if wallet is in allowed list.
     */
    private boolean isWalletAllowed(String walletAddress) {
        if (allowedWallets == null) {
            return true;  // No restrictions
        }
        
        String normalized = walletAddress.toLowerCase();
        for (String allowed : allowedWallets) {
            if (allowed.trim().equals(normalized)) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * Derive Ethereum address from P-256 public key.
     * 
     * <p>Note: This is a simplified derivation. For production,
     * use proper Ethereum address derivation from secp256k1 or
     * a mapping from P-256 to Ethereum address.</p>
     */
    private String deriveWalletAddress(byte[] publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey);
            
            // Take last 20 bytes as address
            StringBuilder sb = new StringBuilder("0x");
            for (int i = hash.length - 20; i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive wallet address", e);
        }
    }
    
    /**
     * Generate a random ID.
     */
    private String generateId() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    
    /**
     * Send 403 Forbidden response.
     */
    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"" + message + "\"}");
    }
    
    /**
     * Handle login page request.
     */
    public void handleLoginPage(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Challenge challenge = createChallenge();
        
        // Set challenge cookie
        Cookie cookie = new Cookie(CHALLENGE_COOKIE, challenge.challengeId);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(true);
        cookie.setMaxAge(CHALLENGE_TTL_MINUTES * 60);
        response.addCookie(cookie);
        
        String returnUrl = request.getParameter("return");
        if (returnUrl == null) {
            returnUrl = Base64.getUrlEncoder().encodeToString("/dashboard".getBytes(StandardCharsets.UTF_8));
        }
        
        response.setContentType("text/html");
        response.setStatus(HttpServletResponse.SC_OK);
        
        // Render login page with WebAuthn
        String html = generateLoginPage(challenge, returnUrl);
        response.getWriter().write(html);
    }
    
    /**
     * Generate login page HTML with WebAuthn integration.
     */
    private String generateLoginPage(Challenge challenge, String returnUrl) {
        String challengeBase64 = Base64.getEncoder().encodeToString(challenge.challengeBytes);
        
        return """
            <!DOCTYPE html>
            <html lang="en">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>Validator Login - Blockchain AEM</title>
                <style>
                    * { margin: 0; padding: 0; box-sizing: border-box; }
                    body {
                        font-family: 'SF Pro Display', -apple-system, BlinkMacSystemFont, sans-serif;
                        background: linear-gradient(135deg, #0a0a0a 0%, #1a1a2e 50%, #16213e 100%);
                        min-height: 100vh;
                        display: flex;
                        align-items: center;
                        justify-content: center;
                        color: #fff;
                    }
                    .login-container {
                        background: rgba(255, 255, 255, 0.05);
                        backdrop-filter: blur(20px);
                        border: 1px solid rgba(255, 255, 255, 0.1);
                        border-radius: 24px;
                        padding: 48px;
                        max-width: 420px;
                        width: 90%;
                        text-align: center;
                    }
                    .logo {
                        font-size: 48px;
                        margin-bottom: 16px;
                    }
                    h1 {
                        font-size: 24px;
                        font-weight: 600;
                        margin-bottom: 8px;
                    }
                    .subtitle {
                        color: rgba(255, 255, 255, 0.6);
                        margin-bottom: 32px;
                    }
                    .auth-button {
                        background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
                        border: none;
                        border-radius: 12px;
                        padding: 16px 32px;
                        font-size: 16px;
                        font-weight: 600;
                        color: #fff;
                        cursor: pointer;
                        width: 100%;
                        transition: transform 0.2s, box-shadow 0.2s;
                    }
                    .auth-button:hover {
                        transform: translateY(-2px);
                        box-shadow: 0 8px 24px rgba(102, 126, 234, 0.4);
                    }
                    .auth-button:disabled {
                        opacity: 0.5;
                        cursor: not-allowed;
                        transform: none;
                    }
                    .status {
                        margin-top: 24px;
                        padding: 12px;
                        border-radius: 8px;
                        font-size: 14px;
                    }
                    .status.error {
                        background: rgba(239, 68, 68, 0.2);
                        color: #fca5a5;
                    }
                    .status.success {
                        background: rgba(34, 197, 94, 0.2);
                        color: #86efac;
                    }
                    .fingerprint-icon {
                        font-size: 64px;
                        margin-bottom: 24px;
                        animation: pulse 2s infinite;
                    }
                    @keyframes pulse {
                        0%, 100% { opacity: 1; }
                        50% { opacity: 0.5; }
                    }
                </style>
            </head>
            <body>
                <div class="login-container">
                    <div class="fingerprint-icon">🔐</div>
                    <h1>Validator Access</h1>
                    <p class="subtitle">Authenticate with your passkey to access the validator dashboard</p>
                    
                    <button id="authButton" class="auth-button" onclick="authenticate()">
                        Authenticate with Passkey
                    </button>
                    
                    <div id="status" class="status" style="display: none;"></div>
                </div>
                
                <script>
                    const challenge = Uint8Array.from(atob('%s'), c => c.charCodeAt(0));
                    const challengeId = '%s';
                    const returnUrl = '%s';
                    
                    async function authenticate() {
                        const button = document.getElementById('authButton');
                        const status = document.getElementById('status');
                        
                        button.disabled = true;
                        button.textContent = 'Authenticating...';
                        status.style.display = 'none';
                        
                        try {
                            // Check WebAuthn support
                            if (!window.PublicKeyCredential) {
                                throw new Error('WebAuthn not supported in this browser');
                            }
                            
                            // Request passkey authentication
                            const credential = await navigator.credentials.get({
                                publicKey: {
                                    challenge: challenge,
                                    timeout: 60000,
                                    userVerification: 'required',
                                    rpId: window.location.hostname
                                }
                            });
                            
                            // Extract authentication data
                            const response = credential.response;
                            const signature = new Uint8Array(response.signature);
                            const authenticatorData = new Uint8Array(response.authenticatorData);
                            const clientDataJSON = new Uint8Array(response.clientDataJSON);
                            
                            // Send to server for verification
                            const verifyResponse = await fetch('/auth/verify', {
                                method: 'POST',
                                headers: { 'Content-Type': 'application/json' },
                                body: JSON.stringify({
                                    challengeId: challengeId,
                                    credentialId: credential.id,
                                    signature: btoa(String.fromCharCode(...signature)),
                                    authenticatorData: btoa(String.fromCharCode(...authenticatorData)),
                                    clientDataJSON: btoa(String.fromCharCode(...clientDataJSON))
                                })
                            });
                            
                            const result = await verifyResponse.json();
                            
                            if (result.success) {
                                status.className = 'status success';
                                status.textContent = '✅ Authentication successful! Redirecting...';
                                status.style.display = 'block';
                                
                                // Redirect to original URL
                                setTimeout(() => {
                                    window.location.href = atob(returnUrl);
                                }, 1000);
                            } else {
                                throw new Error(result.error || 'Authentication failed');
                            }
                            
                        } catch (error) {
                            console.error('Authentication error:', error);
                            status.className = 'status error';
                            status.textContent = '❌ ' + error.message;
                            status.style.display = 'block';
                            
                            button.disabled = false;
                            button.textContent = 'Authenticate with Passkey';
                        }
                    }
                </script>
            </body>
            </html>
            """.formatted(challengeBase64, challenge.challengeId, returnUrl);
    }
}
