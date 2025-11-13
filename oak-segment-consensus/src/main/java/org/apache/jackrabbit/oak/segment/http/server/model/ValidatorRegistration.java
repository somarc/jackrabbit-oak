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
package org.apache.jackrabbit.oak.segment.http.server.model;

/**
 * Validator peer registration information.
 * 
 * <p>Extracted from SegmentHttpServer for better organization.</p>
 */
public class ValidatorRegistration {
    public enum Status {
        JOINING,    // Just joined, broadcasting presence
        SYNCING,    // Bootstrap sync in progress
        READY       // Fully synced and participating in consensus
    }
    
    public final String validatorId;     // Unique identifier (e.g., validator-1)
    public final String validatorUrl;    // Validator's URL/address
    public volatile Status status;          // Current validator status
    public final long registeredAt;      // Timestamp
    public volatile long lastSeen;          // Last heartbeat
    
    public ValidatorRegistration(String validatorId, String validatorUrl) {
        this.validatorId = validatorId;
        this.validatorUrl = validatorUrl;
        this.status = Status.JOINING;  // Start as JOINING
        this.registeredAt = System.currentTimeMillis();
        this.lastSeen = System.currentTimeMillis();
    }
    
    public void updateStatus(Status newStatus) {
        this.status = newStatus;
        this.lastSeen = System.currentTimeMillis();
    }
}

