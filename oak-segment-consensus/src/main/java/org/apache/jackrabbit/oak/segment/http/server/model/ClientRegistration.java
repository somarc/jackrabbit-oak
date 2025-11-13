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
 * Client registration information for Sling Author instances.
 * 
 * <p>Extracted from SegmentHttpServer for better organization.</p>
 */
public class ClientRegistration {
    public final String clientId;        // Unique identifier (e.g., container name)
    public final String clientUrl;       // Client's URL/address
    public final String walletAddress;   // Wallet address if provided
    public final long registeredAt;      // Timestamp
    public volatile long lastSeen;          // Last heartbeat
    
    public ClientRegistration(String clientId, String clientUrl, String walletAddress) {
        this.clientId = clientId;
        this.clientUrl = clientUrl;
        this.walletAddress = walletAddress;
        this.registeredAt = System.currentTimeMillis();
        this.lastSeen = System.currentTimeMillis();
    }
    
    public void updateLastSeen() {
        this.lastSeen = System.currentTimeMillis();
    }
}
