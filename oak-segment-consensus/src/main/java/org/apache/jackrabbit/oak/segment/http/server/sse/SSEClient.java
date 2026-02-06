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

import javax.servlet.AsyncContext;
import java.io.PrintWriter;
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

    public long getConnectedAt() {
        return connectedAt;
    }

    public Set<String> getWallets() {
        return wallets;
    }

    public Set<String> getOrganizations() {
        return organizations;
    }

    public Set<String> getTypes() {
        return types;
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
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"contractVersion\":\"ops.v1\",");
        json.append("\"eventId\":\"").append(escapeJson(event.getId())).append("\",");
        json.append("\"eventType\":\"").append(escapeJson(eventType)).append("\",");
        json.append("\"sourceNode\":\"").append(escapeJson(sourceNode)).append("\",");
        json.append("\"timestampMs\":").append(event.getTimestamp()).append(",");
        json.append("\"data\":{");
        json.append("\"legacyType\":\"").append(escapeJson(event.getType())).append("\"");
        if (event.getAction() != null) {
            json.append(",\"legacyAction\":\"").append(escapeJson(event.getAction())).append("\"");
        }
        if (event.getPath() != null) {
            json.append(",\"path\":\"").append(escapeJson(event.getPath())).append("\"");
        }
        if (event.getWallet() != null) {
            json.append(",\"wallet\":\"").append(escapeJson(event.getWallet())).append("\"");
        }
        if (event.getOrganization() != null) {
            json.append(",\"organization\":\"").append(escapeJson(event.getOrganization())).append("\"");
        }
        if (event.getMessage() != null) {
            json.append(",\"message\":\"").append(escapeJson(event.getMessage())).append("\"");
        }
        if (event.getIpfsCid() != null) {
            json.append(",\"ipfsCid\":\"").append(escapeJson(event.getIpfsCid())).append("\"");
        }
        if (event.getSignature() != null) {
            json.append(",\"signature\":\"").append(escapeJson(event.getSignature())).append("\"");
        }
        if (event.getSize() != null) {
            json.append(",\"size\":").append(event.getSize());
        }
        if (event.getContentType() != null) {
            json.append(",\"contentType\":\"").append(escapeJson(event.getContentType())).append("\"");
        }
        json.append("}}");
        return json.toString();
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

    private String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
