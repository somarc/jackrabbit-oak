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
package org.apache.jackrabbit.oak.segment.consensus.contracts;

import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Credentials;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.tx.RawTransactionManager;
import org.web3j.tx.TransactionManager;
import org.web3j.tx.gas.DefaultGasProvider;
import org.web3j.tx.response.PollingTransactionReceiptProcessor;

import java.io.Closeable;
import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Java client for the PropagationPayment smart contract.
 * <p>
 * This client provides methods to:
 * <ul>
 *   <li>Calculate write costs (archival and ephemeral)</li>
 *   <li>Pay for write operations</li>
 *   <li>Publish ephemeral content with prepaid delete</li>
 *   <li>Claim propagation rewards</li>
 *   <li>Execute scheduled deletes for expired content</li>
 * </ul>
 * <p>
 * The contract implements the "Polymarket for content operations" model where
 * publishers lock in costs upfront, with propagation fees distributed across
 * all clusters in the network.
 */
public class PropagationPaymentClient implements Closeable {
    
    private static final Logger LOG = LoggerFactory.getLogger(PropagationPaymentClient.class);
    
    // Storage modes (must match Solidity enum)
    public static final int STORAGE_MODE_ARCHIVAL = 0;
    public static final int STORAGE_MODE_EPHEMERAL_PREPAID = 1;
    public static final int STORAGE_MODE_EPHEMERAL_ONDEMAND = 2;
    
    // Contract constants (must match Solidity)
    public static final BigInteger LOCAL_FEE_PER_BYTE = BigInteger.valueOf(10_000_000_000L); // 1e10
    public static final BigInteger PROPAGATION_FEE_PER_BYTE = BigInteger.valueOf(1_000_000_000L); // 1e9
    public static final BigInteger BASE_DELETE_FEE = BigInteger.valueOf(1_000_000_000_000_000L); // 0.001 ETH
    public static final BigInteger GROWTH_BUFFER_BPS = BigInteger.valueOf(2000); // 20%
    public static final BigInteger BPS_DENOMINATOR = BigInteger.valueOf(10000);
    
    private final Web3j web3j;
    private final String contractAddress;
    private final Credentials credentials;
    private final TransactionManager transactionManager;
    private final long chainId;
    
    /**
     * Create a PropagationPayment client for read-only operations.
     *
     * @param rpcUrl Ethereum RPC URL
     * @param contractAddress PropagationPayment contract address
     */
    public PropagationPaymentClient(@NotNull String rpcUrl, @NotNull String contractAddress) {
        this(rpcUrl, contractAddress, null, 1L);
    }
    
    /**
     * Create a PropagationPayment client with write capabilities.
     *
     * @param rpcUrl Ethereum RPC URL
     * @param contractAddress PropagationPayment contract address
     * @param credentials Wallet credentials for signing transactions
     * @param chainId Ethereum chain ID (1 for mainnet, 11155111 for Sepolia)
     */
    public PropagationPaymentClient(
            @NotNull String rpcUrl,
            @NotNull String contractAddress,
            Credentials credentials,
            long chainId) {
        this.web3j = Web3j.build(new HttpService(rpcUrl));
        this.contractAddress = contractAddress;
        this.credentials = credentials;
        this.chainId = chainId;
        
        if (credentials != null) {
            this.transactionManager = new RawTransactionManager(
                web3j,
                credentials,
                chainId,
                new PollingTransactionReceiptProcessor(web3j, 1000, 60)
            );
        } else {
            this.transactionManager = null;
        }
        
        LOG.info("PropagationPaymentClient initialized: contract={}, chainId={}", contractAddress, chainId);
    }
    
    // ═══════════════════════════════════════════════════════════════
    // COST CALCULATIONS (View Functions)
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Calculate total cost for archival content.
     *
     * @param sizeBytes Content size in bytes
     * @return Total cost in wei
     */
    public BigInteger calculateArchivalCost(long sizeBytes) throws Exception {
        Function function = new Function(
            "calculateArchivalCost",
            Collections.singletonList(new Uint256(sizeBytes)),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        
        List<Type> result = callFunction(function);
        return ((Uint256) result.get(0)).getValue();
    }
    
    /**
     * Calculate total cost for ephemeral prepaid content.
     *
     * @param sizeBytes Content size in bytes
     * @return Total cost in wei (includes prepaid delete fee with growth buffer)
     */
    public BigInteger calculateEphemeralPrepaidCost(long sizeBytes) throws Exception {
        Function function = new Function(
            "calculateEphemeralPrepaidCost",
            Collections.singletonList(new Uint256(sizeBytes)),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        
        List<Type> result = callFunction(function);
        return ((Uint256) result.get(0)).getValue();
    }
    
    /**
     * Calculate on-demand delete fee.
     *
     * @param clusterCount Number of clusters in network
     * @return Delete fee in wei
     */
    public BigInteger calculateOnDemandDeleteFee(int clusterCount) throws Exception {
        Function function = new Function(
            "calculateOnDemandDeleteFee",
            Collections.singletonList(new Uint256(clusterCount)),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        
        List<Type> result = callFunction(function);
        return ((Uint256) result.get(0)).getValue();
    }
    
    /**
     * Calculate prepaid delete fee with growth buffer.
     *
     * @param currentClusterCount Current number of clusters
     * @return Prepaid delete fee in wei
     */
    public BigInteger calculatePrepaidDeleteFee(int currentClusterCount) throws Exception {
        Function function = new Function(
            "calculatePrepaidDeleteFee",
            Collections.singletonList(new Uint256(currentClusterCount)),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        
        List<Type> result = callFunction(function);
        return ((Uint256) result.get(0)).getValue();
    }
    
    /**
     * Get current cluster count from ShardRegistry.
     *
     * @return Number of registered clusters
     */
    public int getClusterCount() throws Exception {
        Function function = new Function(
            "getClusterCount",
            Collections.emptyList(),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        
        List<Type> result = callFunction(function);
        return ((Uint256) result.get(0)).getValue().intValue();
    }
    
    /**
     * Get claimable rewards for a cluster.
     *
     * @param clusterWallet Cluster wallet address
     * @return Claimable rewards in wei
     */
    public BigInteger getClaimableRewards(String clusterWallet) throws Exception {
        Function function = new Function(
            "getClaimableRewards",
            Collections.singletonList(new Address(clusterWallet)),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        
        List<Type> result = callFunction(function);
        return ((Uint256) result.get(0)).getValue();
    }
    
    /**
     * Get escrow balance (prepaid delete fees held in contract).
     *
     * @return Escrow balance in wei
     */
    public BigInteger getEscrowBalance() throws Exception {
        Function function = new Function(
            "escrowBalance",
            Collections.emptyList(),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        
        List<Type> result = callFunction(function);
        return ((Uint256) result.get(0)).getValue();
    }
    
    /**
     * Get count of pending expirations.
     *
     * @return Number of ephemeral content items pending expiration
     */
    public int getPendingExpirationCount() throws Exception {
        Function function = new Function(
            "getPendingExpirationCount",
            Collections.emptyList(),
            Collections.singletonList(new TypeReference<Uint256>() {})
        );
        
        List<Type> result = callFunction(function);
        return ((Uint256) result.get(0)).getValue().intValue();
    }
    
    // ═══════════════════════════════════════════════════════════════
    // WRITE OPERATIONS (Require Credentials)
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Pay for a write operation.
     *
     * @param proposalId Unique proposal identifier (32 bytes)
     * @param sizeBytes Content size in bytes
     * @param storageMode Storage mode (ARCHIVAL, EPHEMERAL_PREPAID, EPHEMERAL_ONDEMAND)
     * @return Transaction receipt
     */
    public TransactionReceipt payForWrite(
            byte[] proposalId,
            long sizeBytes,
            int storageMode) throws Exception {
        requireCredentials();
        
        BigInteger cost;
        if (storageMode == STORAGE_MODE_ARCHIVAL || storageMode == STORAGE_MODE_EPHEMERAL_ONDEMAND) {
            cost = calculateArchivalCost(sizeBytes);
        } else {
            cost = calculateEphemeralPrepaidCost(sizeBytes);
        }
        
        Function function = new Function(
            "payForWrite",
            Arrays.asList(
                new Bytes32(proposalId),
                new Uint256(sizeBytes),
                new Uint8(storageMode)
            ),
            Collections.emptyList()
        );
        
        return sendTransaction(function, cost);
    }
    
    /**
     * Publish ephemeral content with prepaid delete.
     *
     * @param contentId Unique content identifier (32 bytes)
     * @param sizeBytes Content size in bytes
     * @param ttlDays Time-to-live in days (1-365)
     * @return Transaction receipt
     */
    public TransactionReceipt publishEphemeral(
            byte[] contentId,
            long sizeBytes,
            int ttlDays) throws Exception {
        requireCredentials();
        
        BigInteger cost = calculateEphemeralPrepaidCost(sizeBytes);
        
        Function function = new Function(
            "publishEphemeral",
            Arrays.asList(
                new Bytes32(contentId),
                new Uint256(sizeBytes),
                new Uint256(ttlDays)
            ),
            Collections.emptyList()
        );
        
        return sendTransaction(function, cost);
    }
    
    /**
     * Claim accumulated rewards for the connected wallet.
     *
     * @return Transaction receipt
     */
    public TransactionReceipt claimRewards() throws Exception {
        requireCredentials();
        
        Function function = new Function(
            "claimRewards",
            Collections.emptyList(),
            Collections.emptyList()
        );
        
        return sendTransaction(function, BigInteger.ZERO);
    }
    
    /**
     * Execute scheduled delete for expired ephemeral content.
     * Can be called by anyone (keeper pattern).
     *
     * @param contentId Content identifier to delete
     * @return Transaction receipt
     */
    public TransactionReceipt executeScheduledDelete(byte[] contentId) throws Exception {
        requireCredentials();
        
        Function function = new Function(
            "executeScheduledDelete",
            Collections.singletonList(new Bytes32(contentId)),
            Collections.emptyList()
        );
        
        return sendTransaction(function, BigInteger.ZERO);
    }
    
    /**
     * Pay for on-demand delete (expensive!).
     *
     * @param contentId Content identifier to delete
     * @return Transaction receipt
     */
    public TransactionReceipt payForDelete(byte[] contentId) throws Exception {
        requireCredentials();
        
        int clusterCount = getClusterCount();
        BigInteger deleteFee = calculateOnDemandDeleteFee(clusterCount);
        
        Function function = new Function(
            "payForDelete",
            Collections.singletonList(new Bytes32(contentId)),
            Collections.emptyList()
        );
        
        return sendTransaction(function, deleteFee);
    }
    
    // ═══════════════════════════════════════════════════════════════
    // CONTENT RECORD QUERIES
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Content record data structure.
     */
    public static class ContentRecord {
        public final String publisher;
        public final BigInteger sizeBytes;
        public final BigInteger publishedAt;
        public final int mode;
        public final BigInteger ttlExpiry;
        public final BigInteger prepaidDeleteFee;
        public final BigInteger clusterCountAtPublish;
        public final boolean deleted;
        
        public ContentRecord(
                String publisher,
                BigInteger sizeBytes,
                BigInteger publishedAt,
                int mode,
                BigInteger ttlExpiry,
                BigInteger prepaidDeleteFee,
                BigInteger clusterCountAtPublish,
                boolean deleted) {
            this.publisher = publisher;
            this.sizeBytes = sizeBytes;
            this.publishedAt = publishedAt;
            this.mode = mode;
            this.ttlExpiry = ttlExpiry;
            this.prepaidDeleteFee = prepaidDeleteFee;
            this.clusterCountAtPublish = clusterCountAtPublish;
            this.deleted = deleted;
        }
        
        public boolean isArchival() {
            return mode == STORAGE_MODE_ARCHIVAL;
        }
        
        public boolean isEphemeralPrepaid() {
            return mode == STORAGE_MODE_EPHEMERAL_PREPAID;
        }
        
        public boolean isExpired() {
            if (ttlExpiry.equals(BigInteger.ZERO)) {
                return false; // Archival never expires
            }
            return System.currentTimeMillis() / 1000 >= ttlExpiry.longValue();
        }
        
        @Override
        public String toString() {
            return String.format(
                "ContentRecord{publisher=%s, size=%d, mode=%d, deleted=%s, expired=%s}",
                publisher, sizeBytes, mode, deleted, isExpired()
            );
        }
    }
    
    /**
     * Get content record by ID.
     *
     * @param contentId Content identifier
     * @return Content record or null if not found
     */
    public ContentRecord getContentRecord(byte[] contentId) throws Exception {
        Function function = new Function(
            "getContentRecord",
            Collections.singletonList(new Bytes32(contentId)),
            Arrays.asList(
                new TypeReference<Address>() {},      // publisher
                new TypeReference<Uint256>() {},      // sizeBytes
                new TypeReference<Uint256>() {},      // publishedAt
                new TypeReference<Uint8>() {},        // mode
                new TypeReference<Uint256>() {},      // ttlExpiry
                new TypeReference<Uint256>() {},      // prepaidDeleteFee
                new TypeReference<Uint256>() {},      // clusterCountAtPublish
                new TypeReference<Bool>() {}          // deleted
            )
        );
        
        List<Type> result = callFunction(function);
        
        String publisher = ((Address) result.get(0)).getValue();
        if (publisher.equals("0x0000000000000000000000000000000000000000")) {
            return null; // Not found
        }
        
        return new ContentRecord(
            publisher,
            ((Uint256) result.get(1)).getValue(),
            ((Uint256) result.get(2)).getValue(),
            ((Uint8) result.get(3)).getValue().intValue(),
            ((Uint256) result.get(4)).getValue(),
            ((Uint256) result.get(5)).getValue(),
            ((Uint256) result.get(6)).getValue(),
            ((Bool) result.get(7)).getValue()
        );
    }
    
    // ═══════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ═══════════════════════════════════════════════════════════════
    
    /**
     * Calculate cost locally (without contract call).
     * Useful for quick estimates before submitting.
     *
     * @param sizeBytes Content size in bytes
     * @param clusterCount Number of clusters
     * @param storageMode Storage mode
     * @return Estimated cost in wei
     */
    public static BigInteger estimateCostLocally(long sizeBytes, int clusterCount, int storageMode) {
        BigInteger size = BigInteger.valueOf(sizeBytes);
        BigInteger clusters = BigInteger.valueOf(clusterCount);
        
        // Local fee + propagation fee
        BigInteger localFee = size.multiply(LOCAL_FEE_PER_BYTE);
        BigInteger propagationFee = size.multiply(PROPAGATION_FEE_PER_BYTE).multiply(clusters);
        BigInteger baseCost = localFee.add(propagationFee);
        
        if (storageMode == STORAGE_MODE_EPHEMERAL_PREPAID) {
            // Add prepaid delete fee with growth buffer
            BigInteger bufferedCount = clusters
                .multiply(BPS_DENOMINATOR.add(GROWTH_BUFFER_BPS))
                .divide(BPS_DENOMINATOR);
            BigInteger deleteFee = BASE_DELETE_FEE.multiply(bufferedCount);
            return baseCost.add(deleteFee);
        }
        
        return baseCost;
    }
    
    /**
     * Convert wei to ETH string for display.
     *
     * @param wei Amount in wei
     * @return Formatted ETH string
     */
    public static String weiToEthString(BigInteger wei) {
        BigInteger eth = wei.divide(BigInteger.valueOf(1_000_000_000_000_000_000L));
        BigInteger remainder = wei.mod(BigInteger.valueOf(1_000_000_000_000_000_000L));
        return String.format("%d.%018d ETH", eth, remainder);
    }
    
    private void requireCredentials() {
        if (credentials == null || transactionManager == null) {
            throw new IllegalStateException("Credentials required for write operations");
        }
    }
    
    private List<Type> callFunction(Function function) throws Exception {
        String encodedFunction = FunctionEncoder.encode(function);
        
        EthCall response = web3j.ethCall(
            Transaction.createEthCallTransaction(
                credentials != null ? credentials.getAddress() : "0x0000000000000000000000000000000000000000",
                contractAddress,
                encodedFunction
            ),
            DefaultBlockParameterName.LATEST
        ).send();
        
        if (response.hasError()) {
            throw new RuntimeException("Contract call failed: " + response.getError().getMessage());
        }
        
        return FunctionReturnDecoder.decode(response.getValue(), function.getOutputParameters());
    }
    
    private TransactionReceipt sendTransaction(Function function, BigInteger value) throws Exception {
        String encodedFunction = FunctionEncoder.encode(function);
        
        EthSendTransaction response = transactionManager.sendTransaction(
            DefaultGasProvider.GAS_PRICE,
            DefaultGasProvider.GAS_LIMIT,
            contractAddress,
            encodedFunction,
            value
        );
        
        if (response.hasError()) {
            throw new RuntimeException("Transaction failed: " + response.getError().getMessage());
        }
        
        // Wait for receipt
        return new PollingTransactionReceiptProcessor(web3j, 1000, 60)
            .waitForTransactionReceipt(response.getTransactionHash());
    }
    
    @Override
    public void close() throws IOException {
        web3j.shutdown();
        LOG.info("PropagationPaymentClient closed");
    }
}
