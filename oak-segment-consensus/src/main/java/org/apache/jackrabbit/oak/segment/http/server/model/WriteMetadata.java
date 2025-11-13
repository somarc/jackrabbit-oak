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
 * Metadata about a write for dashboard display.
 * 
 * <p>Extracted from SegmentHttpServer for better organization.</p>
 */
public class WriteMetadata {
    public final String recordId;
    public final String source;      // "consensus", "epoch-sync", "leader-accepted", "dag-local", or "local"
    public final String validator;   // URL of validator that wrote this
    public final long timestamp;
    public final String message;     // Optional message (for test writes)
    
    public WriteMetadata(String recordId, String source, String validator, long timestamp, String message) {
        this.recordId = recordId;
        this.source = source;
        this.validator = validator;
        this.timestamp = timestamp;
        this.message = message;
    }
}

