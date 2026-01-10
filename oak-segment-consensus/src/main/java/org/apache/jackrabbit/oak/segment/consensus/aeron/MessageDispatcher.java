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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import org.agrona.DirectBuffer;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service responsible for dispatching incoming Aeron messages to appropriate handlers.
 * 
 * <p>Extracted from AeronConsensusEngine to reduce complexity and improve testability.
 * This service handles message parsing, validation, and routing based on SBE template IDs.
 * 
 * <p><strong>OSGi Component:</strong> Stateless service that can be injected via @Reference.
 * Lifecycle managed by OSGi framework. No configuration required (stateless).
 * 
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Parse SBE-encoded messages from DirectBuffer</li>
 *   <li>Validate message structure and required fields</li>
 *   <li>Route messages to appropriate callbacks</li>
 *   <li>Handle HEAD broadcasts, writes, deletes, and batches</li>
 * </ul>
 */
@Component(
    service = MessageDispatcher.class,
    immediate = true,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    property = {
        "service.description=Aeron Message Dispatcher",
        "service.vendor=Apache Software Foundation"
    }
)
public class MessageDispatcher {
    
    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);
    
    /**
     * Callback interface for write operations.
     */
    public interface WriteCallback {
        void applyWrite(String walletAddress, String path, String contentType, 
                       String message, String signature, String intentToken, 
                       String blobId, String mimeType, String ipfsCid);
        void applyDelete(String walletAddress, String path, String signature);
    }
    
    /**
     * Callback interface for HEAD broadcasts.
     */
    public interface HeadBroadcastCallback {
        void onHeadBroadcast(String newHead, int epoch, long timestamp, int validatorCount);
    }
    
    /**
     * Callback interface for GC operations.
     */
    public interface GCCallback {
        void applyGCProposal(String proposalId, String proposerWallet, String targetRevision,
                           long estimatedReclaimableSizeMB, String estimatedCostUSDC);
        void applyGCVote(String proposalId, int validatorId, boolean approve, String reason);
        void applyGCExecute(String proposalId, int executorId);
    }
    
    private WriteCallback writeCallback;
    private HeadBroadcastCallback headBroadcastCallback;
    private GCCallback gcCallback;
    
    /**
     * Create a new message dispatcher (default constructor for OSGi).
     */
    public MessageDispatcher() {
        this.writeCallback = null;
        this.headBroadcastCallback = null;
        this.gcCallback = null;
    }
    
    /**
     * Create a new message dispatcher with callbacks (for programmatic use).
     * 
     * @param writeCallback callback for write/delete operations
     * @param headBroadcastCallback callback for HEAD broadcasts
     */
    public MessageDispatcher(WriteCallback writeCallback, HeadBroadcastCallback headBroadcastCallback) {
        this.writeCallback = writeCallback;
        this.headBroadcastCallback = headBroadcastCallback;
        this.gcCallback = null;
    }
    
    /**
     * OSGi lifecycle: Activate component.
     */
    @Activate
    protected void activate() {
        log.info("✅ MessageDispatcher activated (OSGi)");
    }
    
    /**
     * OSGi lifecycle: Deactivate component.
     */
    @Deactivate
    protected void deactivate() {
        log.info("✅ MessageDispatcher deactivated (OSGi)");
    }
    
    /**
     * Set callbacks (for OSGi injection).
     */
    public void setCallbacks(WriteCallback writeCallback, HeadBroadcastCallback headBroadcastCallback) {
        this.writeCallback = writeCallback;
        this.headBroadcastCallback = headBroadcastCallback;
        log.info("✅ MessageDispatcher callbacks set");
    }
    
    /**
     * Set GC callback (for OSGi injection or programmatic use).
     */
    public void setGCCallback(GCCallback gcCallback) {
        this.gcCallback = gcCallback;
        log.info("✅ MessageDispatcher GC callback set");
    }
    
    /**
     * Dispatch an incoming Aeron message to the appropriate handler.
     * 
     * @param timestamp message timestamp
     * @param buffer message buffer
     * @param index buffer index
     * @param length message length
     * @return true if message was successfully processed
     */
    public boolean dispatch(long timestamp, DirectBuffer buffer, int index, int length) {
        try {
            // Read SBE template ID (first 2 bytes)
            int templateId = buffer.getShort(index, java.nio.ByteOrder.LITTLE_ENDIAN);
            
            log.debug("📬 Received message: templateId={}, length={}", templateId, length);
            
            switch (templateId) {
                case 1: // HEAD_BROADCAST (legacy single)
                    return handleHeadBroadcast(buffer, index, length);
                    
                case 100: // WRITE_PROPOSAL (single)
                    return handleWriteProposal(buffer, index, length);
                    
                case 101: // DELETE_PROPOSAL (single)
                    return handleDeleteProposal(buffer, index, length);
                    
                case 103: // GC_PROPOSAL
                    return handleGCProposal(buffer, index, length);
                    
                case 104: // GC_VOTE
                    return handleGCVote(buffer, index, length);
                    
                case 105: // GC_EXECUTE
                    return handleGCExecute(buffer, index, length);
                    
                case 106: // WRITE_BATCH (multiple proposals)
                    return handleWriteBatch(buffer, index, length);
                    
                default:
                    log.warn("Unknown template ID: {}", templateId);
                    return false;
            }
            
        } catch (Exception e) {
            log.error("Failed to dispatch message", e);
            return false;
        }
    }
    
    /**
     * Handle HEAD_BROADCAST message (template ID 1).
     */
    private boolean handleHeadBroadcast(DirectBuffer buffer, int index, int length) {
        try {
            // Extract JSON payload (after template ID header)
            int jsonStartIndex = index + 10; // Skip SBE header
            int jsonLength = length - 10;
            byte[] jsonBytes = new byte[jsonLength];
            buffer.getBytes(jsonStartIndex, jsonBytes);
            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            
            // Parse JSON fields
            String newHead = extractJsonField(json, "head");
            String epochStr = extractJsonField(json, "epoch");
            String timestampStr = extractJsonField(json, "timestamp");
            String validatorCountStr = extractJsonField(json, "validatorCount");
            
            if (newHead == null || epochStr == null) {
                log.warn("Invalid HEAD broadcast: missing required fields");
                return false;
            }
            
            int epoch = Integer.parseInt(epochStr);
            long broadcastTimestamp = Long.parseLong(timestampStr);
            int validatorCount = validatorCountStr != null ? Integer.parseInt(validatorCountStr) : 0;
            
            // Delegate to callback
            headBroadcastCallback.onHeadBroadcast(newHead, epoch, broadcastTimestamp, validatorCount);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to handle HEAD broadcast", e);
            return false;
        }
    }
    
    /**
     * Handle WRITE_PROPOSAL message (template ID 100).
     */
    private boolean handleWriteProposal(DirectBuffer buffer, int index, int length) {
        try {
            // Extract JSON payload
            int jsonStartIndex = index + 10;
            int jsonLength = length - 10;
            byte[] jsonBytes = new byte[jsonLength];
            buffer.getBytes(jsonStartIndex, jsonBytes);
            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            
            // Parse write proposal fields
            String walletAddress = extractJsonField(json, "walletAddress");
            String path = extractJsonField(json, "path");
            String contentType = extractJsonField(json, "contentType");
            String message = extractJsonField(json, "message");
            String signature = extractJsonField(json, "signature");
            String intentToken = extractJsonField(json, "intentToken"); // ADR 020
            String blobId = extractJsonField(json, "blobId");
            String mimeType = extractJsonField(json, "mimeType");
            String ipfsCid = extractJsonField(json, "ipfsCid"); // ADR 016
            
            if (walletAddress == null || path == null) {
                log.warn("Invalid write proposal: missing required fields");
                return false;
            }
            
            // Delegate to callback
            writeCallback.applyWrite(walletAddress, path, contentType, message, signature, 
                                    intentToken, blobId, mimeType, ipfsCid);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to handle write proposal", e);
            return false;
        }
    }
    
    /**
     * Handle DELETE_PROPOSAL message (template ID 101).
     */
    private boolean handleDeleteProposal(DirectBuffer buffer, int index, int length) {
        try {
            // Extract JSON payload
            int jsonStartIndex = index + 10;
            int jsonLength = length - 10;
            byte[] jsonBytes = new byte[jsonLength];
            buffer.getBytes(jsonStartIndex, jsonBytes);
            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            
            // Parse delete proposal fields
            String walletAddress = extractJsonField(json, "walletAddress");
            String path = extractJsonField(json, "path");
            String signature = extractJsonField(json, "signature");
            
            if (walletAddress == null || path == null) {
                log.warn("Invalid delete proposal: missing required fields");
                return false;
            }
            
            // Delegate to callback
            writeCallback.applyDelete(walletAddress, path, signature);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to handle delete proposal", e);
            return false;
        }
    }
    
    /**
     * Handle WRITE_BATCH message (template ID 106).
     */
    private boolean handleWriteBatch(DirectBuffer buffer, int index, int length) {
        try {
            // Extract JSON payload
            int jsonStartIndex = index + 10;
            int jsonLength = length - 10;
            byte[] jsonBytes = new byte[jsonLength];
            buffer.getBytes(jsonStartIndex, jsonBytes);
            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            
            log.debug("📦 Processing write batch: {} bytes", jsonLength);
            
            // Parse batch structure: {"batch":[{proposal1},{proposal2},...]}
            if (!json.startsWith("{\"batch\":[")) {
                log.warn("Invalid batch format: missing batch array");
                return false;
            }
            
            // Extract proposals array
            int proposalsStart = json.indexOf("[");
            int proposalsEnd = json.lastIndexOf("]");
            
            if (proposalsStart < 0 || proposalsEnd < 0) {
                log.warn("Invalid batch format: malformed proposals array");
                return false;
            }
            
            String proposalsJson = json.substring(proposalsStart + 1, proposalsEnd);
            
            // Split by "},{" to get individual proposals
            String[] proposals = proposalsJson.split("\\},\\{");
            
            int successCount = 0;
            for (String proposalJson : proposals) {
                // Clean up brackets
                proposalJson = proposalJson.trim();
                if (!proposalJson.startsWith("{")) {
                    proposalJson = "{" + proposalJson;
                }
                if (!proposalJson.endsWith("}")) {
                    proposalJson = proposalJson + "}";
                }
                
                // Parse proposal
                String type = extractJsonField(proposalJson, "type");
                
                if ("write".equals(type)) {
                    String walletAddress = extractJsonField(proposalJson, "walletAddress");
                    String path = extractJsonField(proposalJson, "path");
                    String contentType = extractJsonField(proposalJson, "contentType");
                    String message = extractJsonField(proposalJson, "message");
                    String signature = extractJsonField(proposalJson, "signature");
                    String intentToken = extractJsonField(proposalJson, "intentToken");
                    String blobId = extractJsonField(proposalJson, "blobId");
                    String mimeType = extractJsonField(proposalJson, "mimeType");
                    String ipfsCid = extractJsonField(proposalJson, "ipfsCid"); // ADR 016
                    
                    if (walletAddress != null && path != null) {
                        writeCallback.applyWrite(walletAddress, path, contentType, message, 
                                                signature, intentToken, blobId, mimeType, ipfsCid);
                        successCount++;
                    }
                    
                } else if ("delete".equals(type)) {
                    String walletAddress = extractJsonField(proposalJson, "walletAddress");
                    String path = extractJsonField(proposalJson, "path");
                    String signature = extractJsonField(proposalJson, "signature");
                    
                    if (walletAddress != null && path != null) {
                        writeCallback.applyDelete(walletAddress, path, signature);
                        successCount++;
                    }
                }
            }
            
            log.debug("✅ Batch processed: {}/{} proposals successful", successCount, proposals.length);
            return successCount > 0;
            
        } catch (Exception e) {
            log.error("Failed to handle write batch", e);
            return false;
        }
    }
    
    /**
     * Extract a field from JSON string (simple parser, no dependencies).
     */
    private String extractJsonField(String json, String field) {
        String pattern = "\"" + field + "\":\"";
        int startIdx = json.indexOf(pattern);
        if (startIdx < 0) {
            return null;
        }
        startIdx += pattern.length();
        int endIdx = json.indexOf("\"", startIdx);
        if (endIdx < 0) {
            return null;
        }
        return json.substring(startIdx, endIdx);
    }
    
    /**
     * Extract a numeric field from JSON string.
     */
    private Long extractJsonLongField(String json, String field) {
        String pattern = "\"" + field + "\":";
        int startIdx = json.indexOf(pattern);
        if (startIdx < 0) {
            return null;
        }
        startIdx += pattern.length();
        // Skip whitespace
        while (startIdx < json.length() && Character.isWhitespace(json.charAt(startIdx))) {
            startIdx++;
        }
        // Find end of number
        int endIdx = startIdx;
        while (endIdx < json.length() && (Character.isDigit(json.charAt(endIdx)) || json.charAt(endIdx) == '-')) {
            endIdx++;
        }
        if (endIdx == startIdx) {
            return null;
        }
        try {
            return Long.parseLong(json.substring(startIdx, endIdx));
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * Extract a boolean field from JSON string.
     */
    private Boolean extractJsonBooleanField(String json, String field) {
        String pattern = "\"" + field + "\":";
        int startIdx = json.indexOf(pattern);
        if (startIdx < 0) {
            return null;
        }
        startIdx += pattern.length();
        // Skip whitespace
        while (startIdx < json.length() && Character.isWhitespace(json.charAt(startIdx))) {
            startIdx++;
        }
        if (json.regionMatches(startIdx, "true", 0, 4)) {
            return true;
        } else if (json.regionMatches(startIdx, "false", 0, 5)) {
            return false;
        }
        return null;
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // GC MESSAGE HANDLERS
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Handle GC_PROPOSAL message (template ID 103).
     */
    private boolean handleGCProposal(DirectBuffer buffer, int index, int length) {
        if (gcCallback == null) {
            log.warn("⚠️  GC callback not set - cannot process GC proposal");
            return false;
        }
        
        try {
            // Extract JSON payload (after SBE header)
            int jsonStartIndex = index + SimpleMessageHeader.ENCODED_LENGTH;
            int jsonLength = length - SimpleMessageHeader.ENCODED_LENGTH;
            byte[] jsonBytes = new byte[jsonLength];
            buffer.getBytes(jsonStartIndex, jsonBytes);
            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            
            // Parse GC proposal fields
            String proposalId = extractJsonField(json, "proposalId");
            String proposerWallet = extractJsonField(json, "proposerWallet");
            String targetRevision = extractJsonField(json, "targetRevision");
            Long estimatedReclaimableSizeMB = extractJsonLongField(json, "estimatedReclaimableSizeMB");
            String estimatedCostUSDC = extractJsonField(json, "estimatedCostUSDC");
            
            if (proposalId == null || proposerWallet == null) {
                log.warn("Invalid GC proposal: missing required fields (proposalId={}, proposerWallet={})", 
                    proposalId, proposerWallet);
                return false;
            }
            
            log.info("🗑️  Received GC proposal: id={}, proposer={}, targetRevision={}", 
                proposalId, proposerWallet, targetRevision);
            
            // Delegate to callback
            gcCallback.applyGCProposal(proposalId, proposerWallet, targetRevision,
                estimatedReclaimableSizeMB != null ? estimatedReclaimableSizeMB : 0L,
                estimatedCostUSDC);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to handle GC proposal", e);
            return false;
        }
    }
    
    /**
     * Handle GC_VOTE message (template ID 104).
     */
    private boolean handleGCVote(DirectBuffer buffer, int index, int length) {
        if (gcCallback == null) {
            log.warn("⚠️  GC callback not set - cannot process GC vote");
            return false;
        }
        
        try {
            // Extract JSON payload
            int jsonStartIndex = index + SimpleMessageHeader.ENCODED_LENGTH;
            int jsonLength = length - SimpleMessageHeader.ENCODED_LENGTH;
            byte[] jsonBytes = new byte[jsonLength];
            buffer.getBytes(jsonStartIndex, jsonBytes);
            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            
            // Parse GC vote fields
            String proposalId = extractJsonField(json, "proposalId");
            Long validatorIdLong = extractJsonLongField(json, "validatorId");
            Boolean approve = extractJsonBooleanField(json, "approve");
            String reason = extractJsonField(json, "reason");
            
            if (proposalId == null || validatorIdLong == null || approve == null) {
                log.warn("Invalid GC vote: missing required fields");
                return false;
            }
            
            int validatorId = validatorIdLong.intValue();
            
            log.info("🗳️  Received GC vote: proposalId={}, validatorId={}, approve={}", 
                proposalId, validatorId, approve);
            
            // Delegate to callback
            gcCallback.applyGCVote(proposalId, validatorId, approve, reason);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to handle GC vote", e);
            return false;
        }
    }
    
    /**
     * Handle GC_EXECUTE message (template ID 105).
     */
    private boolean handleGCExecute(DirectBuffer buffer, int index, int length) {
        if (gcCallback == null) {
            log.warn("⚠️  GC callback not set - cannot process GC execute");
            return false;
        }
        
        try {
            // Extract JSON payload
            int jsonStartIndex = index + SimpleMessageHeader.ENCODED_LENGTH;
            int jsonLength = length - SimpleMessageHeader.ENCODED_LENGTH;
            byte[] jsonBytes = new byte[jsonLength];
            buffer.getBytes(jsonStartIndex, jsonBytes);
            String json = new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8).trim();
            
            // Parse GC execute fields
            String proposalId = extractJsonField(json, "proposalId");
            Long executorIdLong = extractJsonLongField(json, "executorId");
            
            if (proposalId == null || executorIdLong == null) {
                log.warn("Invalid GC execute: missing required fields");
                return false;
            }
            
            int executorId = executorIdLong.intValue();
            
            log.info("⚡ Received GC execute command: proposalId={}, executorId={}", 
                proposalId, executorId);
            
            // Delegate to callback
            gcCallback.applyGCExecute(proposalId, executorId);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to handle GC execute", e);
            return false;
        }
    }
}

