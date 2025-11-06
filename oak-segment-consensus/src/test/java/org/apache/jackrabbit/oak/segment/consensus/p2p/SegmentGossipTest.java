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

import org.apache.jackrabbit.oak.segment.consensus.p2p.impl.SimplePeer;
import org.apache.jackrabbit.oak.segment.consensus.p2p.impl.SimpleSegmentGossip;
import org.apache.jackrabbit.oak.segment.consensus.p2p.impl.StaticPeerDiscovery;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * Tests for the P2P segment gossip protocol.
 */
public class SegmentGossipTest {
    
    private PeerDiscovery peerDiscovery;
    private SegmentGossip gossip;
    
    @Before
    public void setUp() {
        peerDiscovery = new StaticPeerDiscovery(5);
        gossip = new SimpleSegmentGossip(peerDiscovery, 1);
        
        // Register some test peers
        Peer peer1 = new SimplePeer("peer1-wallet-uuid", "localhost:8081");
        Peer peer2 = new SimplePeer("peer2-wallet-uuid", "localhost:8082");
        peer1.setAlive(true);
        peer2.setAlive(true);
        
        peerDiscovery.registerPeer(peer1);
        peerDiscovery.registerPeer(peer2);
        
        peerDiscovery.start();
        gossip.start();
    }
    
    @After
    public void tearDown() {
        gossip.stop();
        peerDiscovery.stop();
    }
    
    @Test
    public void testAnnounceSegment() {
        String segmentId = "segment-123";
        byte[] segmentData = "test segment data".getBytes(StandardCharsets.UTF_8);
        
        gossip.announceSegment(segmentId, segmentData);
        
        // Verify segment is stored locally
        assertTrue("Segment should be stored locally", gossip.hasSegment(segmentId));
        
        Collection<String> localIds = gossip.getLocalSegmentIds();
        assertTrue("Local segments should contain announced segment", localIds.contains(segmentId));
    }
    
    @Test
    public void testFetchLocalSegment() {
        String segmentId = "segment-456";
        byte[] segmentData = "another test segment".getBytes(StandardCharsets.UTF_8);
        
        gossip.announceSegment(segmentId, segmentData);
        
        byte[] fetched = gossip.fetchSegment(segmentId);
        assertNotNull("Should be able to fetch local segment", fetched);
        assertArrayEquals("Fetched data should match original", segmentData, fetched);
    }
    
    @Test
    public void testFetchMissingSegment() {
        byte[] fetched = gossip.fetchSegment("non-existent-segment");
        assertNull("Fetching missing segment should return null", fetched);
    }
    
    @Test
    public void testSyncWithPeer() {
        Peer peer = peerDiscovery.getPeer("peer1-wallet-uuid");
        assertNotNull("Peer should exist", peer);
        
        gossip.syncWithPeer(peer);
        
        // After sync, peer should be marked as alive
        assertTrue("Peer should be alive after sync", peer.isAlive());
    }
    
    @Test
    public void testPeerDiscovery() {
        Collection<Peer> allPeers = peerDiscovery.getKnownPeers();
        assertEquals("Should have 2 registered peers", 2, allPeers.size());
        
        Collection<Peer> alivePeers = peerDiscovery.getAlivePeers();
        assertEquals("Should have 2 alive peers", 2, alivePeers.size());
    }
    
    @Test
    public void testPeerRegistration() {
        Peer newPeer = new SimplePeer("peer3-wallet-uuid", "localhost:8083");
        peerDiscovery.registerPeer(newPeer);
        
        assertEquals("Should have 3 registered peers", 3, peerDiscovery.getKnownPeers().size());
        
        Peer retrieved = peerDiscovery.getPeer("peer3-wallet-uuid");
        assertNotNull("Should be able to retrieve registered peer", retrieved);
        assertEquals("Retrieved peer should match", newPeer.getPeerId(), retrieved.getPeerId());
    }
    
    @Test
    public void testPeerRemoval() {
        peerDiscovery.removePeer("peer1-wallet-uuid");
        
        assertEquals("Should have 1 peer after removal", 1, peerDiscovery.getKnownPeers().size());
        assertNull("Removed peer should not be retrievable", peerDiscovery.getPeer("peer1-wallet-uuid"));
    }
    
    @Test
    public void testMultipleSegmentAnnouncements() throws InterruptedException {
        // Announce multiple segments
        for (int i = 0; i < 10; i++) {
            String segmentId = "segment-" + i;
            byte[] data = ("data-" + i).getBytes(StandardCharsets.UTF_8);
            gossip.announceSegment(segmentId, data);
        }
        
        // Give gossip protocol time to run
        Thread.sleep(2000);
        
        // Verify all segments are stored
        Collection<String> localIds = gossip.getLocalSegmentIds();
        assertEquals("Should have 10 local segments", 10, localIds.size());
        
        for (int i = 0; i < 10; i++) {
            assertTrue("Should have segment-" + i, gossip.hasSegment("segment-" + i));
        }
    }
}

