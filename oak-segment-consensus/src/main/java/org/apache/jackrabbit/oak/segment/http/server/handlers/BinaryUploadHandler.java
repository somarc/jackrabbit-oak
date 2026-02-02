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

import org.apache.jackrabbit.oak.segment.http.server.binary.UploadSession;
import org.apache.jackrabbit.oak.segment.http.server.binary.UploadSessionManager;
import org.apache.jackrabbit.oak.segment.http.server.binary.UploadStatus;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * HTTP handler for lazy binary uploads (ADR 020).
 * 
 * <p>Implements the "upload on confirmation" pattern where clients:
 * <ol>
 *   <li>Declare upload intent (get intentToken)</li>
 *   <li>Submit write proposal with intentToken</li>
 *   <li>Wait for finality notification</li>
 *   <li>Upload binary to IPFS</li>
 *   <li>Complete upload with CID</li>
 * </ol>
 * 
 * <p><strong>Endpoints:</strong>
 * <ul>
 *   <li>POST /v1/binary/declare-intent - Declare upload intent</li>
 *   <li>GET /v1/binary/check-intent/:token - Check upload status</li>
 *   <li>POST /v1/binary/complete-upload - Complete with CID</li>
 * </ul>
 * 
 * <p><strong>OSGi-ready:</strong> Delegates to {@link UploadSessionManager}
 * for clean separation of concerns.</p>
 */
public class BinaryUploadHandler {
    
    private static final Logger log = LoggerFactory.getLogger(BinaryUploadHandler.class);
    
    private final UploadSessionManager sessionManager;
    
    /**
     * Create handler with session manager (dependency injection).
     * 
     * @param sessionManager the upload session manager
     */
    public BinaryUploadHandler(UploadSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }
    
    /**
     * Handle declare-intent request.
     * 
     * <p>Request params:
     * <ul>
     *   <li>walletAddress (required) - Ethereum wallet address</li>
     *   <li>filesize (required) - File size in bytes</li>
     *   <li>mimeType (required) - MIME type</li>
     *   <li>contentHash (optional) - Content hash for deduplication</li>
     * </ul>
     * 
     * <p>Response:
     * <pre>
     * {
     *   "intentToken": "intent-abc123...",
     *   "expirySeconds": 900,
     *   "message": "Upload binary when you receive confirmation notification"
     * }
     * </pre>
     */
    public void handleDeclareIntent(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String walletAddress = request.getParameter("walletAddress");
            String filesizeStr = request.getParameter("filesize");
            String mimeType = request.getParameter("mimeType");
            String contentHash = request.getParameter("contentHash");
            
            // Validate required params
            if (walletAddress == null || filesizeStr == null || mimeType == null) {
                sendError(response, 400, "Missing required parameters: walletAddress, filesize, mimeType");
                return;
            }
            
            // Validate wallet format
            if (!walletAddress.matches("^0x[a-fA-F0-9]{40}$")) {
                sendError(response, 400, "Invalid wallet address format");
                return;
            }
            
            // Parse filesize
            long filesize;
            try {
                filesize = Long.parseLong(filesizeStr);
            } catch (NumberFormatException e) {
                sendError(response, 400, "Invalid filesize: " + filesizeStr);
                return;
            }
            
            // Create session
            UploadSession session = sessionManager.createSession(walletAddress, filesize, mimeType, contentHash);
            
            // Build JSON response using StringBuilder (consistent with rest of codebase)
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"intentToken\":\"").append(escapeJson(session.getIntentToken())).append("\",");
            json.append("\"expirySeconds\":900,");
            json.append("\"message\":\"Upload binary when you receive confirmation notification\"");
            json.append("}");
            
            sendJson(response, 200, json.toString());
            
            log.info("📎 Declared intent: wallet={}, token={}, filesize={}", 
                walletAddress, session.getIntentToken(), filesize);
            
        } catch (Exception e) {
            log.error("Failed to handle declare-intent", e);
            sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
    
    /**
     * Handle check-intent request.
     * 
     * <p>Response:
     * <pre>
     * {
     *   "status": "PENDING" | "READY_FOR_UPLOAD" | "COMPLETED" | "EXPIRED",
     *   "epochNumber": 410063 (if ready),
     *   "uploadDeadline": 1732748700000 (if ready, Unix timestamp)
     * }
     * </pre>
     */
    public void handleCheckIntent(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String path = request.getRequestURI();
            String intentToken = path.substring("/v1/binary/check-intent/".length());
            
            UploadSession session = sessionManager.getSession(intentToken);
            
            if (session == null) {
                sendError(response, 404, "Intent token not found or expired: " + intentToken);
                return;
            }
            
            // Build JSON response
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"status\":\"").append(session.getStatus().name()).append("\"");
            
            if (session.getStatus() == UploadStatus.READY_FOR_UPLOAD) {
                json.append(",\"epochNumber\":").append(session.getEpochNumber());
                json.append(",\"uploadDeadline\":").append(session.getUploadDeadline());
            }
            
            if (session.getStatus() == UploadStatus.COMPLETED) {
                json.append(",\"cid\":\"").append(escapeJson(session.getCid())).append("\"");
            }
            
            json.append("}");
            
            sendJson(response, 200, json.toString());
            
        } catch (Exception e) {
            log.error("Failed to handle check-intent", e);
            sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
    
    /**
     * Handle complete-upload request.
     * 
     * <p>Request params:
     * <ul>
     *   <li>intentToken (required) - The intent token</li>
     *   <li>cid (required) - The IPFS CID</li>
     *   <li>walletAddress (required) - The wallet address (must match session)</li>
     * </ul>
     * 
     * <p>Response:
     * <pre>
     * {
     *   "status": "complete",
     *   "cid": "Qmf4F3CWU6Ly958TFiR8BRP18gwvW3Xsj2yXu5DqkonWc3"
     * }
     * </pre>
     */
    public void handleCompleteUpload(HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            String intentToken = request.getParameter("intentToken");
            String cid = request.getParameter("cid");
            String walletAddress = request.getParameter("walletAddress");
            
            // Validate required params
            if (intentToken == null || cid == null || walletAddress == null) {
                sendError(response, 400, "Missing required parameters: intentToken, cid, walletAddress");
                return;
            }
            
            // Get session
            UploadSession session = sessionManager.getSession(intentToken);
            
            if (session == null) {
                sendError(response, 404, "Intent token not found or expired: " + intentToken);
                return;
            }
            
            // Validate wallet matches
            if (!session.getWalletAddress().equals(walletAddress)) {
                sendError(response, 403, "Wallet address mismatch");
                return;
            }
            
            // Validate CID format (IPFS CIDv0 or CIDv1)
            if (!cid.matches("^Qm[a-zA-Z0-9]{44}$") && !cid.matches("^b[a-zA-Z0-9]{58,}$")) {
                sendError(response, 400, "Invalid IPFS CID format: " + cid);
                return;
            }
            
            // PRODUCTION_HARDENING: Validate CID is reachable (HTTP HEAD to author's IPFS gateway)
            // For now, just accept the CID
            
            // Complete upload
            boolean success = sessionManager.completeUpload(intentToken, cid);
            
            if (!success) {
                sendError(response, 410, "Failed to complete upload (session expired)");
                return;
            }
            
            // Build JSON response
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"status\":\"complete\",");
            json.append("\"cid\":\"").append(escapeJson(cid)).append("\"");
            json.append("}");
            
            sendJson(response, 200, json.toString());
            
            log.info("✅ Upload complete: intentToken={}, cid={}", intentToken, cid);
            
        } catch (Exception e) {
            log.error("Failed to handle complete-upload", e);
            sendError(response, 500, "Internal server error: " + e.getMessage());
        }
    }
    
    /**
     * Get the session manager (for integration with other components).
     * 
     * @return the upload session manager
     */
    public UploadSessionManager getSessionManager() {
        return sessionManager;
    }
    
    /**
     * Send JSON response.
     */
    private void sendJson(HttpServletResponse response, int statusCode, String json) throws IOException {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setStatus(statusCode);
        
        try (PrintWriter writer = response.getWriter()) {
            writer.write(json);
        }
    }
    
    /**
     * Send error response.
     */
    private void sendError(HttpServletResponse response, int statusCode, String message) throws IOException {
        ApiErrorUtil.sendJsonError(response, statusCode, message);
    }
    
    /**
     * Escape JSON string (basic implementation).
     */
    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
