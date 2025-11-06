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
import org.apache.jackrabbit.oak.segment.consensus.p2p.SegmentGossip;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Simple in-memory segment gossip implementation.
 * <p>
 * This is a POC implementation that stores segments in memory
 * and simulates gossip. A production implementation would:
 * - Use actual network transport (HTTP/gRPC/custom protocol)
 * - Persist segments to disk
 * - Implement efficient delta sync
 * - Use merkle trees for fast comparison
 */
public class SimpleSegmentGossip implements SegmentGossip {
    
    private final PeerDiscovery peerDiscovery;
    private final Map<String, byte[]> localSegments = new ConcurrentHashMap<>();
    private ScheduledExecutorService gossipExecutor;
    private final long gossipIntervalSeconds;
    
    /**
     * Create a new gossip service.
     *
     * @param peerDiscovery the peer discovery service
     * @param gossipIntervalSeconds how often to gossip with peers
     */
    public SimpleSegmentGossip(@NotNull PeerDiscovery peerDiscovery, long gossipIntervalSeconds) {
        this.peerDiscovery = peerDiscovery;
        this.gossipIntervalSeconds = gossipIntervalSeconds;
    }
    
    /**
     * Convenience constructor with default 10-second gossip interval.
     */
    public SimpleSegmentGossip(@NotNull PeerDiscovery peerDiscovery) {
        this(peerDiscovery, 10);
    }
    
    @Override
    public void announceSegment(@NotNull String segmentId, @NotNull byte[] segmentData) {
        // Store locally
        localSegments.put(segmentId, segmentData);
        
        // Announce to all alive peers (simplified - no actual network call)
        for (Peer peer : peerDiscovery.getAlivePeers()) {
            // In a real implementation, this would send the segment over the network
            // For POC, we just log it
            System.out.println("Would announce segment " + segmentId + " to peer " + peer.getPeerId());
        }
    }
    
    @Override
    public byte[] fetchSegment(@NotNull String segmentId) {
        // Try local first
        byte[] data = localSegments.get(segmentId);
        if (data != null) {
            return data;
        }
        
        // Request from peers (simplified - no actual network call)
        for (Peer peer : peerDiscovery.getAlivePeers()) {
            // In a real implementation, this would fetch from the peer over the network
            System.out.println("Would fetch segment " + segmentId + " from peer " + peer.getPeerId());
            // For POC, return null
        }
        
        return null;
    }
    
    @Override
    @NotNull
    public Collection<String> getLocalSegmentIds() {
        return localSegments.keySet();
    }
    
    @Override
    public boolean hasSegment(@NotNull String segmentId) {
        return localSegments.containsKey(segmentId);
    }
    
    @Override
    public void syncWithPeer(@NotNull Peer peer) {
        // Compare segment inventories and exchange missing segments
        // This is where the actual gossip/epidemic protocol would run
        
        // For POC, we just log the sync attempt
        System.out.println("Syncing with peer " + peer.getPeerId() + 
                " (local segments: " + localSegments.size() + ")");
        
        // Mark peer as alive since we just communicated
        peer.setAlive(true);
        peer.updateLastSeen();
    }
    
    @Override
    public void start() {
        gossipExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "segment-gossip");
            t.setDaemon(true);
            return t;
        });
        
        // Schedule periodic gossip with random peers
        gossipExecutor.scheduleAtFixedRate(
                this::performGossipRound,
                0,
                gossipIntervalSeconds,
                TimeUnit.SECONDS
        );
    }
    
    @Override
    public void stop() {
        if (gossipExecutor != null) {
            gossipExecutor.shutdown();
            try {
                gossipExecutor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
    
    /**
     * Perform one round of gossip with a random subset of peers.
     * <p>
     * This implements the epidemic/gossip protocol where each node
     * periodically syncs with a few random peers.
     */
    private void performGossipRound() {
        Collection<Peer> alivePeers = peerDiscovery.getAlivePeers();
        
        if (alivePeers.isEmpty()) {
            return;
        }
        
        // In a real implementation, we'd select a random subset
        // For POC, just sync with all alive peers
        for (Peer peer : alivePeers) {
            try {
                syncWithPeer(peer);
            } catch (Exception e) {
                System.err.println("Error syncing with peer " + peer.getPeerId() + ": " + e.getMessage());
                peer.setAlive(false);
            }
        }
    }
}

