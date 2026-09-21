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

/**
 * Upload session data for lazy binary uploads (ADR 020).
 * 
 * <p>Tracks the lifecycle of a binary upload from intent declaration
 * through to CID completion.</p>
 */
public class UploadSession {
    
    private final String intentToken;
    private final String walletAddress;
    private final long filesize;
    private final String mimeType;
    private final String contentHash;
    private final long createdTime;
    private long expiryTime;
    
    private UploadStatus status = UploadStatus.PENDING;
    private Long epochNumber;
    private Long uploadDeadline;
    private String cid;
    
    public UploadSession(String intentToken, String walletAddress, long filesize,
                         String mimeType, String contentHash, long createdTime, long expiryTime) {
        this.intentToken = intentToken;
        this.walletAddress = walletAddress;
        this.filesize = filesize;
        this.mimeType = mimeType;
        this.contentHash = contentHash;
        this.createdTime = createdTime;
        this.expiryTime = expiryTime;
    }
    
    // Getters
    public String getIntentToken() { return intentToken; }
    public String getWalletAddress() { return walletAddress; }
    public long getFilesize() { return filesize; }
    public String getMimeType() { return mimeType; }
    public String getContentHash() { return contentHash; }
    public long getCreatedTime() { return createdTime; }
    public long getExpiryTime() { return expiryTime; }
    
    public UploadStatus getStatus() { return status; }
    public Long getEpochNumber() { return epochNumber; }
    public Long getUploadDeadline() { return uploadDeadline; }
    public String getCid() { return cid; }
    
    // Setters
    public void setExpiryTime(long expiryTime) { this.expiryTime = expiryTime; }
    public void setStatus(UploadStatus status) { this.status = status; }
    public void setEpochNumber(Long epochNumber) { this.epochNumber = epochNumber; }
    public void setUploadDeadline(Long uploadDeadline) { this.uploadDeadline = uploadDeadline; }
    public void setCid(String cid) { this.cid = cid; }
    
    public boolean isExpired() {
        return System.currentTimeMillis() > expiryTime;
    }
}

