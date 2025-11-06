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

/**
 * Represents a peer node in the Blockchain AEM network.
 * <p>
 * Each peer has a unique identifier and connection information
 * for segment synchronization.
 */
public interface Peer {
    
    /**
     * Get the unique identifier for this peer.
     *
     * @return peer ID (typically wallet UUID)
     */
    @NotNull
    String getPeerId();
    
    /**
     * Get the network address of this peer.
     *
     * @return host:port address
     */
    @NotNull
    String getAddress();
    
    /**
     * Get the last known head segment ID for this peer.
     *
     * @return segment ID, or null if unknown
     */
    String getHeadSegmentId();
    
    /**
     * Update the head segment ID for this peer.
     *
     * @param segmentId the new head segment ID
     */
    void setHeadSegmentId(@NotNull String segmentId);
    
    /**
     * Check if this peer is currently reachable.
     *
     * @return true if peer responded to last health check
     */
    boolean isAlive();
    
    /**
     * Update the alive status of this peer.
     *
     * @param alive true if peer is reachable
     */
    void setAlive(boolean alive);
    
    /**
     * Get the timestamp of the last successful communication.
     *
     * @return epoch millis
     */
    long getLastSeenTimestamp();
    
    /**
     * Update the last seen timestamp to now.
     */
    void updateLastSeen();
}

