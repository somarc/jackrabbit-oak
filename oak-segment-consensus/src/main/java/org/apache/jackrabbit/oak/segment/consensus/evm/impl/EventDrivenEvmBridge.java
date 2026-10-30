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
import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.evm.EvmBridge;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.apache.jackrabbit.oak.segment.consensus.evm.SettlementDetails;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-driven EVM bridge for chain-backed modes.
 * 
 * <p>This bridge listens to WriteAuthorized events from OakWriteAuthorizationV5 contract
 * through Web3j event subscriptions.
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
    
    private static final BigInteger EVENT_LOOKBACK_BLOCKS = BigInteger.valueOf(10_000L);
    
    private final String networkName;
    private final String contractAddress;
    private final OakPaymentEventParser paymentEventParser;
    private final Web3jFactory web3jFactory;
    private final ReconnectScheduler reconnectScheduler;
    
    // Web3j client (for real mode)
    private Web3j web3j;
    private Disposable eventSubscription;
    
    // Payment proofs (proposalId -> PaymentProof)
    final Map<String, PaymentProof> payments = new ConcurrentHashMap<>();

    long currentBlock = 1000000;
    private boolean running = false;

    @FunctionalInterface
    interface Web3jFactory {
        Web3j create(String rpcUrl);
    }

    @FunctionalInterface
    interface ReconnectScheduler {
        void schedule(String threadName, long delayMs, Runnable reconnectTask);
    }
    
    /**
     * Create event-driven EVM bridge.
     * 
     * @param networkName Network name (e.g., "mainnet", "polygon", "sepolia")
     * @param contractAddress Contract address (0x...)
     */
    public EventDrivenEvmBridge(@NotNull String networkName, @NotNull String contractAddress) {
        this(
            networkName,
            contractAddress,
            new OakPaymentEventParser(),
            rpcUrl -> Web3j.build(new HttpService(rpcUrl)),
            (threadName, delayMs, reconnectTask) -> {
                Thread reconnectThread = new Thread(() -> {
                    try {
                        Thread.sleep(delayMs);
                        reconnectTask.run();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }, threadName);
                reconnectThread.setDaemon(true);
                reconnectThread.start();
            }
        );
    }

    EventDrivenEvmBridge(@NotNull String networkName,
                         @NotNull String contractAddress,
                         @NotNull OakPaymentEventParser paymentEventParser,
                         @NotNull Web3jFactory web3jFactory,
                         @NotNull ReconnectScheduler reconnectScheduler) {
        this.networkName = networkName;
        this.contractAddress = contractAddress;
        this.paymentEventParser = paymentEventParser;
        this.web3jFactory = web3jFactory;
        this.reconnectScheduler = reconnectScheduler;
    }
    
    @Override
    public PaymentProof verifyPayment(@NotNull String proposalId) {
        // Check if payment proof already exists (from processed event)
        PaymentProof existing = payments.get(proposalId);
        if (existing != null) {
            return refreshProofConfirmations(existing);
        }
        
        // Fall back to a direct log query in case the subscription missed the event.
        return refreshProofConfirmations(fetchPaymentFromChain(proposalId));
    }

    @Override
    @Nullable
    public SettlementDetails getSettlementDetailsByTransactionHash(@NotNull String transactionHash) {
        PaymentProof cached = findCachedProofByTransactionHash(transactionHash);
        if (cached != null) {
            PaymentProof refreshed = refreshProofConfirmations(cached);
            return SettlementDetails.fromProof(getNetworkName(), refreshed);
        }

        PaymentProof proof = fetchPaymentByTransactionHash(transactionHash);
        return proof != null ? SettlementDetails.fromProof(getNetworkName(), proof) : null;
    }
    
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
        log.info("🌐 EventDrivenEvmBridge started (network: {}, contract: {})", networkName, contractAddress);
        startWeb3jEventSubscription();
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
    
    // ========== Real Mode (Mainnet) ==========
    
    /**
     * Start Web3j event subscription (for real Sepolia/Mainnet).
     * 
     * <p>Subscribes to payment/authorization events from the configured contract.
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
            log.info("🔗 Connecting to Ethereum RPC: {}", paymentEventParser.maskRpcUrl(rpcUrl));
            web3j = web3jFactory.create(rpcUrl);
            
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
            
            // Subscribe to all supported Oak payment event signatures.
            paymentEventParser.addSupportedEventTopics(filter);

            log.info("📡 Subscribing to Oak payment events on contract: {}", contractAddress);
            
            // Subscribe to events
            eventSubscription = web3j.ethLogFlowable(filter)
                .subscribe(
                    ethLog -> {
                        try {
                            WriteAuthorizedEvent event = parsePaymentLog(ethLog);
                            if (event == null) {
                                return;
                            }
                            log.info("📨 Received Oak payment event: proposalId={}, payer={}, kind={}, block={}",
                                event.proposalId, event.payer, event.proposalKind, event.blockNumber);
                            processWriteAuthorizedEvent(event);
                        } catch (Exception e) {
                            log.error("Error parsing Oak payment event", e);
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
            log.warn("⚠️  Chain-backed verification is unavailable until the Web3j subscription can connect and recover");
        }
    }
    
    /**
     * Schedule reconnection attempt after subscription failure.
     */
    private void scheduleReconnect() {
        if (!running) return;
        
        reconnectScheduler.schedule("Web3j-Reconnect", 30000L, () -> {
            if (running) {
                log.info("🔄 Attempting to reconnect Web3j subscription...");
                startWeb3jEventSubscription();
            }
        });
    }

    PaymentProof fetchPaymentFromChain(@NotNull String proposalId) {
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
            paymentEventParser.addSupportedEventTopics(filter);
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

    @Nullable
    private PaymentProof fetchPaymentByTransactionHash(@NotNull String transactionHash) {
        if (web3j == null || transactionHash.trim().isEmpty()) {
            return null;
        }

        try {
            resolveLatestBlockNumber();
            EthGetTransactionReceipt receiptResponse = web3j.ethGetTransactionReceipt(transactionHash).send();
            if (receiptResponse == null || receiptResponse.getTransactionReceipt() == null
                    || !receiptResponse.getTransactionReceipt().isPresent()) {
                return null;
            }

            TransactionReceipt receipt = receiptResponse.getTransactionReceipt().get();
            if (receipt.getLogs() == null || receipt.getLogs().isEmpty()) {
                return null;
            }

            for (org.web3j.protocol.core.methods.response.Log receiptLog : receipt.getLogs()) {
                WriteAuthorizedEvent event = parsePaymentLog(receiptLog);
                if (event == null) {
                    continue;
                }
                processWriteAuthorizedEvent(event);
                PaymentProof proof = payments.get(event.proposalId);
                if (proof != null && transactionHash.equalsIgnoreCase(proof.getTransactionHash())) {
                    return refreshProofConfirmations(proof);
                }
            }
        } catch (Exception e) {
            log.debug("On-chain payment lookup failed for transactionHash={}", transactionHash, e);
        }
        return null;
    }

    @Nullable
    private PaymentProof findCachedProofByTransactionHash(@NotNull String transactionHash) {
        if (transactionHash.trim().isEmpty()) {
            return null;
        }
        for (PaymentProof proof : payments.values()) {
            if (proof != null && transactionHash.equalsIgnoreCase(proof.getTransactionHash())) {
                return proof;
            }
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
            proof.getPaymentTier(),
            proof.getProposalKind(),
            proof.getPaymentToken(),
            proof.getCapabilityFlags(),
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
        if (web3j != null) {
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
        return paymentEventParser.parsePaymentLog(ethLog, currentBlock);
    }
    
    // ========== Event Processing ==========
    
    /**
     * Process a WriteAuthorized event from the Web3j subscription or a log lookup.
     */
    void processWriteAuthorizedEvent(WriteAuthorizedEvent event) {
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
            event.paymentTier,
            event.proposalKind,
            event.paymentToken,
            event.capabilityFlags,
            1 // 1 confirmation (just mined)
        );
        
        // Store payment proof
        payments.put(event.proposalId, proof);
        
        // Update current block
        currentBlock = Math.max(currentBlock, event.blockNumber);
        
        log.info("✅ Payment proof stored: proposalId={}, verified=true", event.proposalId);
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
        public final ValidatorEarningsTracker.PaymentTier paymentTier;
        public final PaymentProof.ProposalKind proposalKind;
        public final PaymentProof.PaymentToken paymentToken;
        public final int capabilityFlags;
        
        public WriteAuthorizedEvent(
                String proposalId,
                String payer,
                String shardHash,
                BigInteger amount,
                long blockNumber,
                String txHash,
                ValidatorEarningsTracker.PaymentTier paymentTier,
                PaymentProof.ProposalKind proposalKind,
                PaymentProof.PaymentToken paymentToken,
                int capabilityFlags) {
            this.proposalId = proposalId;
            this.payer = payer;
            this.shardHash = shardHash;
            this.amount = amount;
            this.blockNumber = blockNumber;
            this.txHash = txHash;
            this.paymentTier = paymentTier;
            this.proposalKind = proposalKind;
            this.paymentToken = paymentToken;
            this.capabilityFlags = capabilityFlags;
        }
        
        @Override
        public String toString() {
            return String.format(
                "WriteAuthorizedEvent{proposalId=%s, payer=%s, kind=%s, amount=%s, tier=%s, token=%s, capabilityFlags=%d, block=%d, txHash=%s}",
                proposalId, payer, proposalKind, amount, paymentTier, paymentToken, capabilityFlags, blockNumber, txHash
            );
        }
    }

}
