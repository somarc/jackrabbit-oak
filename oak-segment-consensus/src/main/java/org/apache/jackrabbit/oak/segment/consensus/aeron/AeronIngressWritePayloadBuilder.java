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

import org.apache.jackrabbit.oak.segment.consensus.queue.QueuedProposal;
import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;

import java.util.List;

import static org.apache.jackrabbit.oak.segment.consensus.aeron.AeronIngressPayloadSupport.appendOptional;
import static org.apache.jackrabbit.oak.segment.consensus.aeron.AeronIngressPayloadSupport.escapeJson;

final class AeronIngressWritePayloadBuilder {

    AeronEncodedMessage buildWriteProposal(String walletAddress,
                                           String path,
                                           String contentType,
                                           String message,
                                           String signature,
                                           Integer term,
                                           String ipfsCid,
                                           MutationAuditMetadata auditMetadata) {
        return buildWriteProposalWithBinary(walletAddress, path, contentType, message, signature, term,
            null, null, ipfsCid, auditMetadata);
    }

    AeronEncodedMessage buildWriteProposalWithBinary(String walletAddress,
                                                     String path,
                                                     String contentType,
                                                     String message,
                                                     String signature,
                                                     Integer term,
                                                     String blobId,
                                                     String mimeType,
                                                     String ipfsCid,
                                                     MutationAuditMetadata auditMetadata) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        appendWriteFields(json, walletAddress, path, contentType, message, signature);
        if (term != null) {
            json.append(",\"term\":").append(term.intValue());
        }
        appendBinary(json, blobId, mimeType);
        appendOptional(json, "ipfsCid", ipfsCid);
        appendAuditMetadata(
            json,
            auditMetadata != null ? auditMetadata.withOperation(MutationAuditMetadata.Operation.WRITE) : null
        );
        json.append("}");
        return AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL, json.toString());
    }

    AeronEncodedMessage buildDeleteProposal(String walletAddress,
                                            String path,
                                            String signature,
                                            Integer term,
                                            MutationAuditMetadata auditMetadata) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"walletAddress\":\"").append(escapeJson(walletAddress)).append("\",");
        json.append("\"path\":\"").append(escapeJson(path)).append("\",");
        json.append("\"signature\":\"").append(escapeJson(signature != null ? signature : "")).append("\"");
        if (term != null) {
            json.append(",\"term\":").append(term.intValue());
        }
        appendAuditMetadata(
            json,
            auditMetadata != null ? auditMetadata.withOperation(MutationAuditMetadata.Operation.DELETE) : null
        );
        json.append("}");
        return AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_DELETE_PROPOSAL, json.toString());
    }

    AeronEncodedMessage buildWriteBatch(List<QueuedProposal> proposals, Integer term) {
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
            appendWriteFields(json, proposal.getWalletAddress(), proposal.getPath(), proposal.getContentType(),
                proposal.getMessage(), proposal.getSignature());
            appendOptional(json, "intentToken", proposal.getIntentToken());
            appendBinary(json, proposal.getBlobId(), proposal.getMimeType());
            appendOptional(json, "ipfsCid", proposal.getIpfsCid());

            appendAuditMetadata(json, proposal.toAuditMetadata());

            json.append("}");
        }

        json.append("]}");
        return AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH, json.toString());
    }

    private static void appendWriteFields(StringBuilder json, String walletAddress, String path, String contentType,
                                          String message, String signature) {
        json.append("\"walletAddress\":\"").append(escapeJson(walletAddress)).append("\",");
        json.append("\"path\":\"").append(escapeJson(path)).append("\",");
        json.append("\"contentType\":\"").append(escapeJson(contentType != null ? contentType : "page")).append("\",");
        json.append("\"message\":\"").append(escapeJson(message != null ? message : "")).append("\",");
        json.append("\"signature\":\"").append(escapeJson(signature != null ? signature : "")).append("\"");
    }

    private static void appendBinary(StringBuilder json, String blobId, String mimeType) {
        if (blobId != null && !blobId.isEmpty()) {
            json.append(",\"blobId\":\"").append(escapeJson(blobId)).append("\"");
            json.append(",\"mimeType\":\"").append(escapeJson(
                mimeType != null ? mimeType : "application/octet-stream")).append("\"");
        }
    }

    private void appendAuditMetadata(StringBuilder json, MutationAuditMetadata auditMetadata) {
        if (auditMetadata == null) {
            return;
        }
        json.append(",\"operation\":\"").append(auditMetadata.getOperation().name()).append("\"");
        appendOptional(json, "transactionId", auditMetadata.getTransactionId());
        appendOptional(json, "correlationId", auditMetadata.getCorrelationId());
        appendOptional(json, "proposalId", auditMetadata.getProposalId());
        appendOptional(json, "ethereumTxHash", auditMetadata.getEthereumTxHash());
        appendLongField(json, "confirmedBlockNumber", auditMetadata.getConfirmedBlockNumber());
        appendLongField(json, "ethereumObservedEpoch", auditMetadata.getEthereumObservedEpoch());
        appendLongField(json, "ethereumFinalizedEpoch", auditMetadata.getEthereumFinalizedEpoch());
    }

    private void appendLongField(StringBuilder json, String field, Long value) {
        if (value != null) {
            json.append(",\"").append(field).append("\":").append(value.longValue());
        }
    }
}
