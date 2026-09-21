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
package org.apache.jackrabbit.oak.segment.consensus.security;

import java.util.ArrayList;
import java.util.List;

/**
 * Cryptographic proof that a validator is ready to join consensus.
 * 
 * Before a validator can participate in consensus and potentially become
 * leader, it must prove:
 * 1. State is fully synced (HEAD matches network)
 * 2. Clock is aligned (epoch matches network)
 * 3. On correct chain (genesis matches)
 * 4. Can perform crypto operations (challenge-response)
 * 5. Has complete segment store (random segment verification)
 * 
 * This prevents Byzantine validators from joining with incomplete/corrupt state.
 */
public class JoinProof {
    
    // === 1. Proof of Sync ===
    /** Current HEAD segment ID */
    private String headSegmentId;
    
    /** Timestamp when HEAD was captured */
    private long headCapturedAt;
    
    // === 2. Proof of Epoch Alignment ===
    /** Current epoch according to this validator */
    private int currentEpoch;
    
    /** Timestamp used to calculate epoch */
    private long epochCalculatedAt;
    
    // === 3. Proof of Genesis Match ===
    /** Genesis segment ID (first segment in chain) */
    private String genesisSegmentId;
    
    /** SHA-256 hash of genesis content */
    private String genesisHash;
    
    // === 4. Proof of Capability ===
    /** Validator's public identifier */
    private String validatorId;
    
    /** Nonce provided by existing validator (challenge) */
    private String challengeNonce;
    
    /** Signature of nonce (response) */
    private String nonceSignature;
    
    // === 5. Proof of Segment Access ===
    /** List of random segment IDs the validator claims to have */
    private List<String> sampleSegmentIds = new ArrayList<>();
    
    /** SHA-256 hashes of those segments */
    private List<String> sampleSegmentHashes = new ArrayList<>();
    
    // === Metadata ===
    /** When this proof was generated */
    private long proofGeneratedAt;
    
    /** Validator's consensus URL */
    private String validatorUrl;
    
    public JoinProof() {
        this.proofGeneratedAt = System.currentTimeMillis();
    }
    
    // === Getters & Setters ===
    
    public String getHeadSegmentId() {
        return headSegmentId;
    }
    
    public void setHeadSegmentId(String headSegmentId) {
        this.headSegmentId = headSegmentId;
    }
    
    public long getHeadCapturedAt() {
        return headCapturedAt;
    }
    
    public void setHeadCapturedAt(long headCapturedAt) {
        this.headCapturedAt = headCapturedAt;
    }
    
    public int getCurrentEpoch() {
        return currentEpoch;
    }
    
    public void setCurrentEpoch(int currentEpoch) {
        this.currentEpoch = currentEpoch;
    }
    
    public long getEpochCalculatedAt() {
        return epochCalculatedAt;
    }
    
    public void setEpochCalculatedAt(long epochCalculatedAt) {
        this.epochCalculatedAt = epochCalculatedAt;
    }
    
    public String getGenesisSegmentId() {
        return genesisSegmentId;
    }
    
    public void setGenesisSegmentId(String genesisSegmentId) {
        this.genesisSegmentId = genesisSegmentId;
    }
    
    public String getGenesisHash() {
        return genesisHash;
    }
    
    public void setGenesisHash(String genesisHash) {
        this.genesisHash = genesisHash;
    }
    
    public String getValidatorId() {
        return validatorId;
    }
    
    public void setValidatorId(String validatorId) {
        this.validatorId = validatorId;
    }
    
    public String getChallengeNonce() {
        return challengeNonce;
    }
    
    public void setChallengeNonce(String challengeNonce) {
        this.challengeNonce = challengeNonce;
    }
    
    public String getNonceSignature() {
        return nonceSignature;
    }
    
    public void setNonceSignature(String nonceSignature) {
        this.nonceSignature = nonceSignature;
    }
    
    public List<String> getSampleSegmentIds() {
        return sampleSegmentIds;
    }
    
    public void setSampleSegmentIds(List<String> sampleSegmentIds) {
        this.sampleSegmentIds = sampleSegmentIds;
    }
    
    public List<String> getSampleSegmentHashes() {
        return sampleSegmentHashes;
    }
    
    public void setSampleSegmentHashes(List<String> sampleSegmentHashes) {
        this.sampleSegmentHashes = sampleSegmentHashes;
    }
    
    public long getProofGeneratedAt() {
        return proofGeneratedAt;
    }
    
    public void setProofGeneratedAt(long proofGeneratedAt) {
        this.proofGeneratedAt = proofGeneratedAt;
    }
    
    public String getValidatorUrl() {
        return validatorUrl;
    }
    
    public void setValidatorUrl(String validatorUrl) {
        this.validatorUrl = validatorUrl;
    }
    
    /**
     * Convert to JSON for transmission.
     */
    public String toJson() {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"headSegmentId\":\"").append(escape(headSegmentId)).append("\",");
        json.append("\"headCapturedAt\":").append(headCapturedAt).append(",");
        json.append("\"currentEpoch\":").append(currentEpoch).append(",");
        json.append("\"epochCalculatedAt\":").append(epochCalculatedAt).append(",");
        json.append("\"genesisSegmentId\":\"").append(escape(genesisSegmentId)).append("\",");
        json.append("\"genesisHash\":\"").append(escape(genesisHash)).append("\",");
        json.append("\"validatorId\":\"").append(escape(validatorId)).append("\",");
        json.append("\"challengeNonce\":\"").append(escape(challengeNonce)).append("\",");
        json.append("\"nonceSignature\":\"").append(escape(nonceSignature)).append("\",");
        json.append("\"validatorUrl\":\"").append(escape(validatorUrl)).append("\",");
        json.append("\"proofGeneratedAt\":").append(proofGeneratedAt).append(",");
        
        // Sample segments
        json.append("\"sampleSegmentIds\":[");
        for (int i = 0; i < sampleSegmentIds.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escape(sampleSegmentIds.get(i))).append("\"");
        }
        json.append("],");
        
        json.append("\"sampleSegmentHashes\":[");
        for (int i = 0; i < sampleSegmentHashes.size(); i++) {
            if (i > 0) json.append(",");
            json.append("\"").append(escape(sampleSegmentHashes.get(i))).append("\"");
        }
        json.append("]");
        
        json.append("}");
        return json.toString();
    }
    
    /**
     * Parse from JSON.
     */
    public static JoinProof fromJson(String jsonStr) {
        JoinProof proof = new JoinProof();
        
        // Simple JSON parsing (production would use Jackson/Gson)
        proof.headSegmentId = extractJsonField(jsonStr, "headSegmentId");
        proof.headCapturedAt = Long.parseLong(extractJsonField(jsonStr, "headCapturedAt", "0"));
        proof.currentEpoch = Integer.parseInt(extractJsonField(jsonStr, "currentEpoch", "0"));
        proof.epochCalculatedAt = Long.parseLong(extractJsonField(jsonStr, "epochCalculatedAt", "0"));
        proof.genesisSegmentId = extractJsonField(jsonStr, "genesisSegmentId");
        proof.genesisHash = extractJsonField(jsonStr, "genesisHash");
        proof.validatorId = extractJsonField(jsonStr, "validatorId");
        proof.challengeNonce = extractJsonField(jsonStr, "challengeNonce");
        proof.nonceSignature = extractJsonField(jsonStr, "nonceSignature");
        proof.validatorUrl = extractJsonField(jsonStr, "validatorUrl");
        proof.proofGeneratedAt = Long.parseLong(extractJsonField(jsonStr, "proofGeneratedAt", "0"));
        
        // Parse arrays (simplified)
        proof.sampleSegmentIds = extractJsonArray(jsonStr, "sampleSegmentIds");
        proof.sampleSegmentHashes = extractJsonArray(jsonStr, "sampleSegmentHashes");
        
        return proof;
    }
    
    private String escape(String str) {
        if (str == null) return "";
        return str.replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
    
    private static String extractJsonField(String json, String field) {
        return extractJsonField(json, field, "");
    }
    
    private static String extractJsonField(String json, String field, String defaultValue) {
        String pattern = "\"" + field + "\":";
        int start = json.indexOf(pattern);
        if (start == -1) return defaultValue;
        
        start += pattern.length();
        while (start < json.length() && (json.charAt(start) == ' ' || json.charAt(start) == '\"')) {
            start++;
        }
        
        int end = start;
        boolean inQuotes = json.charAt(start - 1) == '\"';
        if (inQuotes) {
            while (end < json.length() && json.charAt(end) != '\"') {
                end++;
            }
        } else {
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') {
                end++;
            }
        }
        
        if (start >= end) return defaultValue;
        return json.substring(start, end).trim();
    }
    
    private static List<String> extractJsonArray(String json, String field) {
        List<String> result = new ArrayList<>();
        String pattern = "\"" + field + "\":[";
        int start = json.indexOf(pattern);
        if (start == -1) return result;
        
        start += pattern.length();
        int end = json.indexOf("]", start);
        if (end == -1) return result;
        
        String arrayContent = json.substring(start, end);
        String[] items = arrayContent.split(",");
        for (String item : items) {
            String cleaned = item.trim().replace("\"", "");
            if (!cleaned.isEmpty()) {
                result.add(cleaned);
            }
        }
        
        return result;
    }
    
    @Override
    public String toString() {
        return "JoinProof{" +
                "validatorId='" + validatorId + '\'' +
                ", headSegmentId='" + headSegmentId + '\'' +
                ", currentEpoch=" + currentEpoch +
                ", genesisSegmentId='" + genesisSegmentId + '\'' +
                '}';
    }
}

