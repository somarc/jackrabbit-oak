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
package org.apache.jackrabbit.oak.segment.consensus.registry;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.DynamicArray;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.Utf8String;
import org.web3j.abi.datatypes.generated.Uint16;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * On-chain implementation of ShardRegistry that reads from the Ethereum L1 contract.
 * <p>
 * This implementation:
 * <ul>
 *   <li>Connects to Ethereum via Web3j</li>
 *   <li>Reads cluster registrations from ShardRegistry.sol</li>
 *   <li>Caches results with configurable TTL</li>
 *   <li>Supports both mainnet and Sepolia testnet</li>
 * </ul>
 */
public class OnChainShardRegistry implements ShardRegistry {
    
    private static final Logger log = LoggerFactory.getLogger(OnChainShardRegistry.class);
    
    private static final long DEFAULT_CACHE_TTL_MS = TimeUnit.MINUTES.toMillis(5);
    
    private final Web3j web3j;
    private final String contractAddress;
    private final long cacheTtlMs;
    
    // Cache
    private volatile List<ClusterRegistration> cachedClusters;
    private volatile long cacheTimestamp;
    private final Map<Integer, ClusterRegistration> shardToClusterCache = new ConcurrentHashMap<>();
    
    /**
     * Create an on-chain shard registry.
     *
     * @param rpcUrl Ethereum RPC URL (e.g., "https://mainnet.infura.io/v3/YOUR_KEY")
     * @param contractAddress ShardRegistry contract address
     */
    public OnChainShardRegistry(@NotNull String rpcUrl, @NotNull String contractAddress) {
        this(rpcUrl, contractAddress, DEFAULT_CACHE_TTL_MS);
    }
    
    /**
     * Create an on-chain shard registry with custom cache TTL.
     *
     * @param rpcUrl Ethereum RPC URL
     * @param contractAddress ShardRegistry contract address
     * @param cacheTtlMs Cache time-to-live in milliseconds
     */
    public OnChainShardRegistry(@NotNull String rpcUrl, @NotNull String contractAddress, long cacheTtlMs) {
        this.web3j = Web3j.build(new HttpService(rpcUrl));
        this.contractAddress = contractAddress;
        this.cacheTtlMs = cacheTtlMs;
        
        log.info("✅ OnChainShardRegistry initialized");
        log.info("   Contract: {}", contractAddress);
        log.info("   RPC: {}", rpcUrl.replaceAll("v3/[^/]+", "v3/***")); // Hide API key
        log.info("   Cache TTL: {}ms", cacheTtlMs);
    }
    
    /**
     * Create an on-chain shard registry with existing Web3j instance.
     *
     * @param web3j Web3j instance
     * @param contractAddress ShardRegistry contract address
     */
    public OnChainShardRegistry(@NotNull Web3j web3j, @NotNull String contractAddress) {
        this.web3j = web3j;
        this.contractAddress = contractAddress;
        this.cacheTtlMs = DEFAULT_CACHE_TTL_MS;
    }
    
    @Override
    @NotNull
    public List<ClusterRegistration> getAllClusters() {
        ensureCacheValid();
        return cachedClusters != null ? cachedClusters : Collections.emptyList();
    }
    
    @Override
    @NotNull
    public List<ClusterRegistration> getActiveClusters() {
        return getAllClusters().stream()
            .filter(ClusterRegistration::isActive)
            .collect(Collectors.toList());
    }
    
    @Override
    @Nullable
    public ClusterRegistration getCluster(@NotNull String clusterWallet) {
        return getAllClusters().stream()
            .filter(c -> c.getClusterWallet().equalsIgnoreCase(clusterWallet))
            .findFirst()
            .orElse(null);
    }
    
    @Override
    @Nullable
    public ClusterRegistration getClusterForShard(int shardId) {
        // Check cache first
        ClusterRegistration cached = shardToClusterCache.get(shardId);
        if (cached != null) {
            return cached;
        }
        
        // Search through clusters
        for (ClusterRegistration cluster : getAllClusters()) {
            if (cluster.containsShard(shardId)) {
                shardToClusterCache.put(shardId, cluster);
                return cluster;
            }
        }
        
        return null;
    }
    
    @Override
    @Nullable
    public ClusterRegistration getClusterForWallet(@NotNull String contentOwnerWallet) {
        int shardId = computeShardId(contentOwnerWallet);
        return getClusterForShard(shardId);
    }
    
    @Override
    public int computeShardId(@NotNull String wallet) {
        // Same algorithm as Solidity: first 12 bits of keccak256(wallet)
        byte[] hash = Hash.sha3(Numeric.hexStringToByteArray(wallet));
        // Take first 2 bytes and mask to 12 bits
        int value = ((hash[0] & 0xFF) << 8) | (hash[1] & 0xFF);
        return value >> 4; // Shift right 4 bits to get 12 bits
    }
    
    @Override
    public boolean isRangeAvailable(int shardRangeStart, int shardRangeEnd) {
        for (int shard = shardRangeStart; shard <= shardRangeEnd; shard++) {
            if (getClusterForShard(shard) != null) {
                return false;
            }
        }
        return true;
    }
    
    @Override
    public int getClusterCount() {
        return getAllClusters().size();
    }
    
    @Override
    public void refresh() {
        log.info("🔄 Refreshing shard registry from chain...");
        cacheTimestamp = 0; // Invalidate cache
        shardToClusterCache.clear();
        ensureCacheValid();
    }
    
    /**
     * Ensure the cache is valid, refreshing if necessary.
     */
    private synchronized void ensureCacheValid() {
        long now = System.currentTimeMillis();
        if (cachedClusters != null && (now - cacheTimestamp) < cacheTtlMs) {
            return; // Cache is still valid
        }
        
        try {
            cachedClusters = fetchAllClustersFromChain();
            cacheTimestamp = now;
            log.info("✅ Loaded {} clusters from chain", cachedClusters.size());
        } catch (Exception e) {
            log.error("❌ Failed to fetch clusters from chain: {}", e.getMessage(), e);
            if (cachedClusters == null) {
                cachedClusters = Collections.emptyList();
            }
            // Keep stale cache on error
        }
    }
    
    /**
     * Fetch all clusters from the blockchain.
     */
    private List<ClusterRegistration> fetchAllClustersFromChain() throws IOException {
        // Call getAllClusters() on the contract
        Function function = new Function(
            "getAllClusters",
            Collections.emptyList(),
            Collections.singletonList(new TypeReference<DynamicArray<Address>>() {})
        );
        
        String encodedFunction = FunctionEncoder.encode(function);
        EthCall response = web3j.ethCall(
            Transaction.createEthCallTransaction(null, contractAddress, encodedFunction),
            DefaultBlockParameterName.LATEST
        ).send();
        
        if (response.hasError()) {
            throw new IOException("Contract call failed: " + response.getError().getMessage());
        }
        
        List<Type> result = FunctionReturnDecoder.decode(
            response.getValue(),
            function.getOutputParameters()
        );
        
        if (result.isEmpty()) {
            return Collections.emptyList();
        }
        
        @SuppressWarnings("unchecked")
        List<Address> addresses = ((DynamicArray<Address>) result.get(0)).getValue();
        
        // Fetch details for each cluster
        List<ClusterRegistration> clusters = new ArrayList<>();
        for (Address addr : addresses) {
            try {
                ClusterRegistration reg = fetchClusterInfo(addr.getValue());
                if (reg != null) {
                    clusters.add(reg);
                }
            } catch (Exception e) {
                log.warn("Failed to fetch cluster info for {}: {}", addr.getValue(), e.getMessage());
            }
        }
        
        return clusters;
    }
    
    /**
     * Fetch cluster info for a specific wallet address.
     */
    @Nullable
    private ClusterRegistration fetchClusterInfo(String clusterWallet) throws IOException {
        // Call getClusterInfo(address) on the contract
        Function function = new Function(
            "getClusterInfo",
            Collections.singletonList(new Address(clusterWallet)),
            Arrays.asList(
                // Returns tuple: (ClusterRegistration, string endpoint)
                // ClusterRegistration: (address, uint16, uint16, uint256, bool)
                new TypeReference<Address>() {},      // clusterWallet
                new TypeReference<Uint16>() {},       // shardRangeStart
                new TypeReference<Uint16>() {},       // shardRangeEnd
                new TypeReference<Uint256>() {},      // registeredAt
                new TypeReference<Bool>() {},         // active
                new TypeReference<Utf8String>() {}    // endpoint
            )
        );
        
        String encodedFunction = FunctionEncoder.encode(function);
        EthCall response = web3j.ethCall(
            Transaction.createEthCallTransaction(null, contractAddress, encodedFunction),
            DefaultBlockParameterName.LATEST
        ).send();
        
        if (response.hasError()) {
            throw new IOException("Contract call failed: " + response.getError().getMessage());
        }
        
        List<Type> result = FunctionReturnDecoder.decode(
            response.getValue(),
            function.getOutputParameters()
        );
        
        if (result.size() < 6) {
            return null;
        }
        
        String wallet = ((Address) result.get(0)).getValue();
        if (wallet.equals("0x0000000000000000000000000000000000000000")) {
            return null; // Not registered
        }
        
        int shardStart = ((Uint16) result.get(1)).getValue().intValue();
        int shardEnd = ((Uint16) result.get(2)).getValue().intValue();
        BigInteger registeredAt = ((Uint256) result.get(3)).getValue();
        boolean active = ((Bool) result.get(4)).getValue();
        String endpoint = ((Utf8String) result.get(5)).getValue();
        
        return new ClusterRegistration(
            wallet,
            shardStart,
            shardEnd,
            registeredAt,
            active,
            endpoint.isEmpty() ? null : endpoint
        );
    }
    
    /**
     * Get the contract address.
     *
     * @return Contract address
     */
    @NotNull
    public String getContractAddress() {
        return contractAddress;
    }
    
    /**
     * Close the Web3j connection.
     */
    public void close() {
        web3j.shutdown();
    }
}
