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
package org.apache.jackrabbit.oak.segment.http.server.sse;

import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Content event for SSE streaming (ADR 036).
 * 
 * <p>Event types:
 * <ul>
 *   <li>content - Content write</li>
 *   <li>binary - Binary upload with IPFS CID</li>
 *   <li>wallet - Wallet registration</li>
 *   <li>delete - Content deletion</li>
 *   <li>consensus - Consensus events (leader change, commit)</li>
 * </ul>
 */
public class ContentEvent {

    public enum EventType {
        CONTENT("content"),
        BINARY("binary"),
        WALLET("wallet"),
        DELETE("delete"),
        CONSENSUS("consensus");

        private final String value;

        EventType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    public enum Action {
        WRITE("write"),
        DELETE("delete"),
        REGISTER("register"),
        COMMIT("commit"),
        LEADER_CHANGE("leader_change");

        private final String value;

        Action(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    private final String id;
    private final EventType type;
    private final Action action;
    private final String path;
    private final String wallet;
    private final String organization;
    private final long timestamp;
    private final String message;
    private final String ipfsCid;
    private final String signature;
    private final Long size;
    private final String contentType;

    private ContentEvent(Builder builder) {
        this.id = builder.id != null ? builder.id : String.valueOf(builder.timestamp);
        this.type = builder.type;
        this.action = builder.action;
        this.path = builder.path;
        this.wallet = builder.wallet;
        this.organization = builder.organization;
        this.timestamp = builder.timestamp;
        this.message = builder.message;
        this.ipfsCid = builder.ipfsCid;
        this.signature = builder.signature;
        this.size = builder.size;
        this.contentType = builder.contentType;
    }

    // Getters

    public String getId() {
        return id;
    }

    public String getType() {
        return type.getValue();
    }

    public String getAction() {
        return action != null ? action.getValue() : null;
    }

    public String getPath() {
        return path;
    }

    public String getWallet() {
        return wallet;
    }

    public String getOrganization() {
        return organization;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getMessage() {
        return message;
    }

    public String getIpfsCid() {
        return ipfsCid;
    }

    public String getSignature() {
        return signature;
    }

    public Long getSize() {
        return size;
    }

    public String getContentType() {
        return contentType;
    }

    /** Ordered event fields: id, type and timestamp always, the others only when set. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", id);
        map.put("type", type.getValue());
        putIfSet(map, "action", getAction());
        putIfSet(map, "path", path);
        putIfSet(map, "wallet", wallet);
        putIfSet(map, "organization", organization);
        map.put("timestamp", timestamp);
        putIfSet(map, "message", message);
        putIfSet(map, "ipfsCid", ipfsCid);
        putIfSet(map, "signature", signature);
        putIfSet(map, "size", size);
        putIfSet(map, "contentType", contentType);
        return map;
    }

    private static void putIfSet(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    /**
     * Convert to JSON string.
     */
    public String toJson() {
        return JsonOutputUtil.toJson(toMap());
    }

    /**
     * Format as SSE event.
     */
    public String toSSE() {
        StringBuilder sse = new StringBuilder();
        sse.append("event: ").append(type.getValue()).append("\n");
        sse.append("id: ").append(id).append("\n");
        sse.append("data: ").append(toJson()).append("\n\n");
        return sse.toString();
    }

    // Builder

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String id;
        private EventType type = EventType.CONTENT;
        private Action action = Action.WRITE;
        private String path;
        private String wallet;
        private String organization;
        private long timestamp = System.currentTimeMillis();
        private String message;
        private String ipfsCid;
        private String signature;
        private Long size;
        private String contentType;

        public Builder id(String id) {
            this.id = id;
            return this;
        }

        public Builder type(EventType type) {
            this.type = type;
            return this;
        }

        public Builder action(Action action) {
            this.action = action;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder wallet(String wallet) {
            this.wallet = wallet;
            return this;
        }

        public Builder organization(String organization) {
            this.organization = organization;
            return this;
        }

        public Builder timestamp(long timestamp) {
            this.timestamp = timestamp;
            return this;
        }

        public Builder message(String message) {
            this.message = message;
            return this;
        }

        public Builder ipfsCid(String ipfsCid) {
            this.ipfsCid = ipfsCid;
            return this;
        }

        public Builder signature(String signature) {
            this.signature = signature;
            return this;
        }

        public Builder size(Long size) {
            this.size = size;
            return this;
        }

        public Builder contentType(String contentType) {
            this.contentType = contentType;
            return this;
        }

        public ContentEvent build() {
            return new ContentEvent(this);
        }
    }
}

