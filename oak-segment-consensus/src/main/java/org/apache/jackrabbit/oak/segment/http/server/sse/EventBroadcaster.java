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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Broadcasts content events to connected SSE clients.
 * 
 * <p>Part of ADR 036 - Real-time content discovery via SSE.
 * 
 * <p>Features:
 * <ul>
 *   <li>Thread-safe client management</li>
 *   <li>Event buffering for late-joiners (catchup via 'since' param)</li>
 *   <li>Keep-alive heartbeats every 30 seconds</li>
 *   <li>Automatic cleanup of dead connections</li>
 * </ul>
 */
public class EventBroadcaster {

    private static final Logger log = LoggerFactory.getLogger(EventBroadcaster.class);

    private static final int MAX_EVENT_BUFFER = 1000;
    private static final int KEEP_ALIVE_INTERVAL_SECONDS = 30;
    private static final int MAX_CLIENTS = 100;

    private final Set<SSEClient> clients = ConcurrentHashMap.newKeySet();
    private final ConcurrentLinkedDeque<ContentEvent> eventBuffer = new ConcurrentLinkedDeque<>();
    private final AtomicLong totalEventsBroadcast = new AtomicLong(0);
    private final ScheduledExecutorService scheduler;

    public EventBroadcaster() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sse-keep-alive");
            t.setDaemon(true);
            return t;
        });

        // Start keep-alive heartbeat
        scheduler.scheduleAtFixedRate(this::sendKeepAlives, 
            KEEP_ALIVE_INTERVAL_SECONDS, 
            KEEP_ALIVE_INTERVAL_SECONDS, 
            TimeUnit.SECONDS);

        log.info("📡 EventBroadcaster initialized (max clients: {}, buffer: {})", 
            MAX_CLIENTS, MAX_EVENT_BUFFER);
    }

    /**
     * Add a new SSE client.
     * 
     * @param client The client to add
     * @return true if added, false if at capacity
     */
    public boolean addClient(SSEClient client) {
        if (clients.size() >= MAX_CLIENTS) {
            log.warn("📡 SSE client limit reached ({}/{}), rejecting connection", 
                clients.size(), MAX_CLIENTS);
            return false;
        }

        clients.add(client);
        log.debug("📡 SSE client added (total: {})", clients.size());
        return true;
    }

    /**
     * Remove an SSE client.
     */
    public void removeClient(SSEClient client) {
        clients.remove(client);
        client.close();
        log.debug("📡 SSE client removed (total: {})", clients.size());
    }

    /**
     * Broadcast an event to all matching clients.
     * 
     * @param event The event to broadcast
     */
    public void broadcast(ContentEvent event) {
        // Add to buffer
        eventBuffer.addLast(event);
        while (eventBuffer.size() > MAX_EVENT_BUFFER) {
            eventBuffer.removeFirst();
        }

        // Broadcast to matching clients
        int sent = 0;
        List<SSEClient> deadClients = new ArrayList<>();

        for (SSEClient client : clients) {
            if (client.isClosed()) {
                deadClients.add(client);
                continue;
            }

            if (client.matches(event)) {
                boolean success = client.send(event);
                if (success) {
                    sent++;
                } else {
                    deadClients.add(client);
                }
            }
        }

        // Clean up dead clients
        for (SSEClient dead : deadClients) {
            removeClient(dead);
        }

        totalEventsBroadcast.incrementAndGet();

        if (sent > 0) {
            log.debug("📡 Broadcast event {} to {} clients (type: {}, org: {})", 
                event.getId(), sent, event.getType(), event.getOrganization());
        }
    }

    /**
     * Emit a content write event.
     */
    public void emitContentWrite(String path, String wallet, String organization, 
                                  String message, String signature, String contentType) {
        ContentEvent event = ContentEvent.builder()
            .type(ContentEvent.EventType.CONTENT)
            .action(ContentEvent.Action.WRITE)
            .path(path)
            .wallet(wallet)
            .organization(organization)
            .message(message)
            .signature(signature)
            .contentType(contentType)
            .build();

        broadcast(event);
    }

    /**
     * Emit a binary upload event.
     */
    public void emitBinaryUpload(String path, String wallet, String organization,
                                  String message, String ipfsCid, Long size, String mimeType) {
        ContentEvent event = ContentEvent.builder()
            .type(ContentEvent.EventType.BINARY)
            .action(ContentEvent.Action.WRITE)
            .path(path)
            .wallet(wallet)
            .organization(organization)
            .message(message)
            .ipfsCid(ipfsCid)
            .size(size)
            .contentType(mimeType)
            .build();

        broadcast(event);
    }

    /**
     * Emit a content delete event.
     */
    public void emitContentDelete(String path, String wallet, String organization, String signature) {
        ContentEvent event = ContentEvent.builder()
            .type(ContentEvent.EventType.DELETE)
            .action(ContentEvent.Action.DELETE)
            .path(path)
            .wallet(wallet)
            .organization(organization)
            .signature(signature)
            .build();

        broadcast(event);
    }

    /**
     * Emit a wallet registration event.
     */
    public void emitWalletRegistration(String wallet, String owner) {
        ContentEvent event = ContentEvent.builder()
            .type(ContentEvent.EventType.WALLET)
            .action(ContentEvent.Action.REGISTER)
            .wallet(wallet)
            .message(owner != null ? "Owner: " + owner : null)
            .build();

        broadcast(event);
    }

    /**
     * Emit a consensus event (leader change, commit, etc).
     */
    public void emitConsensusEvent(ContentEvent.Action action, String message) {
        ContentEvent event = ContentEvent.builder()
            .type(ContentEvent.EventType.CONSENSUS)
            .action(action)
            .message(message)
            .build();

        broadcast(event);
    }

    /**
     * Get recent events from buffer.
     */
    public List<ContentEvent> getRecentEvents(int limit) {
        List<ContentEvent> recent = new ArrayList<>();
        ContentEvent[] events = eventBuffer.toArray(new ContentEvent[0]);
        
        int start = Math.max(0, events.length - limit);
        for (int i = start; i < events.length; i++) {
            recent.add(events[i]);
        }
        
        return recent;
    }

    /**
     * Send keep-alive to all clients.
     */
    private void sendKeepAlives() {
        List<SSEClient> deadClients = new ArrayList<>();

        for (SSEClient client : clients) {
            if (client.isClosed()) {
                deadClients.add(client);
            } else if (!client.sendKeepAlive()) {
                deadClients.add(client);
            }
        }

        // Clean up dead clients
        for (SSEClient dead : deadClients) {
            removeClient(dead);
        }

        if (!clients.isEmpty()) {
            log.debug("📡 Keep-alive sent to {} clients", clients.size());
        }
    }

    /**
     * Get connected client count.
     */
    public int getClientCount() {
        return clients.size();
    }

    /**
     * Get event buffer size.
     */
    public int getBufferSize() {
        return eventBuffer.size();
    }

    /**
     * Get total events broadcast.
     */
    public long getTotalEventsBroadcast() {
        return totalEventsBroadcast.get();
    }

    /**
     * Shutdown the broadcaster.
     */
    public void shutdown() {
        scheduler.shutdown();
        
        // Close all clients
        for (SSEClient client : clients) {
            client.close();
        }
        clients.clear();
        
        log.info("📡 EventBroadcaster shutdown");
    }
}

