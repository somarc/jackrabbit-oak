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
import org.jetbrains.annotations.NotNull;

/**
 * Simple implementation of a peer node.
 */
public class SimplePeer implements Peer {
    
    private final String peerId;
    private final String address;
    private String headSegmentId;
    private boolean alive;
    private long lastSeenTimestamp;
    
    public SimplePeer(@NotNull String peerId, @NotNull String address) {
        this.peerId = peerId;
        this.address = address;
        this.alive = false;
        this.lastSeenTimestamp = System.currentTimeMillis();
    }
    
    @Override
    @NotNull
    public String getPeerId() {
        return peerId;
    }
    
    @Override
    @NotNull
    public String getAddress() {
        return address;
    }
    
    @Override
    public String getHeadSegmentId() {
        return headSegmentId;
    }
    
    @Override
    public void setHeadSegmentId(@NotNull String segmentId) {
        this.headSegmentId = segmentId;
    }
    
    @Override
    public boolean isAlive() {
        return alive;
    }
    
    @Override
    public void setAlive(boolean alive) {
        this.alive = alive;
        if (alive) {
            updateLastSeen();
        }
    }
    
    @Override
    public long getLastSeenTimestamp() {
        return lastSeenTimestamp;
    }
    
    @Override
    public void updateLastSeen() {
        this.lastSeenTimestamp = System.currentTimeMillis();
    }
    
    @Override
    public String toString() {
        return "Peer{" +
                "id='" + peerId + '\'' +
                ", address='" + address + '\'' +
                ", alive=" + alive +
                ", head=" + headSegmentId +
                '}';
    }
}

