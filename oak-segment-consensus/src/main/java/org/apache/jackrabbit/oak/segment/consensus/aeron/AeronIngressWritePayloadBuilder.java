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

import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;

import java.nio.charset.StandardCharsets;
import java.util.List;

final class AeronIngressWritePayloadBuilder {

    EncodedMessage buildWriteProposal(String walletAddress,
                                      String path,
                                      String contentType,
                                      String message,
                                      String signature,
                                      Integer term,
                                      String ipfsCid,
                                      String proposalId) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"walletAddress\":\"").append(escapeJson(walletAddress)).append("\",");
        json.append("\"path\":\"").append(escapeJson(path)).append("\",");
        json.append("\"contentType\":\"").append(escapeJson(contentType != null ? contentType : "page")).append("\",");
        json.append("\"message\":\"").append(escapeJson(message != null ? message : "")).append("\",");
        json.append("\"signature\":\"").append(escapeJson(signature != null ? signature : "")).append("\"");
        if (term != null) {
            json.append(",\"term\":").append(term.intValue());
        }
        if (ipfsCid != null && !ipfsCid.isEmpty()) {
            json.append(",\"ipfsCid\":\"").append(escapeJson(ipfsCid)).append("\"");
        }
        if (proposalId != null && !proposalId.isEmpty()) {
            json.append(",\"proposalId\":\"").append(escapeJson(proposalId)).append("\"");
        }
        json.append("}");
        return encode(SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL, json.toString());
    }

    EncodedMessage buildWriteProposalWithBinary(String walletAddress,
                                                String path,
                                                String contentType,
                                                String message,
                                                String signature,
                                                Integer term,
                                                String blobId,
                                                String mimeType,
                                                String ipfsCid,
                                                String proposalId) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"walletAddress\":\"").append(escapeJson(walletAddress)).append("\",");
        json.append("\"path\":\"").append(escapeJson(path)).append("\",");
        json.append("\"contentType\":\"").append(escapeJson(contentType != null ? contentType : "page")).append("\",");
        json.append("\"message\":\"").append(escapeJson(message != null ? message : "")).append("\",");
        json.append("\"signature\":\"").append(escapeJson(signature != null ? signature : "")).append("\"");
        if (term != null) {
            json.append(",\"term\":").append(term.intValue());
        }
        if (blobId != null && !blobId.isEmpty()) {
            json.append(",\"blobId\":\"").append(escapeJson(blobId)).append("\"");
            json.append(",\"mimeType\":\"").append(escapeJson(
                mimeType != null ? mimeType : "application/octet-stream")).append("\"");
        }
        if (ipfsCid != null && !ipfsCid.isEmpty()) {
            json.append(",\"ipfsCid\":\"").append(escapeJson(ipfsCid)).append("\"");
        }
        if (proposalId != null && !proposalId.isEmpty()) {
            json.append(",\"proposalId\":\"").append(escapeJson(proposalId)).append("\"");
        }
        json.append("}");
        return encode(SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL, json.toString());
    }

    EncodedMessage buildDeleteProposal(String walletAddress,
                                       String path,
                                       String signature,
                                       Integer term,
                                       String proposalId) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"walletAddress\":\"").append(escapeJson(walletAddress)).append("\",");
        json.append("\"path\":\"").append(escapeJson(path)).append("\",");
        json.append("\"signature\":\"").append(escapeJson(signature != null ? signature : "")).append("\"");
        if (term != null) {
            json.append(",\"term\":").append(term.intValue());
        }
        if (proposalId != null && !proposalId.isEmpty()) {
            json.append(",\"proposalId\":\"").append(escapeJson(proposalId)).append("\"");
        }
        json.append("}");
        return encode(SimpleMessageHeader.TEMPLATE_ID_DELETE_PROPOSAL, json.toString());
    }

    EncodedMessage buildWriteBatch(List<QueuedProposal> proposals, Integer term) {
        StringBuilder json = new StringBuilder();
        json.append("{\"batch\":[");

        boolean first = true;
        for (QueuedProposal proposal : proposals) {
            if (!first) {
                json.append(",");
            }
            first = false;

            json.append("{");
            json.append("\"proposalId\":\"").append(escapeJson(proposal.getProposalId())).append("\",");
            if (term != null) {
                json.append("\"term\":").append(term.intValue()).append(",");
            }
            json.append("\"walletAddress\":\"").append(escapeJson(proposal.getWalletAddress())).append("\",");
            json.append("\"path\":\"").append(escapeJson(proposal.getPath())).append("\",");
            json.append("\"contentType\":\"").append(escapeJson(
                proposal.getContentType() != null ? proposal.getContentType() : "page")).append("\",");
            json.append("\"message\":\"").append(escapeJson(
                proposal.getMessage() != null ? proposal.getMessage() : "")).append("\",");
            json.append("\"signature\":\"").append(escapeJson(
                proposal.getSignature() != null ? proposal.getSignature() : "")).append("\"");

            if (proposal.getIntentToken() != null && !proposal.getIntentToken().isEmpty()) {
                json.append(",\"intentToken\":\"").append(escapeJson(proposal.getIntentToken())).append("\"");
            }

            if (proposal.getBlobId() != null && !proposal.getBlobId().isEmpty()) {
                json.append(",\"blobId\":\"").append(escapeJson(proposal.getBlobId())).append("\"");
                json.append(",\"mimeType\":\"").append(escapeJson(
                    proposal.getMimeType() != null ? proposal.getMimeType() : "application/octet-stream")).append("\"");
            }

            if (proposal.getIpfsCid() != null && !proposal.getIpfsCid().isEmpty()) {
                json.append(",\"ipfsCid\":\"").append(escapeJson(proposal.getIpfsCid())).append("\"");
            }

            json.append("}");
        }

        json.append("]}");
        return encode(SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH, json.toString());
    }

    private EncodedMessage encode(int templateId, String json) {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);
        int totalLength = SimpleMessageHeader.ENCODED_LENGTH + jsonBytes.length;
        MutableDirectBuffer messageBuffer = new UnsafeBuffer(new byte[totalLength]);
        SimpleMessageHeader.encode(messageBuffer, 0, jsonBytes.length, templateId);
        messageBuffer.putBytes(SimpleMessageHeader.ENCODED_LENGTH, jsonBytes);
        return new EncodedMessage(templateId, json, messageBuffer, totalLength);
    }

    String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    static final class EncodedMessage {
        final int templateId;
        final String json;
        final MutableDirectBuffer buffer;
        final int totalLength;

        private EncodedMessage(int templateId, String json, MutableDirectBuffer buffer, int totalLength) {
            this.templateId = templateId;
            this.json = json;
            this.buffer = buffer;
            this.totalLength = totalLength;
        }
    }
}
