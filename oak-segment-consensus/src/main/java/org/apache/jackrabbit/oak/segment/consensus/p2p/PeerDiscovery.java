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
package org.apache.jackrabbit.oak.segment.consensus.p2p;

import org.jetbrains.annotations.NotNull;

import java.util.Collection;

/**
 * Service for discovering and managing peer nodes in the network.
 * <p>
 * Implementations might use:
 * - Static configuration (seed nodes)
 * - UDP multicast discovery
 * - Service registry (Consul, etcd, ZooKeeper)
 * - DNS-based discovery
 */
public interface PeerDiscovery {
    
    /**
     * Get all currently known peers in the network.
     *
     * @return collection of peers
     */
    @NotNull
    Collection<Peer> getKnownPeers();
    
    /**
     * Get all peers that are currently alive (reachable).
     *
     * @return collection of alive peers
     */
    @NotNull
    Collection<Peer> getAlivePeers();
    
    /**
     * Register a new peer in the network.
     *
     * @param peer the peer to register
     */
    void registerPeer(@NotNull Peer peer);
    
    /**
     * Remove a peer from the network.
     *
     * @param peerId the ID of the peer to remove
     */
    void removePeer(@NotNull String peerId);
    
    /**
     * Get a specific peer by ID.
     *
     * @param peerId the peer ID
     * @return the peer, or null if not found
     */
    Peer getPeer(@NotNull String peerId);
    
    /**
     * Start the discovery service.
     * <p>
     * This begins periodic peer discovery and health checks.
     */
    void start();
    
    /**
     * Stop the discovery service.
     */
    void stop();
}

