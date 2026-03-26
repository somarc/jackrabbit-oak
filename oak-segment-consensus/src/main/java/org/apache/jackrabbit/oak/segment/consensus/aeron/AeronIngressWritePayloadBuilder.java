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

final class AeronIngressWritePayloadBuilder {

    AeronEncodedMessage buildWriteProposal(String walletAddress,
                                           String path,
                                           String contentType,
                                           String message,
                                           String signature,
                                           Integer term,
                                           String ipfsCid,
                                           String proposalId) {
        return buildWriteProposal(
            walletAddress,
            path,
            contentType,
            message,
            signature,
            term,
            ipfsCid,
            MutationAuditMetadata.write(null, null, proposalId, null, null, null, null)
        );
    }

    AeronEncodedMessage buildWriteProposal(String walletAddress,
                                           String path,
                                           String contentType,
                                           String message,
                                           String signature,
                                           Integer term,
                                           String ipfsCid,
                                           MutationAuditMetadata auditMetadata) {
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
        appendAuditMetadata(
            json,
            auditMetadata != null ? auditMetadata.withOperation(MutationAuditMetadata.Operation.WRITE) : null
        );
        json.append("}");
        return AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_WRITE_PROPOSAL, json.toString());
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
                                                     String proposalId) {
        return buildWriteProposalWithBinary(
            walletAddress,
            path,
            contentType,
            message,
            signature,
            term,
            blobId,
            mimeType,
            ipfsCid,
            MutationAuditMetadata.write(null, null, proposalId, null, null, null, null)
        );
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
                                            String proposalId) {
        return buildDeleteProposal(
            walletAddress,
            path,
            signature,
            term,
            MutationAuditMetadata.delete(null, null, proposalId, null, null, null, null)
        );
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

            appendAuditMetadata(json, proposal.toAuditMetadata());

            json.append("}");
        }

        json.append("]}");
        return AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_WRITE_BATCH, json.toString());
    }

    String escapeJson(String str) {
        return AeronIngressPayloadSupport.escapeJson(str);
    }

    private void appendAuditMetadata(StringBuilder json, MutationAuditMetadata auditMetadata) {
        if (auditMetadata == null) {
            return;
        }
        json.append(",\"operation\":\"").append(auditMetadata.getOperation().name()).append("\"");
        appendStringField(json, "transactionId", auditMetadata.getTransactionId());
        appendStringField(json, "correlationId", auditMetadata.getCorrelationId());
        appendStringField(json, "proposalId", auditMetadata.getProposalId());
        appendStringField(json, "ethereumTxHash", auditMetadata.getEthereumTxHash());
        appendLongField(json, "confirmedBlockNumber", auditMetadata.getConfirmedBlockNumber());
        appendLongField(json, "ethereumObservedEpoch", auditMetadata.getEthereumObservedEpoch());
        appendLongField(json, "ethereumFinalizedEpoch", auditMetadata.getEthereumFinalizedEpoch());
    }

    private void appendStringField(StringBuilder json, String field, String value) {
        if (value != null && !value.isEmpty()) {
            json.append(",\"").append(field).append("\":\"").append(escapeJson(value)).append("\"");
        }
    }

    private void appendLongField(StringBuilder json, String field, Long value) {
        if (value != null) {
            json.append(",\"").append(field).append("\":").append(value.longValue());
        }
    }
}
