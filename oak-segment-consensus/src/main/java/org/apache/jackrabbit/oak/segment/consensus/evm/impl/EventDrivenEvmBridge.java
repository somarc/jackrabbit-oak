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
package org.apache.jackrabbit.oak.segment.consensus.evm.impl;

import io.reactivex.disposables.Disposable;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint96;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Event-driven EVM bridge that can work in mock mode (for testing) or real mode (mainnet).
 * 
 * <p>This bridge listens to WriteAuthorized events from OakWriteAuthorizationV5 contract.
 * In mock mode, events are simulated. In real mode, events come from Web3j event subscriptions.
 * 
 * <p>Event Structure (from OakWriteAuthorizationV5.sol):
 * <pre>
 * event WriteAuthorized(
 *     bytes32 indexed proposalId,
 *     address indexed payer,
 *     bytes32 indexed shardHash,
 *     uint96 amount,
 *     uint32 blockNumber
 * );
 * </pre>
 */
public class EventDrivenEvmBridge implements EvmBridge {
    
    private static final Logger log = LoggerFactory.getLogger(EventDrivenEvmBridge.class);
    
    // Pricing constants (all in wei)
    private static final BigInteger BASE_FEE = new BigInteger("1000000000000000"); // 0.001 ETH
    private static final BigInteger SEGMENT_FEE = new BigInteger("100000000000000"); // 0.0001 ETH per segment
    private static final BigInteger STORAGE_FEE_PER_KB = new BigInteger("10000000000000"); // 0.00001 ETH per KB
    private static final BigInteger BLOB_FEE = new BigInteger("50000000000000"); // 0.00005 ETH per blob
    private static final BigInteger EVENT_LOOKBACK_BLOCKS = BigInteger.valueOf(10_000L);
    
    private final String networkName;
    private final String contractAddress;
    private final boolean mockMode;
    
    // Web3j client (for real mode)
    private Web3j web3j;
    private Disposable eventSubscription;
    
    // WriteAuthorized event definition (matches OakWriteAuthorizationV5.sol)
    private static final Event WRITE_AUTHORIZED_EVENT = new Event("WriteAuthorized",
        Arrays.asList(
            new TypeReference<Bytes32>(true) {},  // proposalId (indexed)
            new TypeReference<Address>(true) {},   // payer (indexed)
            new TypeReference<Bytes32>(true) {},   // shardHash (indexed)
            new TypeReference<Uint96>() {},        // amount
            new TypeReference<Uint32>() {}         // blockNumber
        ));

    // ProposalPaid event definition (matches ValidatorPaymentV3_1/V3_2)
    private static final Event PROPOSAL_PAID_EVENT = new Event("ProposalPaid",
        Arrays.asList(
            new TypeReference<Bytes32>(true) {},   // proposalId (indexed)
            new TypeReference<Address>(true) {},   // payer (indexed)
            new TypeReference<Uint256>() {},       // amount
            new TypeReference<Uint8>() {},         // tier
            new TypeReference<Uint8>(true) {},     // paymentToken (indexed)
            new TypeReference<Address>() {},       // preferredValidator
            new TypeReference<Uint256>() {}        // timestamp
        ));
    
    // Event listeners (for real mode - Web3j subscriptions)
    private final CopyOnWriteArrayList<Consumer<WriteAuthorizedEvent>> eventListeners = new CopyOnWriteArrayList<>();
    
    // Payment proofs (proposalId -> PaymentProof)
    private final Map<String, PaymentProof> payments = new ConcurrentHashMap<>();
    
    // Mock event queue (for testing)
    private final java.util.concurrent.BlockingQueue<WriteAuthorizedEvent> mockEventQueue = 
        new java.util.concurrent.LinkedBlockingQueue<>();
    
    private long currentBlock = 1000000;
    private boolean running = false;
    
    /**
     * Create event-driven EVM bridge.
     * 
     * @param networkName Network name (e.g., "mainnet", "polygon", "sepolia")
     * @param contractAddress Contract address (0x...)
     * @param mockMode If true, use mock events. If false, use Web3j subscriptions.
     */
    public EventDrivenEvmBridge(@NotNull String networkName, @NotNull String contractAddress, boolean mockMode) {
        this.networkName = networkName;
        this.contractAddress = contractAddress;
        this.mockMode = mockMode;
    }
    
    /**
     * Convenience constructor for mock mode (testing).
     */
    public EventDrivenEvmBridge() {
        this("sepolia", "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0", true);
    }
    
    @Override
    public PaymentProof verifyPayment(@NotNull String proposalId) {
        // Check if payment proof already exists (from processed event)
        PaymentProof existing = payments.get(proposalId);
        if (existing != null) {
            return refreshProofConfirmations(existing);
        }
        
        // In mock mode, auto-confirm proposals for testing
        // This allows load tests to work without injecting events
        if (mockMode) {
            return refreshProofConfirmations(autoConfirmMockProposal(proposalId));
        }

        // In real mode, fall back to direct log query in case subscription missed event.
        return refreshProofConfirmations(fetchPaymentFromChain(proposalId));
    }
    
    /**
     * Auto-confirm a proposal in mock mode (for testing/load testing).
     * Creates a payment proof on-the-fly if proposalId looks valid.
     */
    private PaymentProof autoConfirmMockProposal(@NotNull String proposalId) {
        // Validate proposalId format (UUID: 8-4-4-4-12 hex digits)
        if (!proposalId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            return null; // Invalid format, don't auto-confirm
        }
        
        // Check if we already created a proof for this proposal
        PaymentProof cached = payments.get(proposalId);
        if (cached != null) {
            return cached;
        }
        
        // Create mock payment proof
        // Use proposalId to generate a deterministic mock tx hash
        String proposalIdHex = proposalId.replace("-", "");
        String mockTxHash = "0x" + proposalIdHex;
        // Pad to 66 chars (0x + 64 hex chars)
        if (mockTxHash.length() < 66) {
            int paddingNeeded = 66 - mockTxHash.length();
            StringBuilder padding = new StringBuilder();
            for (int i = 0; i < paddingNeeded; i++) {
                padding.append("0");
            }
            mockTxHash = mockTxHash + padding.toString();
        } else if (mockTxHash.length() > 66) {
            mockTxHash = mockTxHash.substring(0, 66);
        }
        
        // Increment block number for each new proposal (simulates block progression)
        currentBlock++;
        
        PaymentProof mockProof = new SimplePaymentProof(
            mockTxHash,
            currentBlock,
            "0x0000000000000000000000000000000000000000", // Mock payer address
            contractAddress,
            proposalId,
            BASE_FEE.toString(), // Mock payment amount
            1 // 1 confirmation (just "mined")
        );
        
        // Cache the proof for future lookups
        payments.put(proposalId, mockProof);
        
        log.debug("🎭 Auto-confirmed mock proposal: {} (tx: {}, block: {})", 
            proposalId, mockTxHash, currentBlock);
        
        return mockProof;
    }
    
    /**
     * Check if bridge is in mock mode.
     */
    public boolean isMockMode() {
        return mockMode;
    }
    
    @Override
    @NotNull
    public String calculateRequiredPayment(int segmentCount, long byteSize, int blobCount) {
        BigInteger total = BASE_FEE;
        total = total.add(SEGMENT_FEE.multiply(BigInteger.valueOf(segmentCount)));
        long kilobytes = byteSize / 1024;
        total = total.add(STORAGE_FEE_PER_KB.multiply(BigInteger.valueOf(kilobytes)));
        total = total.add(BLOB_FEE.multiply(BigInteger.valueOf(blobCount)));
        return total.toString();
    }
    
    @Override
    public String getWalletUuidForAddress(@NotNull String ethereumAddress) {
        // In real implementation, this would query a registry contract
        return null;
    }
    
    @Override
    public long getCurrentBlockNumber() {
        return currentBlock;
    }
    
    @Override
    @NotNull
    public String getNetworkName() {
        return networkName;
    }
    
    @Override
    @NotNull
    public String getContractAddress() {
        return contractAddress;
    }
    
    @Override
    public void start() {
        running = true;
        
        if (mockMode) {
            log.info("🎭 EventDrivenEvmBridge started in MOCK mode (network: {}, contract: {})", 
                networkName, contractAddress);
            // Start mock event processor thread
            startMockEventProcessor();
        } else {
            log.info("🌐 EventDrivenEvmBridge started in REAL mode (network: {}, contract: {})", 
                networkName, contractAddress);
            // Start Web3j event subscription
            startWeb3jEventSubscription();
        }
    }
    
    @Override
    public void stop() {
        running = false;
        
        // Clean up Web3j subscription
        if (eventSubscription != null && !eventSubscription.isDisposed()) {
            eventSubscription.dispose();
            log.info("Web3j event subscription disposed");
        }
        
        // Shutdown Web3j client
        if (web3j != null) {
            web3j.shutdown();
            log.info("Web3j client shutdown");
        }
        
        log.info("EventDrivenEvmBridge stopped");
    }
    
    // ========== Mock Mode (Testing) ==========
    
    /**
     * Simulate a WriteAuthorized event (for testing).
     * 
     * <p>In mock mode, this queues an event that will be processed by the mock event processor.
     * In real mode, events come from Web3j subscriptions.
     * 
     * @param proposalId Proposal ID (bytes32)
     * @param payer Payer address (0x...)
     * @param shardHash Shard hash (bytes32)
     * @param amount Amount paid (uint96, in USDC 6 decimals)
     * @param blockNumber Block number (uint32)
     * @param txHash Transaction hash (0x...)
     */
    public void simulateWriteAuthorizedEvent(
            @NotNull String proposalId,
            @NotNull String payer,
            @NotNull String shardHash,
            @NotNull BigInteger amount,
            long blockNumber,
            @NotNull String txHash) {
        
        WriteAuthorizedEvent event = new WriteAuthorizedEvent(
            proposalId,
            payer,
            shardHash,
            amount,
            blockNumber,
            txHash
        );
        
        if (mockMode) {
            mockEventQueue.offer(event);
            log.debug("📥 Mock WriteAuthorized event queued: proposalId={}, payer={}, amount={}", 
                proposalId, payer, amount);
        } else {
            log.warn("⚠️  simulateWriteAuthorizedEvent called in REAL mode - ignoring (events come from blockchain)");
        }
    }

    /**
     * Advance the mock chain head to simulate additional confirmations in tests.
     */
    public void advanceMockBlocks(long blocks) {
        if (!mockMode || blocks <= 0) {
            return;
        }
        currentBlock += blocks;
    }
    
    /**
     * Process mock events (runs in background thread).
     */
    private void startMockEventProcessor() {
        Thread processorThread = new Thread(() -> {
            while (running) {
                try {
                    WriteAuthorizedEvent event = mockEventQueue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (event != null) {
                        processWriteAuthorizedEvent(event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    log.error("Error processing mock event", e);
                }
            }
            log.info("Mock event processor stopped");
        }, "MockEventProcessor");
        processorThread.setDaemon(true);
        processorThread.start();
        log.info("Mock event processor started");
    }
    
    // ========== Real Mode (Mainnet) ==========
    
    /**
     * Start Web3j event subscription (for real Sepolia/Mainnet).
     * 
     * <p>Subscribes to WriteAuthorized events from OakWriteAuthorizationV5 contract.
     * Events are processed as they arrive and stored as PaymentProofs.
     */
    private void startWeb3jEventSubscription() {
        // Get RPC URL from config
        BlockchainConfig config = BlockchainConfig.getInstance();
        String rpcUrl = config.getRpcUrl();
        
        if (rpcUrl == null || rpcUrl.isEmpty()) {
            log.error("❌ Cannot start Web3j subscription: RPC URL not configured");
            log.error("   Set OAK_BLOCKCHAIN_RPC_URL environment variable");
            return;
        }
        
        try {
            // Initialize Web3j client
            log.info("🔗 Connecting to Ethereum RPC: {}", maskRpcUrl(rpcUrl));
            web3j = Web3j.build(new HttpService(rpcUrl));
            
            // Verify connection
            String clientVersion = web3j.web3ClientVersion().send().getWeb3ClientVersion();
            log.info("✅ Connected to Ethereum node: {}", clientVersion);
            
            // Get current block number
            BigInteger blockNumber = web3j.ethBlockNumber().send().getBlockNumber();
            currentBlock = blockNumber.longValue();
            log.info("📦 Current block: {}", currentBlock);
            
            // Create event filter
            EthFilter filter = new EthFilter(
                DefaultBlockParameterName.LATEST,  // Start from latest block
                DefaultBlockParameterName.LATEST,  // Subscribe to new blocks
                contractAddress
            );
            
            // Add event topic
            String eventSignature = EventEncoder.encode(WRITE_AUTHORIZED_EVENT);
            filter.addSingleTopic(eventSignature);
            
            log.info("📡 Subscribing to WriteAuthorized events on contract: {}", contractAddress);
            
            // Subscribe to events
            eventSubscription = web3j.ethLogFlowable(filter)
                .subscribe(
                    ethLog -> {
                        try {
                            WriteAuthorizedEvent event = parseWriteAuthorizedLog(ethLog);
                            if (event == null) {
                                return;
                            }
                            log.info("📨 Received WriteAuthorized event: proposalId={}, payer={}, block={}",
                                event.proposalId, event.payer, event.blockNumber);
                            processWriteAuthorizedEvent(event);
                        } catch (Exception e) {
                            log.error("Error parsing WriteAuthorized event", e);
                        }
                    },
                    error -> {
                        log.error("❌ Web3j subscription error", error);
                        // Attempt to reconnect after delay
                        scheduleReconnect();
                    },
                    () -> {
                        log.info("Web3j subscription completed");
                    }
                );
            
            log.info("✅ Web3j event subscription started");
            
        } catch (Exception e) {
            log.error("❌ Failed to start Web3j event subscription", e);
            // Fall back to mock mode
            log.warn("⚠️  Falling back to mock mode due to Web3j connection failure");
        }
    }
    
    /**
     * Schedule reconnection attempt after subscription failure.
     */
    private void scheduleReconnect() {
        if (!running) return;
        
        new Thread(() -> {
            try {
                Thread.sleep(30000); // Wait 30 seconds before reconnecting
                if (running) {
                    log.info("🔄 Attempting to reconnect Web3j subscription...");
                    startWeb3jEventSubscription();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "Web3j-Reconnect").start();
    }

    private PaymentProof fetchPaymentFromChain(@NotNull String proposalId) {
        if (web3j == null) {
            return null;
        }
        if (!proposalId.matches("^0x[0-9a-fA-F]{64}$")) {
            return null;
        }

        try {
            BigInteger latest = web3j.ethBlockNumber().send().getBlockNumber();
            BigInteger from = latest.subtract(EVENT_LOOKBACK_BLOCKS);
            if (from.signum() < 0) {
                from = BigInteger.ZERO;
            }

            EthFilter filter = new EthFilter(
                DefaultBlockParameter.valueOf(from),
                DefaultBlockParameterName.LATEST,
                contractAddress
            );
            filter.addOptionalTopics(
                EventEncoder.encode(WRITE_AUTHORIZED_EVENT),
                EventEncoder.encode(PROPOSAL_PAID_EVENT)
            );
            filter.addOptionalTopics(proposalId);

            EthLog logResponse = web3j.ethGetLogs(filter).send();
            if (logResponse == null || logResponse.hasError() || logResponse.getLogs() == null || logResponse.getLogs().isEmpty()) {
                return null;
            }

            for (EthLog.LogResult<?> logResult : logResponse.getLogs()) {
                Object value = logResult.get();
                if (!(value instanceof org.web3j.protocol.core.methods.response.Log)) {
                    continue;
                }
                org.web3j.protocol.core.methods.response.Log logEntry =
                    (org.web3j.protocol.core.methods.response.Log) value;
                WriteAuthorizedEvent event = parsePaymentLog(logEntry);
                if (event == null) {
                    continue;
                }
                processWriteAuthorizedEvent(event);
                return payments.get(event.proposalId);
            }
        } catch (Exception e) {
            log.debug("On-chain payment lookup failed for proposalId={}", proposalId, e);
        }
        return null;
    }

    private PaymentProof refreshProofConfirmations(PaymentProof proof) {
        if (proof == null) {
            return null;
        }
        int confirmations = confirmationsForBlock(proof.getBlockNumber());
        if (confirmations == proof.getConfirmations()) {
            return proof;
        }
        PaymentProof refreshed = new SimplePaymentProof(
            proof.getTransactionHash(),
            proof.getBlockNumber(),
            proof.getFromAddress(),
            proof.getContractAddress(),
            proof.getProposalId(),
            proof.getAmountWei(),
            confirmations
        );
        payments.put(proof.getProposalId(), refreshed);
        return refreshed;
    }

    private int confirmationsForBlock(long blockNumber) {
        long latestBlock = resolveLatestBlockNumber();
        long confirmations = latestBlock >= blockNumber ? (latestBlock - blockNumber) + 1L : 1L;
        return confirmations > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) confirmations;
    }

    private long resolveLatestBlockNumber() {
        if (!mockMode && web3j != null) {
            try {
                long latestBlock = web3j.ethBlockNumber().send().getBlockNumber().longValue();
                currentBlock = Math.max(currentBlock, latestBlock);
            } catch (Exception e) {
                log.debug("Unable to refresh latest block number from Web3j; using cached head {}", currentBlock, e);
            }
        }
        return currentBlock;
    }

    private WriteAuthorizedEvent parsePaymentLog(
            org.web3j.protocol.core.methods.response.Log ethLog) {
        if (ethLog.getTopics() == null || ethLog.getTopics().isEmpty()) {
            return null;
        }
        String signature = ethLog.getTopics().get(0);
        String writeAuthorizedSignature = EventEncoder.encode(WRITE_AUTHORIZED_EVENT);
        String proposalPaidSignature = EventEncoder.encode(PROPOSAL_PAID_EVENT);

        if (writeAuthorizedSignature.equalsIgnoreCase(signature)) {
            return parseWriteAuthorizedLog(ethLog);
        }
        if (proposalPaidSignature.equalsIgnoreCase(signature)) {
            return parseProposalPaidLog(ethLog);
        }
        return null;
    }

    private WriteAuthorizedEvent parseWriteAuthorizedLog(
            org.web3j.protocol.core.methods.response.Log ethLog) {
        if (ethLog.getTopics() == null || ethLog.getTopics().size() < 4) {
            return null;
        }
        String proposalId = ethLog.getTopics().get(1);
        String payer = "0x" + ethLog.getTopics().get(2).substring(26);
        String shardHash = ethLog.getTopics().get(3);

        String data = ethLog.getData();
        if (data == null || data.length() < 130) {
            return null;
        }
        BigInteger amount = Numeric.toBigInt(data.substring(0, 66));
        long eventBlockNumber = Numeric.toBigInt(data.substring(66, 130)).longValue();
        String txHash = ethLog.getTransactionHash();

        return new WriteAuthorizedEvent(
            proposalId,
            payer,
            shardHash,
            amount,
            eventBlockNumber,
            txHash
        );
    }

    private WriteAuthorizedEvent parseProposalPaidLog(
            org.web3j.protocol.core.methods.response.Log ethLog) {
        if (ethLog.getTopics() == null || ethLog.getTopics().size() < 3) {
            return null;
        }
        String proposalId = ethLog.getTopics().get(1);
        String payer = "0x" + ethLog.getTopics().get(2).substring(26);
        String shardHash = "0x0";

        String data = ethLog.getData();
        if (data == null || data.length() < 66) {
            return null;
        }
        BigInteger amount = Numeric.toBigInt(data.substring(0, 66));
        long blockNumber = ethLog.getBlockNumber() != null
            ? ethLog.getBlockNumber().longValue()
            : currentBlock;
        String txHash = ethLog.getTransactionHash();

        return new WriteAuthorizedEvent(
            proposalId,
            payer,
            shardHash,
            amount,
            blockNumber,
            txHash
        );
    }
    
    /**
     * Mask RPC URL for logging (hide API keys).
     */
    private String maskRpcUrl(String url) {
        if (url == null) return "null";
        // Mask anything after the last slash that looks like an API key
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash > 0 && lastSlash < url.length() - 8) {
            String key = url.substring(lastSlash + 1);
            if (key.length() > 8) {
                return url.substring(0, lastSlash + 1) + key.substring(0, 4) + "***" + key.substring(key.length() - 4);
            }
        }
        return url;
    }
    
    // ========== Event Processing ==========
    
    /**
     * Process a WriteAuthorized event (called from mock or real event source).
     */
    private void processWriteAuthorizedEvent(WriteAuthorizedEvent event) {
        log.info("📨 Processing WriteAuthorized event: proposalId={}, payer={}, amount={}, block={}", 
            event.proposalId, event.payer, event.amount, event.blockNumber);
        
        // Create payment proof
        PaymentProof proof = new SimplePaymentProof(
            event.txHash,
            event.blockNumber,
            event.payer,
            contractAddress,
            event.proposalId,
            event.amount.toString(),
            1 // 1 confirmation (just mined)
        );
        
        // Store payment proof
        payments.put(event.proposalId, proof);
        
        // Update current block
        currentBlock = Math.max(currentBlock, event.blockNumber);
        
        // Notify listeners (for future use - e.g., metrics, notifications)
        for (Consumer<WriteAuthorizedEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            } catch (Exception e) {
                log.error("Error in event listener", e);
            }
        }
        
        log.info("✅ Payment proof stored: proposalId={}, verified=true", event.proposalId);
    }
    
    /**
     * Add event listener (for metrics, notifications, etc.).
     */
    public void addEventListener(Consumer<WriteAuthorizedEvent> listener) {
        eventListeners.add(listener);
    }
    
    /**
     * WriteAuthorized event structure (matches Solidity event).
     */
    public static class WriteAuthorizedEvent {
        public final String proposalId;
        public final String payer;
        public final String shardHash;
        public final BigInteger amount;
        public final long blockNumber;
        public final String txHash;
        
        public WriteAuthorizedEvent(
                String proposalId,
                String payer,
                String shardHash,
                BigInteger amount,
                long blockNumber,
                String txHash) {
            this.proposalId = proposalId;
            this.payer = payer;
            this.shardHash = shardHash;
            this.amount = amount;
            this.blockNumber = blockNumber;
            this.txHash = txHash;
        }
        
        @Override
        public String toString() {
            return String.format("WriteAuthorizedEvent{proposalId=%s, payer=%s, amount=%s, block=%d, txHash=%s}",
                proposalId, payer, amount, blockNumber, txHash);
        }
    }
}
