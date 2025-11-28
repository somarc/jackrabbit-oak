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
package org.apache.jackrabbit.oak.segment.http.server.binary;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages upload sessions for lazy binary uploads (ADR 020).
 * 
 * <p>This service is responsible for:
 * <ul>
 *   <li>Creating and tracking upload sessions</li>
 *   <li>Managing session lifecycle (expiry, cleanup)</li>
 *   <li>Updating session status based on finality events</li>
 * </ul>
 * 
 * <p><strong>OSGi-ready:</strong> This class follows OSGi service patterns
 * and can be easily converted to an OSGi Declarative Services component.</p>
 */
public class UploadSessionManager {
    
    private static final Logger log = LoggerFactory.getLogger(UploadSessionManager.class);
    
    // Configuration (could be OSGi Config in future)
    private static final long INTENT_TOKEN_EXPIRY_MS = 15 * 60 * 1000; // 15 minutes
    private static final long UPLOAD_DEADLINE_MS = 2 * 60 * 60 * 1000; // 2 hours after finality
    
    // Session storage (intentToken → UploadSession)
    private final Map<String, UploadSession> sessions = new ConcurrentHashMap<>();
    
    private final SecureRandom secureRandom = new SecureRandom();
    
    /**
     * Create a new upload session.
     * 
     * @param walletAddress the wallet address
     * @param filesize the file size in bytes
     * @param mimeType the MIME type
     * @param contentHash optional content hash for deduplication
     * @return the upload session
     */
    public UploadSession createSession(String walletAddress, long filesize, String mimeType, String contentHash) {
        String intentToken = generateIntentToken(walletAddress, filesize);
        
        UploadSession session = new UploadSession(
            intentToken,
            walletAddress,
            filesize,
            mimeType,
            contentHash,
            System.currentTimeMillis(),
            System.currentTimeMillis() + INTENT_TOKEN_EXPIRY_MS
        );
        
        sessions.put(intentToken, session);
        
        log.info("📝 Upload session created: token={}, wallet={}, size={} bytes, mimeType={}",
            intentToken, walletAddress, filesize, mimeType);
        
        // Cleanup expired sessions
        cleanupExpiredSessions();
        
        return session;
    }
    
    /**
     * Get an upload session by intent token.
     * 
     * @param intentToken the intent token
     * @return the upload session, or null if not found
     */
    public UploadSession getSession(String intentToken) {
        UploadSession session = sessions.get(intentToken);
        
        if (session != null && session.isExpired()) {
            sessions.remove(intentToken);
            return null;
        }
        
        return session;
    }
    
    /**
     * Mark a session as ready for upload (called when finality reached).
     * 
     * @param intentToken the intent token
     * @param epochNumber the epoch number when finality was reached
     */
    public void markReadyForUpload(String intentToken, long epochNumber) {
        UploadSession session = sessions.get(intentToken);
        
        if (session != null) {
            session.setStatus(UploadStatus.READY_FOR_UPLOAD);
            session.setEpochNumber(epochNumber);
            session.setUploadDeadline(System.currentTimeMillis() + UPLOAD_DEADLINE_MS);
            session.setExpiryTime(System.currentTimeMillis() + UPLOAD_DEADLINE_MS); // Extend expiry
            
            log.info("📬 Upload ready: token={}, epoch={}", intentToken, epochNumber);
        }
    }
    
    /**
     * Complete an upload with a CID.
     * 
     * @param intentToken the intent token
     * @param cid the IPFS CID
     * @return true if successful, false if session not found or invalid
     */
    public boolean completeUpload(String intentToken, String cid) {
        UploadSession session = sessions.get(intentToken);
        
        if (session == null || session.isExpired()) {
            return false;
        }
        
        session.setCid(cid);
        session.setStatus(UploadStatus.COMPLETED);
        
        log.info("✅ Upload completed: token={}, cid={}", intentToken, cid);
        
        return true;
    }
    
    /**
     * Get CID for a completed upload.
     * 
     * @param intentToken the intent token
     * @return the CID, or null if not completed
     */
    public String getCid(String intentToken) {
        UploadSession session = sessions.get(intentToken);
        return (session != null && session.getStatus() == UploadStatus.COMPLETED) 
            ? session.getCid() 
            : null;
    }
    
    /**
     * Check if an intent token is valid.
     * 
     * @param intentToken the intent token
     * @return true if valid, false if not found or expired
     */
    public boolean isValidIntent(String intentToken) {
        UploadSession session = sessions.get(intentToken);
        return session != null && !session.isExpired();
    }
    
    /**
     * Generate a secure intent token.
     */
    private String generateIntentToken(String walletAddress, long filesize) {
        try {
            byte[] randomBytes = new byte[16];
            secureRandom.nextBytes(randomBytes);
            
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(walletAddress.getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(filesize).getBytes(StandardCharsets.UTF_8));
            digest.update(Long.toString(System.currentTimeMillis()).getBytes(StandardCharsets.UTF_8));
            digest.update(randomBytes);
            
            byte[] hash = digest.digest();
            return "intent-" + bytesToHex(hash).substring(0, 32);
            
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }
    
    /**
     * Convert bytes to hex string.
     */
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
    
    /**
     * Cleanup expired sessions.
     */
    private void cleanupExpiredSessions() {
        long now = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    /**
     * Get session statistics (for monitoring).
     */
    public SessionStats getStats() {
        int pending = 0;
        int readyForUpload = 0;
        int completed = 0;
        
        for (UploadSession session : sessions.values()) {
            if (session.isExpired()) continue;
            
            switch (session.getStatus()) {
                case PENDING: pending++; break;
                case READY_FOR_UPLOAD: readyForUpload++; break;
                case COMPLETED: completed++; break;
            }
        }
        
        return new SessionStats(pending, readyForUpload, completed, sessions.size());
    }
    
    /**
     * Session statistics for monitoring.
     */
    public static class SessionStats {
        public final int pending;
        public final int readyForUpload;
        public final int completed;
        public final int total;
        
        public SessionStats(int pending, int readyForUpload, int completed, int total) {
            this.pending = pending;
            this.readyForUpload = readyForUpload;
            this.completed = completed;
            this.total = total;
        }
    }
}

