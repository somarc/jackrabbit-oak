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

import static org.apache.jackrabbit.oak.segment.consensus.aeron.AeronIngressPayloadSupport.appendOptional;
import static org.apache.jackrabbit.oak.segment.consensus.aeron.AeronIngressPayloadSupport.escapeJson;

final class AeronIngressControlPayloadBuilder {

    AeronEncodedMessage buildStartTransaction(String transactionId,
                                              String correlationId,
                                              long timeoutMs,
                                              String initiatorWallet,
                                              Integer term) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"transactionId\":\"").append(escapeJson(transactionId)).append("\"");
        appendOptional(json, "correlationId", correlationId);
        appendOptional(json, "initiatorWallet", initiatorWallet);
        json.append(",\"timeoutMs\":").append(timeoutMs > 0 ? timeoutMs : 30000L);
        appendTerm(json, term);
        json.append("}");
        return AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_START_TRANSACTION, json.toString());
    }

    AeronEncodedMessage buildCommitTransaction(String transactionId,
                                               String correlationId,
                                               Integer term) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"transactionId\":\"").append(escapeJson(transactionId)).append("\"");
        appendOptional(json, "correlationId", correlationId);
        appendTerm(json, term);
        json.append("}");
        return AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_COMMIT_TRANSACTION, json.toString());
    }

    AeronEncodedMessage buildAbortTransaction(String transactionId,
                                              String correlationId,
                                              String reason,
                                              Integer term) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"transactionId\":\"").append(escapeJson(transactionId)).append("\"");
        appendOptional(json, "correlationId", correlationId);
        appendOptional(json, "reason", reason);
        appendTerm(json, term);
        json.append("}");
        return AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_ABORT_TRANSACTION, json.toString());
    }

    private static void appendTerm(StringBuilder json, Integer term) {
        if (term != null) {
            json.append(",\"term\":").append(term.intValue());
        }
    }

    AeronEncodedMessage buildSegmentPersisted(String proposalId,
                                              int memberId,
                                              boolean success,
                                              String durableHead,
                                              String error) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"proposalId\":\"").append(escapeJson(proposalId)).append("\",");
        json.append("\"memberId\":").append(memberId).append(",");
        json.append("\"success\":").append(success);
        appendOptional(json, "durableHead", durableHead);
        appendOptional(json, "error", error);
        json.append("}");
        return AeronIngressPayloadSupport.encode(SimpleMessageHeader.TEMPLATE_ID_SEGMENT_PERSISTED, json.toString());
    }
}
