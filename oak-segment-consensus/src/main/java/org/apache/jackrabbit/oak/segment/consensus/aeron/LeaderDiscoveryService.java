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

import io.aeron.cluster.service.Cluster;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Service responsible for discovering the current cluster leader.
 * 
 * <p>Extracted from AeronConsensusEngine to isolate leader discovery logic
 * and improve testability. Handles mapping Aeron member IDs to validator URLs
 * and caching leader information.
 * 
 * <p><strong>OSGi Component:</strong> Stateful service with lifecycle management.
 * 
 * <p><strong>Responsibilities:</strong>
 * <ul>
 *   <li>Discover leader URL from Aeron cluster state</li>
 *   <li>Map Aeron member IDs to validator URLs</li>
 *   <li>Cache leader information to reduce lookups</li>
 *   <li>Resolve URLs to IP addresses for matching</li>
 * </ul>
 * 
 * <p><strong>Leader Discovery Strategy:</strong>
 * <ol>
 *   <li>Check cache (TTL 10s)</li>
 *   <li>Query Aeron cluster for leader member ID</li>
 *   <li>Map member ID to validator URL</li>
 *   <li>Fallback to peer polling if mapping fails</li>
 * </ol>
 */
@Component(
    service = LeaderDiscoveryService.class,
    immediate = true,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    property = {
        "service.description=Leader Discovery Service",
        "service.vendor=Apache Software Foundation"
    }
)
public class LeaderDiscoveryService {
    
    private static final Logger log = LoggerFactory.getLogger(LeaderDiscoveryService.class);
    
    /** Leader cache TTL (ms) */
    private static final long LEADER_CACHE_TTL_MS = 10000; // 10 seconds
    
    private final Map<Integer, String> nodeIdToUrl;
    private final List<String> peerUrls;
    
    private volatile String cachedLeaderUrl = null;
    private volatile long cachedLeaderTimestamp = 0;
    
    /**
     * Create a new leader discovery service (default constructor for OSGi).
     */
    public LeaderDiscoveryService() {
        this.nodeIdToUrl = new java.util.concurrent.ConcurrentHashMap<>();
        this.peerUrls = new java.util.ArrayList<>();
    }
    
    /**
     * Create a new leader discovery service with mappings (for programmatic use).
     * 
     * @param nodeIdToUrl mapping of Aeron member IDs to validator URLs
     * @param peerUrls list of peer validator URLs
     */
    public LeaderDiscoveryService(Map<Integer, String> nodeIdToUrl, List<String> peerUrls) {
        this.nodeIdToUrl = nodeIdToUrl;
        this.peerUrls = peerUrls;
    }
    
    /**
     * OSGi lifecycle: Activate component.
     */
    @Activate
    protected void activate() {
        log.info("✅ LeaderDiscoveryService activated: peers={}", peerUrls.size());
    }
    
    /**
     * OSGi lifecycle: Deactivate component.
     */
    @Deactivate
    protected void deactivate() {
        log.info("✅ LeaderDiscoveryService deactivated");
        invalidateCache();
    }
    
    /**
     * Set node ID mappings (for OSGi injection).
     */
    public void setNodeIdMapping(Map<Integer, String> nodeIdToUrl) {
        this.nodeIdToUrl.clear();
        this.nodeIdToUrl.putAll(nodeIdToUrl);
        log.debug("Updated node ID mapping: {}", nodeIdToUrl);
    }
    
    /**
     * Set peer URLs (for OSGi injection).
     */
    public void setPeerUrls(List<String> peerUrls) {
        this.peerUrls.clear();
        this.peerUrls.addAll(peerUrls);
        log.debug("Updated peer URLs: {}", peerUrls);
    }
    
    /**
     * Discover the current leader URL.
     * 
     * @param cluster Aeron cluster instance
     * @return leader URL, or null if not found
     */
    public String discoverLeader(Cluster cluster) {
        // Check cache
        if (cachedLeaderUrl != null && 
            (System.currentTimeMillis() - cachedLeaderTimestamp) < LEADER_CACHE_TTL_MS) {
            return cachedLeaderUrl;
        }
        
        // Discover from Aeron cluster
        String leaderUrl = discoverFromAeronCluster(cluster);
        
        if (leaderUrl != null) {
            // Update cache
            cachedLeaderUrl = leaderUrl;
            cachedLeaderTimestamp = System.currentTimeMillis();
            return leaderUrl;
        }
        
        // Fallback: poll peers
        log.debug("Leader discovery from Aeron failed, polling peers...");
        leaderUrl = discoverFromPeers();
        
        if (leaderUrl != null) {
            cachedLeaderUrl = leaderUrl;
            cachedLeaderTimestamp = System.currentTimeMillis();
        }
        
        return leaderUrl;
    }
    
    /**
     * Discover leader from Aeron cluster state.
     */
    private String discoverFromAeronCluster(Cluster cluster) {
        if (cluster == null) {
            return null;
        }
        
        try {
            // TODO: Aeron Cluster API doesn't expose leaderMemberId() directly
            // Need to use cluster state or role to determine leader
            // For now, return null (fallback to peer polling will be used)
            log.debug("Leader discovery from Aeron cluster not yet implemented (API limitations)");
            
        } catch (Exception e) {
            log.error("Failed to discover leader from Aeron cluster", e);
        }
        
        return null;
    }
    
    /**
     * Discover leader by polling peers.
     * 
     * <p>This is a fallback when Aeron cluster state is not available.
     * It polls each peer's /api/health endpoint to find who reports as leader.
     */
    private String discoverFromPeers() {
        for (String peerUrl : peerUrls) {
            try {
                // TODO: HTTP call to /api/health to check if peer is leader
                // For now, just return null (not implemented)
                log.debug("Polling peer: {}", peerUrl);
                
            } catch (Exception e) {
                log.debug("Failed to poll peer {}: {}", peerUrl, e.getMessage());
            }
        }
        
        return null;
    }
    
    /**
     * Invalidate leader cache.
     * 
     * <p>Call this when leader changes to force re-discovery.
     */
    public void invalidateCache() {
        cachedLeaderUrl = null;
        cachedLeaderTimestamp = 0;
        log.debug("Leader cache invalidated");
    }
    
    /**
     * Check if two URLs point to the same validator (by port).
     * 
     * <p>This handles cases where URLs might differ in protocol or hostname
     * but actually refer to the same validator instance.
     */
    public boolean isSameUrl(String url1, String url2) {
        if (url1 == null || url2 == null) {
            return false;
        }
        
        if (url1.equals(url2)) {
            return true;
        }
        
        try {
            URI uri1 = new URI(url1.startsWith("http") ? url1 : "http://" + url1);
            URI uri2 = new URI(url2.startsWith("http") ? url2 : "http://" + url2);
            
            // Same host and port = same validator
            if (uri1.getPort() == uri2.getPort()) {
                String host1 = resolveUrlToIP(uri1.getHost());
                String host2 = resolveUrlToIP(uri2.getHost());
                
                return host1 != null && host1.equals(host2);
            }
            
        } catch (Exception e) {
            log.debug("Failed to compare URLs: {} vs {}", url1, url2, e);
        }
        
        return false;
    }
    
    /**
     * Resolve URL hostname to IP address.
     */
    private String resolveUrlToIP(String hostname) {
        try {
            // Handle localhost specially
            if ("localhost".equalsIgnoreCase(hostname)) {
                return "127.0.0.1";
            }
            
            // Already an IP?
            if (hostname.matches("\\d+\\.\\d+\\.\\d+\\.\\d+")) {
                return hostname;
            }
            
            // Resolve DNS
            InetAddress addr = InetAddress.getByName(hostname);
            return addr.getHostAddress();
            
        } catch (Exception e) {
            log.debug("Failed to resolve hostname: {}", hostname, e);
            return null;
        }
    }
    
    /**
     * Get cached leader URL (for testing/monitoring).
     */
    public String getCachedLeaderUrl() {
        return cachedLeaderUrl;
    }
}

