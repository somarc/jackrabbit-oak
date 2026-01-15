/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.oak.segment.consensus.sharding;

import org.apache.jackrabbit.oak.segment.consensus.registry.ClusterRegistration;
import org.apache.jackrabbit.oak.segment.consensus.registry.ShardRegistry;
import org.apache.jackrabbit.oak.segment.consensus.registry.StaticShardRegistry;
import org.junit.Before;
import org.junit.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.Assert.*;

/**
 * Integration tests for multi-cluster sharding.
 * <p>
 * Tests the complete flow of:
 * <ul>
 *   <li>Cluster registration and shard assignment</li>
 *   <li>Shard ID computation from wallet addresses</li>
 *   <li>Authority checking and redirect logic</li>
 *   <li>Cross-cluster routing</li>
 * </ul>
 */
public class MultiClusterIntegrationTest {
    
    // Test cluster wallets
    private static final String CLUSTER_A_WALLET = "0x1234567890123456789012345678901234567890";
    private static final String CLUSTER_B_WALLET = "0xABCDEF0123456789ABCDEF0123456789ABCDEF01";
    
    // Test endpoints
    private static final String CLUSTER_A_ENDPOINT = "http://cluster-a.example.com:8090";
    private static final String CLUSTER_B_ENDPOINT = "http://cluster-b.example.com:8090";
    
    // Test content owner wallets
    private static final String CONTENT_OWNER_1 = "0x1111111111111111111111111111111111111111";
    private static final String CONTENT_OWNER_2 = "0x2222222222222222222222222222222222222222";
    private static final String CONTENT_OWNER_3 = "0x3333333333333333333333333333333333333333";
    
    private StaticShardRegistry registry;
    
    @Before
    public void setUp() {
        // Create a 2-cluster registry with non-overlapping shard ranges
        registry = StaticShardRegistry.builder()
            .addCluster(CLUSTER_A_WALLET, 0x000, 0x7FF, CLUSTER_A_ENDPOINT)  // First half
            .addCluster(CLUSTER_B_WALLET, 0x800, 0xFFF, CLUSTER_B_ENDPOINT)  // Second half
            .build();
    }
    
    // ========== Registry Tests ==========
    
    @Test
    public void testClusterRegistration() {
        assertEquals(2, registry.getClusterCount());
        
        List<ClusterRegistration> clusters = registry.getAllClusters();
        assertEquals(2, clusters.size());
    }
    
    @Test
    public void testClusterLookup() {
        ClusterRegistration clusterA = registry.getCluster(CLUSTER_A_WALLET);
        assertNotNull(clusterA);
        assertEquals(0x000, clusterA.getShardRangeStart());
        assertEquals(0x7FF, clusterA.getShardRangeEnd());
        assertEquals(CLUSTER_A_ENDPOINT, clusterA.getEndpoint());
        
        ClusterRegistration clusterB = registry.getCluster(CLUSTER_B_WALLET);
        assertNotNull(clusterB);
        assertEquals(0x800, clusterB.getShardRangeStart());
        assertEquals(0xFFF, clusterB.getShardRangeEnd());
    }
    
    @Test
    public void testShardToClusterMapping() {
        // Shard 0x000 should map to Cluster A
        ClusterRegistration forShard0 = registry.getClusterForShard(0x000);
        assertNotNull(forShard0);
        assertEquals(CLUSTER_A_WALLET.toLowerCase(), forShard0.getClusterWallet().toLowerCase());
        
        // Shard 0x7FF should map to Cluster A
        ClusterRegistration forShard7FF = registry.getClusterForShard(0x7FF);
        assertNotNull(forShard7FF);
        assertEquals(CLUSTER_A_WALLET.toLowerCase(), forShard7FF.getClusterWallet().toLowerCase());
        
        // Shard 0x800 should map to Cluster B
        ClusterRegistration forShard800 = registry.getClusterForShard(0x800);
        assertNotNull(forShard800);
        assertEquals(CLUSTER_B_WALLET.toLowerCase(), forShard800.getClusterWallet().toLowerCase());
        
        // Shard 0xFFF should map to Cluster B
        ClusterRegistration forShardFFF = registry.getClusterForShard(0xFFF);
        assertNotNull(forShardFFF);
        assertEquals(CLUSTER_B_WALLET.toLowerCase(), forShardFFF.getClusterWallet().toLowerCase());
    }
    
    @Test
    public void testRemoteClusters() {
        List<ClusterRegistration> remoteFromA = registry.getRemoteClusters(CLUSTER_A_WALLET);
        assertEquals(1, remoteFromA.size());
        assertEquals(CLUSTER_B_WALLET.toLowerCase(), remoteFromA.get(0).getClusterWallet().toLowerCase());
        
        List<ClusterRegistration> remoteFromB = registry.getRemoteClusters(CLUSTER_B_WALLET);
        assertEquals(1, remoteFromB.size());
        assertEquals(CLUSTER_A_WALLET.toLowerCase(), remoteFromB.get(0).getClusterWallet().toLowerCase());
    }
    
    // ========== Shard ID Computation Tests ==========
    
    @Test
    public void testShardIdComputation() {
        // Shard IDs should be in valid range
        int shard1 = registry.computeShardId(CONTENT_OWNER_1);
        assertTrue("Shard ID should be <= 0xFFF", shard1 <= 0xFFF);
        assertTrue("Shard ID should be >= 0", shard1 >= 0);
        
        int shard2 = registry.computeShardId(CONTENT_OWNER_2);
        assertTrue("Shard ID should be <= 0xFFF", shard2 <= 0xFFF);
        
        int shard3 = registry.computeShardId(CONTENT_OWNER_3);
        assertTrue("Shard ID should be <= 0xFFF", shard3 <= 0xFFF);
    }
    
    @Test
    public void testShardIdDeterminism() {
        // Same wallet should always produce same shard
        int shard1a = registry.computeShardId(CONTENT_OWNER_1);
        int shard1b = registry.computeShardId(CONTENT_OWNER_1);
        assertEquals("Shard ID should be deterministic", shard1a, shard1b);
    }
    
    @Test
    public void testWalletToClusterMapping() {
        // Content owner should map to a cluster
        ClusterRegistration cluster = registry.getClusterForWallet(CONTENT_OWNER_1);
        assertNotNull("Content owner should map to a cluster", cluster);
        
        // Verify it's one of our clusters
        String wallet = cluster.getClusterWallet().toLowerCase();
        assertTrue("Should map to Cluster A or B",
            wallet.equals(CLUSTER_A_WALLET.toLowerCase()) ||
            wallet.equals(CLUSTER_B_WALLET.toLowerCase()));
    }
    
    // ========== Authority Checker Tests ==========
    
    @Test
    public void testAuthorityCheckerAuthorized() {
        // Create checker for Cluster A
        ShardAuthorityChecker checkerA = new ShardAuthorityChecker(registry, CLUSTER_A_WALLET);
        
        // Find a wallet that maps to Cluster A's range
        String walletInRangeA = findWalletInShardRange(0x000, 0x7FF);
        if (walletInRangeA != null) {
            ShardAuthorityChecker.AuthorityResult result = checkerA.checkAuthority(walletInRangeA);
            assertTrue("Should be authorized for wallet in range", result.isAuthorized());
        }
    }
    
    @Test
    public void testAuthorityCheckerRedirect() {
        // Create checker for Cluster A
        ShardAuthorityChecker checkerA = new ShardAuthorityChecker(registry, CLUSTER_A_WALLET);
        
        // Find a wallet that maps to Cluster B's range
        String walletInRangeB = findWalletInShardRange(0x800, 0xFFF);
        if (walletInRangeB != null) {
            ShardAuthorityChecker.AuthorityResult result = checkerA.checkAuthority(walletInRangeB);
            assertTrue("Should redirect for wallet in other range", result.isRedirect());
            assertNotNull("Should have redirect cluster", result.getRedirectCluster());
            assertEquals("Should redirect to Cluster B",
                CLUSTER_B_WALLET.toLowerCase(),
                result.getRedirectCluster().getClusterWallet().toLowerCase());
        }
    }
    
    @Test
    public void testShardRoutingException() {
        ClusterRegistration clusterB = registry.getCluster(CLUSTER_B_WALLET);
        
        ShardRoutingException ex = new ShardRoutingException(
            "Write must go to Cluster B",
            0x800,
            clusterB
        );
        
        assertTrue("Should have redirect info", ex.hasRedirectInfo());
        assertEquals(307, ex.getHttpStatusCode());
        assertEquals(CLUSTER_B_ENDPOINT, ex.getRedirectEndpoint());
    }
    
    @Test
    public void testShardRoutingExceptionUnclaimed() {
        ShardRoutingException ex = new ShardRoutingException(
            "Shard is unclaimed",
            0x500
        );
        
        assertFalse("Should not have redirect info", ex.hasRedirectInfo());
        assertEquals(503, ex.getHttpStatusCode());
    }
    
    // ========== Range Availability Tests ==========
    
    @Test
    public void testRangeAvailability() {
        // Claimed ranges should not be available
        assertFalse("Range 0x000-0x0FF should not be available",
            registry.isRangeAvailable(0x000, 0x0FF));
        
        assertFalse("Range 0x800-0x8FF should not be available",
            registry.isRangeAvailable(0x800, 0x8FF));
    }
    
    @Test
    public void testConflictingRegistration() {
        try {
            // Try to register overlapping range
            ClusterRegistration newCluster = StaticShardRegistry.createCluster(
                "0xNEWCLUSTER0000000000000000000000000000",
                0x400, 0x4FF,
                "http://new.example.com"
            );
            registry.addCluster(newCluster);
            fail("Should throw exception for overlapping range");
        } catch (IllegalArgumentException e) {
            // Expected
            assertTrue(e.getMessage().contains("already claimed"));
        }
    }
    
    // ========== Mount Path Tests ==========
    
    @Test
    public void testMountPaths() {
        ClusterRegistration clusterA = registry.getCluster(CLUSTER_A_WALLET);
        ClusterRegistration clusterB = registry.getCluster(CLUSTER_B_WALLET);
        
        assertEquals("/oak-chain/shard-000", clusterA.getMountPath());
        assertEquals("/oak-chain/shard-800", clusterB.getMountPath());
    }
    
    @Test
    public void testMountNames() {
        ClusterRegistration clusterA = registry.getCluster(CLUSTER_A_WALLET);
        ClusterRegistration clusterB = registry.getCluster(CLUSTER_B_WALLET);
        
        assertTrue("Mount name should start with cluster-",
            clusterA.getMountName().startsWith("cluster-"));
        assertTrue("Mount name should start with cluster-",
            clusterB.getMountName().startsWith("cluster-"));
    }
    
    // ========== Helper Methods ==========
    
    /**
     * Find a wallet address that hashes to a shard in the given range.
     * Uses brute force search with incrementing addresses.
     */
    private String findWalletInShardRange(int rangeStart, int rangeEnd) {
        // Try a bunch of addresses to find one in range
        for (int i = 0; i < 10000; i++) {
            String wallet = String.format("0x%040d", i);
            int shard = registry.computeShardId(wallet);
            if (shard >= rangeStart && shard <= rangeEnd) {
                return wallet;
            }
        }
        return null;
    }
}
