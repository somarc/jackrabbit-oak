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

import java.nio.charset.StandardCharsets;

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
                        String blobId, String mimeType, String ipfsCid, String proposalId);
        void applyDelete(String walletAddress, String path, String signature, String proposalId);
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

    /**
     * Callback interface for durability acknowledgments (ADR 026).
     */
    public interface DurabilityCallback {
        void onQueueSegment(String proposalId, int totalMembers, int requiredAcks);
        void onSegmentPersisted(String proposalId, int memberId, String durableHead, boolean success, String error);
        void onAckSegmentPersisted(String proposalId, boolean success, String durableHead, String error,
                                   int totalMembers, int requiredAcks);
    }
    
    private WriteCallback writeCallback;
    private HeadBroadcastCallback headBroadcastCallback;
    private GCCallback gcCallback;
    private DurabilityCallback durabilityCallback;
    
    /**
     * Create a new message dispatcher (default constructor for OSGi).
     */
    public MessageDispatcher() {
        this.writeCallback = null;
        this.headBroadcastCallback = null;
        this.gcCallback = null;
        this.durabilityCallback = null;
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
        this.durabilityCallback = null;
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

    public void setDurabilityCallback(DurabilityCallback durabilityCallback) {
        this.durabilityCallback = durabilityCallback;
        log.info("✅ MessageDispatcher durability callback set");
    }
    
    /**
     * Dispatch an incoming Aeron message to the appropriate handler.
     * 
     * <p>Uses {@link SimpleMessageHeader} to decode the SBE header format:
     * <ul>
     *   <li>Bytes 0-1: blockLength (payload size)</li>
     *   <li>Bytes 2-3: templateId (message type)</li>
     *   <li>Bytes 4-5: schemaId</li>
     *   <li>Bytes 6-7: version</li>
     * </ul>
     * 
     * @param timestamp message timestamp
     * @param buffer message buffer
     * @param offset buffer offset
     * @param length message length
     * @return true if message was successfully processed
     */
    public boolean dispatch(long timestamp, DirectBuffer buffer, int offset, int length) {
        try {
            // Validate minimum message length for SBE header
            if (length < SimpleMessageHeader.ENCODED_LENGTH) {
                log.warn("⚠️  Message too short: {} bytes (minimum {} for SBE header)", 
                    length, SimpleMessageHeader.ENCODED_LENGTH);
                return false;
            }
            
            // Decode SBE header using SimpleMessageHeader
            SimpleMessageHeader.HeaderInfo header = SimpleMessageHeader.decode(buffer, offset);
            
            log.debug("📬 Received message: templateId={}, blockLength={}, length={}", 
                header.templateId, header.blockLength, length);
            
            // Validate payload length matches header
            int payloadLength = length - SimpleMessageHeader.ENCODED_LENGTH;
            if (payloadLength < header.blockLength) {
                log.warn("⚠️  Message payload shorter than header blockLength: {} < {}", 
                    payloadLength, header.blockLength);
                return false;
            }
            
            // Advance past header to payload
            int payloadOffset = offset + SimpleMessageHeader.ENCODED_LENGTH;
            
            switch (header.templateId) {
                case SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL:
                    return handleWriteProposal(buffer, payloadOffset, header.blockLength);
                    
                case SimpleMessageHeader.TEMPLATE_ID_DELETE_PROPOSAL:
                    return handleDeleteProposal(buffer, payloadOffset, header.blockLength);
                    
                case SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH:
                    return handleWriteBatch(buffer, payloadOffset, header.blockLength);
                    
                case SimpleMessageHeader.TEMPLATE_ID_GC_PROPOSAL:
                    return handleGCProposal(buffer, payloadOffset, header.blockLength);
                    
                case SimpleMessageHeader.TEMPLATE_ID_GC_VOTE:
                    return handleGCVote(buffer, payloadOffset, header.blockLength);
                    
                case SimpleMessageHeader.TEMPLATE_ID_GC_EXECUTE:
                    return handleGCExecute(buffer, payloadOffset, header.blockLength);
                    
                case SimpleMessageHeader.TEMPLATE_ID_QUEUE_SEGMENT:
                    return handleQueueSegment(buffer, payloadOffset, header.blockLength);
                    
                case SimpleMessageHeader.TEMPLATE_ID_SEGMENT_PERSISTED:
                    return handleSegmentPersisted(buffer, payloadOffset, header.blockLength);
                    
                case SimpleMessageHeader.TEMPLATE_ID_ACK_SEGMENT_PERSISTED:
                    return handleAckSegmentPersisted(buffer, payloadOffset, header.blockLength);
                    
                case SimpleMessageHeader.TEMPLATE_ID_GENESIS_PROPOSAL:
                    log.info("🎬 GENESIS proposal received - delegating to genesis callback");
                    // Genesis is handled specially by AeronConsensusEngine
                    return true;
                    
                case SimpleMessageHeader.TEMPLATE_ID_SNAPSHOT:
                    log.debug("📸 Snapshot message received (handled separately)");
                    return true;
                    
                default:
                    log.warn("Unknown template ID: {}", header.templateId);
                    return false;
            }
            
        } catch (Exception e) {
            log.error("Failed to dispatch message", e);
            return false;
        }
    }
    
    /**
     * Handle WRITE_PROPOSAL message (template ID 100).
     * 
     * @param buffer message buffer
     * @param payloadOffset offset to JSON payload (after SBE header)
     * @param payloadLength length of JSON payload
     */
    private boolean handleWriteProposal(DirectBuffer buffer, int payloadOffset, int payloadLength) {
        try {
            // Extract JSON payload
            byte[] jsonBytes = new byte[payloadLength];
            buffer.getBytes(payloadOffset, jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();
            
            log.debug("✈️  Processing write proposal: {} bytes", payloadLength);
            
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
            String proposalId = extractJsonField(json, "proposalId");
            
            if (walletAddress == null || path == null) {
                log.warn("Invalid write proposal: missing required fields (wallet={}, path={})", 
                    walletAddress != null, path != null);
                return false;
            }
            
            if (writeCallback == null) {
                log.error("❌ Write callback not set - cannot apply write");
                return false;
            }
            
            // Delegate to callback
            log.debug("✅ Applying write: wallet={}, path={}, intentToken={}", 
                walletAddress, path, intentToken != null ? intentToken : "none");
            writeCallback.applyWrite(walletAddress, path, contentType, message, signature, 
                                    intentToken, blobId, mimeType, ipfsCid, proposalId);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to handle write proposal", e);
            return false;
        }
    }
    
    /**
     * Handle DELETE_PROPOSAL message (template ID 101).
     * 
     * @param buffer message buffer
     * @param payloadOffset offset to JSON payload (after SBE header)
     * @param payloadLength length of JSON payload
     */
    private boolean handleDeleteProposal(DirectBuffer buffer, int payloadOffset, int payloadLength) {
        try {
            // Extract JSON payload
            byte[] jsonBytes = new byte[payloadLength];
            buffer.getBytes(payloadOffset, jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();
            
            log.debug("🗑️  Processing delete proposal: {} bytes", payloadLength);
            
            // Parse delete proposal fields
            String walletAddress = extractJsonField(json, "walletAddress");
            String path = extractJsonField(json, "path");
            String signature = extractJsonField(json, "signature");
            String proposalId = extractJsonField(json, "proposalId");
            
            if (walletAddress == null || path == null) {
                log.warn("Invalid delete proposal: missing required fields");
                return false;
            }
            
            if (writeCallback == null) {
                log.error("❌ Write callback not set - cannot apply delete");
                return false;
            }
            
            // Delegate to callback
            log.info("🗑️  Applying delete: wallet={}, path={}", walletAddress, path);
            writeCallback.applyDelete(walletAddress, path, signature, proposalId);
            
            return true;
            
        } catch (Exception e) {
            log.error("Failed to handle delete proposal", e);
            return false;
        }
    }
    
    /**
     * Handle WRITE_BATCH message (template ID 106).
     * 
     * @param buffer message buffer
     * @param payloadOffset offset to JSON payload (after SBE header)
     * @param payloadLength length of JSON payload
     * @return number of proposals successfully processed
     */
    private boolean handleWriteBatch(DirectBuffer buffer, int payloadOffset, int payloadLength) {
        try {
            // Extract JSON payload
            byte[] jsonBytes = new byte[payloadLength];
            buffer.getBytes(payloadOffset, jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();
            
            log.debug("📦 Processing write batch: {} bytes", payloadLength);
            
            if (writeCallback == null) {
                log.error("❌ Write callback not set - cannot apply batch");
                return false;
            }
            
            // Parse batch JSON: {"batch":[{...},{...}]}
            int batchStart = json.indexOf("[");
            int batchEnd = json.lastIndexOf("]");
            
            if (batchStart < 0 || batchEnd < 0) {
                log.error("❌ Invalid batch format: {}", json.substring(0, Math.min(100, json.length())));
                return false;
            }
            
            // Parse individual proposals from batch array
            String batchContent = json.substring(batchStart + 1, batchEnd);
            java.util.List<String> proposals = parseBatchProposals(batchContent);
            
            log.debug("   Batch contains {} proposals", proposals.size());
            
            // Process each proposal in the batch
            int successCount = 0;
            for (String proposalJson : proposals) {
                // Parse proposal fields (batch proposals don't have "type" field - they're all writes)
                String walletAddress = extractJsonField(proposalJson, "walletAddress");
                String path = extractJsonField(proposalJson, "path");
                String contentType = extractJsonField(proposalJson, "contentType");
                String message = extractJsonField(proposalJson, "message");
                String signature = extractJsonField(proposalJson, "signature");
                String intentToken = extractJsonField(proposalJson, "intentToken");
                String blobId = extractJsonField(proposalJson, "blobId");
                String mimeType = extractJsonField(proposalJson, "mimeType");
                String ipfsCid = extractJsonField(proposalJson, "ipfsCid"); // ADR 016
                String proposalId = extractJsonField(proposalJson, "proposalId");
                
                if (walletAddress == null || path == null) {
                    log.warn("Invalid proposal in batch: missing required fields");
                    continue;
                }
                
                writeCallback.applyWrite(walletAddress, path, contentType, message, 
                                        signature, intentToken, blobId, mimeType, ipfsCid, proposalId);
                successCount++;
            }
            
            log.debug("✅ Batch processed: {}/{} proposals successful", successCount, proposals.size());
            lastBatchSize = successCount;
            return successCount > 0;
            
        } catch (Exception e) {
            log.error("Failed to handle write batch", e);
            return false;
        }
    }
    
    /**
     * Parse batch content into individual proposal JSON strings.
     * 
     * <p>Handles nested JSON objects by tracking brace depth.
     */
    private java.util.List<String> parseBatchProposals(String batchContent) {
        java.util.List<String> proposals = new java.util.ArrayList<>();
        
        int depth = 0;
        StringBuilder currentProposal = new StringBuilder();
        
        for (int i = 0; i < batchContent.length(); i++) {
            char c = batchContent.charAt(i);
            if (c == '{') {
                depth++;
                currentProposal.append(c);
            } else if (c == '}') {
                depth--;
                currentProposal.append(c);
                if (depth == 0) {
                    proposals.add(currentProposal.toString());
                    currentProposal = new StringBuilder();
                }
            } else if (depth > 0) {
                currentProposal.append(c);
            }
        }
        
        return proposals;
    }
    
    /**
     * Extract a field from JSON string (simple parser, no dependencies).
     * 
     * <p>Handles both quoted string values and unquoted values.
     */
    private String extractJsonField(String json, String field) {
        // Handle both "field":"value" and "field": "value" (with optional whitespace)
        String fieldPrefix = "\"" + field + "\"";
        int fieldStart = json.indexOf(fieldPrefix);
        if (fieldStart == -1) return null;
        
        // Find the colon after the field name
        int colonIndex = json.indexOf(":", fieldStart + fieldPrefix.length());
        if (colonIndex == -1) return null;
        
        // Skip optional whitespace and find the opening quote
        int quoteStart = json.indexOf("\"", colonIndex);
        if (quoteStart == -1) return null;
        
        // Find the closing quote
        int quoteEnd = json.indexOf("\"", quoteStart + 1);
        if (quoteEnd == -1) return null;
        
        return json.substring(quoteStart + 1, quoteEnd);
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
     * 
     * @param buffer message buffer
     * @param payloadOffset offset to JSON payload (after SBE header)
     * @param payloadLength length of JSON payload
     */
    private boolean handleGCProposal(DirectBuffer buffer, int payloadOffset, int payloadLength) {
        if (gcCallback == null) {
            log.warn("⚠️  GC callback not set - cannot process GC proposal");
            return false;
        }
        
        try {
            // Extract JSON payload
            byte[] jsonBytes = new byte[payloadLength];
            buffer.getBytes(payloadOffset, jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();
            
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
     * 
     * @param buffer message buffer
     * @param payloadOffset offset to JSON payload (after SBE header)
     * @param payloadLength length of JSON payload
     */
    private boolean handleGCVote(DirectBuffer buffer, int payloadOffset, int payloadLength) {
        if (gcCallback == null) {
            log.warn("⚠️  GC callback not set - cannot process GC vote");
            return false;
        }
        
        try {
            // Extract JSON payload
            byte[] jsonBytes = new byte[payloadLength];
            buffer.getBytes(payloadOffset, jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();
            
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
     * 
     * @param buffer message buffer
     * @param payloadOffset offset to JSON payload (after SBE header)
     * @param payloadLength length of JSON payload
     */
    private boolean handleGCExecute(DirectBuffer buffer, int payloadOffset, int payloadLength) {
        if (gcCallback == null) {
            log.warn("⚠️  GC callback not set - cannot process GC execute");
            return false;
        }
        
        try {
            // Extract JSON payload
            byte[] jsonBytes = new byte[payloadLength];
            buffer.getBytes(payloadOffset, jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();
            
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
    
    /**
     * Get the number of proposals processed in the last batch.
     * Used for metrics tracking.
     */
    public int getLastBatchSize() {
        return lastBatchSize;
    }
    
    private volatile int lastBatchSize = 0;

    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // DURABILITY MESSAGE HANDLERS (ADR 026)
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━

    private boolean handleQueueSegment(DirectBuffer buffer, int payloadOffset, int payloadLength) {
        if (durabilityCallback == null) {
            log.warn("⚠️  Durability callback not set - cannot process queue segment");
            return false;
        }

        try {
            byte[] jsonBytes = new byte[payloadLength];
            buffer.getBytes(payloadOffset, jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();

            String proposalId = extractJsonField(json, "proposalId");
            Long totalMembers = extractJsonLongField(json, "totalMembers");
            Long requiredAcks = extractJsonLongField(json, "requiredAcks");

            if (proposalId == null || totalMembers == null || requiredAcks == null) {
                log.warn("Invalid queue segment: missing required fields (proposalId={}, totalMembers={}, requiredAcks={})",
                    proposalId, totalMembers, requiredAcks);
                return false;
            }

            durabilityCallback.onQueueSegment(proposalId, totalMembers.intValue(), requiredAcks.intValue());
            return true;
        } catch (Exception e) {
            log.error("Failed to handle queue segment", e);
            return false;
        }
    }

    private boolean handleSegmentPersisted(DirectBuffer buffer, int payloadOffset, int payloadLength) {
        if (durabilityCallback == null) {
            log.warn("⚠️  Durability callback not set - cannot process segment persisted");
            return false;
        }

        try {
            byte[] jsonBytes = new byte[payloadLength];
            buffer.getBytes(payloadOffset, jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();

            String proposalId = extractJsonField(json, "proposalId");
            Long memberIdLong = extractJsonLongField(json, "memberId");
            String durableHead = extractJsonField(json, "durableHead");
            Boolean success = extractJsonBooleanField(json, "success");
            String error = extractJsonField(json, "error");

            if (proposalId == null || memberIdLong == null || success == null) {
                log.warn("Invalid segment persisted: missing required fields");
                return false;
            }

            durabilityCallback.onSegmentPersisted(
                proposalId,
                memberIdLong.intValue(),
                durableHead,
                success,
                error
            );
            return true;
        } catch (Exception e) {
            log.error("Failed to handle segment persisted", e);
            return false;
        }
    }

    private boolean handleAckSegmentPersisted(DirectBuffer buffer, int payloadOffset, int payloadLength) {
        if (durabilityCallback == null) {
            log.warn("⚠️  Durability callback not set - cannot process ack segment persisted");
            return false;
        }

        try {
            byte[] jsonBytes = new byte[payloadLength];
            buffer.getBytes(payloadOffset, jsonBytes);
            String json = new String(jsonBytes, StandardCharsets.UTF_8).trim();

            String proposalId = extractJsonField(json, "proposalId");
            Boolean success = extractJsonBooleanField(json, "success");
            String durableHead = extractJsonField(json, "durableHead");
            String error = extractJsonField(json, "error");
            Long totalMembers = extractJsonLongField(json, "totalMembers");
            Long requiredAcks = extractJsonLongField(json, "requiredAcks");

            if (proposalId == null || success == null || totalMembers == null || requiredAcks == null) {
                log.warn("Invalid ack segment persisted: missing required fields");
                return false;
            }

            durabilityCallback.onAckSegmentPersisted(
                proposalId,
                success,
                durableHead,
                error,
                totalMembers.intValue(),
                requiredAcks.intValue()
            );
            return true;
        } catch (Exception e) {
            log.error("Failed to handle ack segment persisted", e);
            return false;
        }
    }
}
