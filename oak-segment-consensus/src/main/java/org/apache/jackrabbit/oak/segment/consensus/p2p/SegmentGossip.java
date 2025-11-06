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
 * Protocol for gossiping segment data across the peer-to-peer network.
 * <p>
 * This is the core synchronization mechanism for Blockchain AEM.
 * Segments are propagated using an epidemic gossip protocol similar
 * to blockchain transaction propagation.
 */
public interface SegmentGossip {
    
    /**
     * Announce a new segment to the network.
     * <p>
     * This is called when a write is committed and the segment
     * needs to be distributed to all peers.
     *
     * @param segmentId the ID of the new segment
     * @param segmentData the raw segment data
     */
    void announceSegment(@NotNull String segmentId, @NotNull byte[] segmentData);
    
    /**
     * Request a specific segment from peers.
     * <p>
     * Used when a node discovers it's missing a segment
     * referenced by a newer commit.
     *
     * @param segmentId the segment to fetch
     * @return segment data, or null if not available
     */
    byte[] fetchSegment(@NotNull String segmentId);
    
    /**
     * Get the IDs of all segments this node has synced.
     *
     * @return collection of segment IDs
     */
    @NotNull
    Collection<String> getLocalSegmentIds();
    
    /**
     * Check if a segment is available locally.
     *
     * @param segmentId the segment ID
     * @return true if available
     */
    boolean hasSegment(@NotNull String segmentId);
    
    /**
     * Synchronize with a specific peer.
     * <p>
     * Compares segment inventories and exchanges missing segments.
     *
     * @param peer the peer to sync with
     */
    void syncWithPeer(@NotNull Peer peer);
    
    /**
     * Start the gossip protocol.
     * <p>
     * Begins periodic segment announcement and sync operations.
     */
    void start();
    
    /**
     * Stop the gossip protocol.
     */
    void stop();
}

