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
package org.apache.jackrabbit.oak.segment.consensus.p2p.impl;

import org.apache.jackrabbit.oak.segment.consensus.p2p.Peer;
import org.apache.jackrabbit.oak.segment.consensus.p2p.PeerDiscovery;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Simple peer discovery implementation using a static seed list.
 * <p>
 * This is suitable for POC and controlled environments where
 * peer addresses are known in advance.
 */
public class StaticPeerDiscovery implements PeerDiscovery {
    
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();
    private ScheduledExecutorService healthCheckExecutor;
    private final long healthCheckIntervalSeconds;
    
    /**
     * Create a new static peer discovery service.
     *
     * @param healthCheckIntervalSeconds how often to check peer health
     */
    public StaticPeerDiscovery(long healthCheckIntervalSeconds) {
        this.healthCheckIntervalSeconds = healthCheckIntervalSeconds;
    }
    
    /**
     * Convenience constructor with default 30-second health check interval.
     */
    public StaticPeerDiscovery() {
        this(30);
    }
    
    @Override
    @NotNull
    public Collection<Peer> getKnownPeers() {
        return peers.values();
    }
    
    @Override
    @NotNull
    public Collection<Peer> getAlivePeers() {
        return peers.values().stream()
                .filter(Peer::isAlive)
                .collect(Collectors.toList());
    }
    
    @Override
    public void registerPeer(@NotNull Peer peer) {
        peers.put(peer.getPeerId(), peer);
    }
    
    @Override
    public void removePeer(@NotNull String peerId) {
        peers.remove(peerId);
    }
    
    @Override
    public Peer getPeer(@NotNull String peerId) {
        return peers.get(peerId);
    }
    
    @Override
    public void start() {
        healthCheckExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "peer-health-check");
            t.setDaemon(true);
            return t;
        });
        
        // Schedule periodic health checks
        healthCheckExecutor.scheduleAtFixedRate(
                this::performHealthChecks,
                0,
                healthCheckIntervalSeconds,
                TimeUnit.SECONDS
        );
    }
    
    @Override
    public void stop() {
        if (healthCheckExecutor != null) {
            healthCheckExecutor.shutdown();
            try {
                healthCheckExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Perform health checks on all known peers.
     * <p>
     * This is a simplified implementation that marks peers
     * as dead if they haven't been seen in 2x the health check interval.
     * A real implementation would send actual health check requests.
     */
    private void performHealthChecks() {
        long now = System.currentTimeMillis();
        long timeoutMillis = healthCheckIntervalSeconds * 2 * 1000;
        
        for (Peer peer : peers.values()) {
            if (now - peer.getLastSeenTimestamp() > timeoutMillis) {
                peer.setAlive(false);
            }
        }
    }
}

