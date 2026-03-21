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

import io.aeron.Publication;
import io.aeron.cluster.client.AeronCluster;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Simple Aeron client for sending write messages through the cluster ingress.
 * 
 * <p>Creates an AeronCluster client for external write submissions:
 * - Connects via UDP to the cluster ingress endpoints
 * - Uses the same MediaDriver directory as the ClusteredService
 * - Provides offer() method to send messages through ingress
 * 
 * <p>Architecture:
 * - ClusteredService runs the cluster (leader/follower)
 * - AeronWriteClient creates an external client that connects to the cluster via UDP
 * - Both use the same MediaDriver (shared process, same Aeron directory)
 * - Messages sent via offer() go through UDP ingress → replicated via Raft → onSessionMessage() on all nodes
 */
public class AeronWriteClient {
    private static final Logger log = LoggerFactory.getLogger(AeronWriteClient.class);
    
    private final int clientId;
    private final String aeronDirectoryName;
    private final List<String> clusterHostnames;
    private final int clusterBasePort;
    private final String clientHostname;
    private final IdleStrategy idleStrategy;
    
    private AeronCluster clusterClient;
    private volatile boolean connected = false;
    
    public AeronWriteClient(
            int clientId,
            String aeronDirectoryName,
            List<String> clusterHostnames,
            int clusterBasePort,
            String clientHostname) {
        this.clientId = clientId;
        this.aeronDirectoryName = aeronDirectoryName;
        this.clusterHostnames = clusterHostnames;
        this.clusterBasePort = clusterBasePort;
        this.clientHostname = clientHostname;
        this.idleStrategy = new BackoffIdleStrategy(
            100, 10, 1000, 1_000_000
        );
    }
    
    /**
     * Connect to the Aeron cluster.
     * Retries with exponential backoff for resilient connection handling.
     */
    public void connect() {
        if (connected && clusterClient != null) {
            return; // Already connected
        }
        
        int maxRetries = 10;
        long retryIntervalMs = 1000;
        
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                AeronIngressEndpointPlanner.Plan ingressPlan =
                    AeronIngressEndpointPlanner.system(clusterHostnames, clientHostname).plan();
                
                // Create egress listener (like production)
                io.aeron.cluster.client.EgressListener egressListener = (clusterSessionId, timestamp, message, header, offset, length) -> {
                    log.debug("Received egress message from cluster (session: {}, length: {})", clusterSessionId, length);
                };
                
                clusterClient = AeronCluster.connect(
                    new AeronCluster.Context()
                        .aeronDirectoryName(aeronDirectoryName)
                        .ingressChannel("aeron:udp")  // UDP like production
                        .ingressEndpoints(ingressPlan.ingressEndpoints)  // Required for UDP
                        .egressChannel("aeron:udp?endpoint=" + ingressPlan.clientIp + ":0")  // UDP egress like production
                        .egressListener(egressListener)  // Egress listener like production
                        .idleStrategy(idleStrategy)
                        .errorHandler(e -> log.error("Error in AeronWriteClient", e))
                );
                
                connected = true;
                log.info("✅ AeronWriteClient connected successfully (clientId: {}, ingressEndpoints: {})", 
                    clientId, ingressPlan.ingressEndpoints);
                return;
                
            } catch (Exception e) {
                if (attempt < maxRetries) {
                    log.warn("⚠️  Failed to connect to Aeron cluster (attempt {}/{}): {} - retrying in {}ms...", 
                        attempt + 1, maxRetries, e.getMessage(), retryIntervalMs);
                    try {
                        Thread.sleep(retryIntervalMs);
                        retryIntervalMs = Math.min(retryIntervalMs * 2, 10000); // Exponential backoff, cap at 10s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Connection interrupted", ie);
                    }
                } else {
                    log.error("❌ Failed to connect to Aeron cluster after {} attempts", maxRetries, e);
                    throw new RuntimeException("Failed to connect to Aeron cluster", e);
                }
            }
        }
    }
    
    /**
     * Send a message through the cluster ingress.
     * Returns the position if successful, or a negative value if failed.
     * 
     * <p>Uses standard Aeron Cluster pattern: cluster.offer(buffer, offset, length)
     */
    public long offer(MutableDirectBuffer buffer, int offset, int length) {
        if (!connected || clusterClient == null) {
            log.warn("⚠️  AeronWriteClient not connected - attempting to connect...");
            try {
                connect();
            } catch (Exception e) {
                log.error("❌ Failed to connect: {}", e.getMessage());
                return Publication.NOT_CONNECTED;
            }
        }
        
        idleStrategy.reset();
        long result;
        int retries = 0;
        while ((result = clusterClient.offer(buffer, offset, length)) < 0) {
            if (result == Publication.BACK_PRESSURED) {
                idleStrategy.idle();
                retries++;
                if (retries > 100) {
                    log.error("❌ Ingress back-pressured after {} retries", retries);
                    return result;
                }
            } else if (result == Publication.NOT_CONNECTED) {
                log.warn("⚠️  Ingress not connected - waiting...");
                idleStrategy.idle();
                retries++;
                if (retries > 100) {
                    log.error("❌ Ingress not connected after {} retries", retries);
                    return result;
                }
            } else {
                log.error("❌ Failed to send message through ingress: {}", result);
                return result;
            }
        }
        
        log.debug("✅ Message sent through ingress (position: {})", result);
        return result;
    }
    
    /**
     * Close the client connection.
     */
    public void close() {
        if (clusterClient != null) {
            try {
                clusterClient.close();
                connected = false;
                log.info("✅ AeronWriteClient closed");
            } catch (Exception e) {
                log.error("Error closing AeronWriteClient", e);
            }
        }
    }
    
    public boolean isConnected() {
        return connected && clusterClient != null;
    }
}
