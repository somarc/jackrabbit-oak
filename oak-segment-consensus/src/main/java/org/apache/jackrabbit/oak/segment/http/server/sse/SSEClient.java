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

import jakarta.servlet.AsyncContext;
import java.io.PrintWriter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Represents a connected SSE client with filters.
 * 
 * <p>Part of ADR 036 - Real-time content discovery via SSE.
 */
public class SSEClient {

    private final AsyncContext asyncContext;
    private final PrintWriter writer;
    private final Set<String> types;
    private final Set<String> wallets;
    private final Set<String> organizations;
    private final String pathPrefix;
    private final Long since;
    private final boolean opsV1Mode;
    private final String sourceNode;
    private final long connectedAt;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SSEClient(
        AsyncContext asyncContext,
        PrintWriter writer,
        Set<String> types,
        Set<String> wallets,
        Set<String> organizations,
        String pathPrefix,
        Long since
    ) {
        this(asyncContext, writer, types, wallets, organizations, pathPrefix, since, false, null);
    }

    public SSEClient(
        AsyncContext asyncContext,
        PrintWriter writer,
        Set<String> types,
        Set<String> wallets,
        Set<String> organizations,
        String pathPrefix,
        Long since,
        boolean opsV1Mode,
        String sourceNode
    ) {
        this.asyncContext = asyncContext;
        this.writer = writer;
        this.types = types;
        this.wallets = wallets;
        this.organizations = organizations;
        this.pathPrefix = pathPrefix;
        this.since = since;
        this.opsV1Mode = opsV1Mode;
        this.sourceNode = sourceNode != null ? sourceNode : "unknown";
        this.connectedAt = System.currentTimeMillis();
    }

    /**
     * Check if an event matches this client's filters.
     */
    public boolean matches(ContentEvent event) {
        // Check timestamp filter
        if (since != null && event.getTimestamp() <= since) {
            return false;
        }

        // Check type filter
        if (!types.isEmpty() && !types.contains(event.getType())) {
            return false;
        }

        // Check wallet filter
        if (!wallets.isEmpty() && !wallets.contains(event.getWallet())) {
            return false;
        }

        // Check organization filter
        if (!organizations.isEmpty()) {
            String eventOrg = event.getOrganization();
            if (eventOrg == null || !organizations.contains(eventOrg)) {
                return false;
            }
        }

        // Check path prefix filter
        if (pathPrefix != null && !pathPrefix.isEmpty()) {
            String eventPath = event.getPath();
            if (eventPath == null || !eventPath.startsWith(pathPrefix)) {
                return false;
            }
        }

        return true;
    }

    /**
     * Send an event to this client.
     * 
     * @return true if sent successfully, false if client is disconnected
     */
    public boolean send(ContentEvent event) {
        if (closed.get()) {
            return false;
        }

        try {
            if (opsV1Mode) {
                writer.write(toOpsV1SSE(event));
            } else {
                writer.write(event.toSSE());
            }
            writer.flush();
            
            if (writer.checkError()) {
                close();
                return false;
            }
            
            return true;
        } catch (Exception e) {
            close();
            return false;
        }
    }

    /**
     * Send a keep-alive comment.
     * 
     * @return true if sent successfully
     */
    public boolean sendKeepAlive() {
        if (closed.get()) {
            return false;
        }

        try {
            writer.write(": keep-alive\n\n");
            writer.flush();
            
            if (writer.checkError()) {
                close();
                return false;
            }
            
            return true;
        } catch (Exception e) {
            close();
            return false;
        }
    }

    /**
     * Close this client connection.
     */
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                asyncContext.complete();
            } catch (Exception e) {
                // Already closed or error - ignore
            }
        }
    }

    public boolean isClosed() {
        return closed.get();
    }

    private String toOpsV1SSE(ContentEvent event) {
        String eventType = mapOpsEventType(event);
        StringBuilder sse = new StringBuilder();
        sse.append("event: ").append(eventType).append("\n");
        sse.append("id: ").append(event.getId()).append("\n");
        sse.append("data: ").append(toOpsV1Json(event, eventType)).append("\n\n");
        return sse.toString();
    }

    private String toOpsV1Json(ContentEvent event, String eventType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("legacyType", event.getType());
        if (event.getAction() != null) {
            data.put("legacyAction", event.getAction());
        }
        Map<String, Object> fields = event.toMap();
        fields.keySet().removeAll(List.of("id", "type", "action", "timestamp"));
        data.putAll(fields);

        Map<String, Object> json = new LinkedHashMap<>();
        json.put("contractVersion", "ops.v1");
        json.put("eventId", event.getId());
        json.put("eventType", eventType);
        json.put("sourceNode", sourceNode != null ? sourceNode : "");
        json.put("timestampMs", event.getTimestamp());
        json.put("data", data);
        return JsonOutputUtil.toJson(json);
    }

    private String mapOpsEventType(ContentEvent event) {
        String type = event.getType();
        String action = event.getAction();

        if ("consensus".equals(type) && "leader_change".equals(action)) {
            return "cluster.leader.changed";
        }
        if ("consensus".equals(type) && "commit".equals(action)) {
            return "durability.ack.updated";
        }
        if ("content".equals(type) || "binary".equals(type) || "delete".equals(type)) {
            return "proposal.state.changed";
        }
        if ("wallet".equals(type)) {
            return "proposal.queue.updated";
        }
        return "health.status.changed";
    }
}
