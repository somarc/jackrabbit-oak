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
import org.apache.jackrabbit.oak.segment.consensus.evm.RpcDomainAttestor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;

import java.math.BigInteger;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event-driven EVM bridge for chain-backed modes: subscribes through Web3j to the configured payment contract's
 * {@code ProposalSettledV5} (ValidatorPaymentV5) and {@code ProposalPaid} (ValidatorPaymentV3.2) events and keeps
 * one {@link PaymentProof} per proposal.
 */
public class EventDrivenEvmBridge implements EvmBridge {
    
    private static final Logger log = LoggerFactory.getLogger(EventDrivenEvmBridge.class);
    
    private static final BigInteger EVENT_LOOKBACK_BLOCKS = BigInteger.valueOf(10_000L);
    
    private final String networkName;
    private final BlockchainConfig.Mode mode;
    private final String contractAddress;
    private final String expectedCodeHash;
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
     * Create event-driven EVM bridge whose RPC domain is attested at start.
     *
     * @param networkName blockchain mode key ("local", "sepolia", ...); fixes the expected chain id
     * @param contractAddress Contract address (0x...)
     * @param expectedCodeHash keccak256 of the contract's deployed code, or null if not pinned
     */
    public EventDrivenEvmBridge(@NotNull String networkName, @NotNull String contractAddress,
                                @Nullable String expectedCodeHash) {
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
            },
            expectedCodeHash
        );
    }

    EventDrivenEvmBridge(@NotNull String networkName,
                         @NotNull String contractAddress,
                         @NotNull OakPaymentEventParser paymentEventParser,
                         @NotNull Web3jFactory web3jFactory,
                         @NotNull ReconnectScheduler reconnectScheduler,
                         @Nullable String expectedCodeHash) {
        this.networkName = networkName;
        this.mode = BlockchainConfig.Mode.fromString(networkName);
        this.contractAddress = contractAddress;
        this.expectedCodeHash = expectedCodeHash;
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
    public PaymentProof getSettlementDetailsByTransactionHash(@NotNull String transactionHash) {
        PaymentProof cached = findCachedProofByTransactionHash(transactionHash);
        if (cached != null) {
            return refreshProofConfirmations(cached);
        }
        return fetchPaymentByTransactionHash(transactionHash);
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
    
    /**
     * Connects, attests the RPC domain and subscribes. An RPC domain that cannot be attested (wrong chain,
     * missing or different contract code, unreachable endpoint) fails the start.
     */
    @Override
    public void start() {
        running = true;
        log.info("🌐 EventDrivenEvmBridge started (network: {}, contract: {})", networkName, contractAddress);
        try {
            startWeb3jEventSubscription();
        } catch (RpcDomainAttestor.AttestationException e) {
            stop();
            throw e;
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
            web3j = attestedClient(web3jFactory.create(rpcUrl));

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
            
        } catch (RpcDomainAttestor.AttestationException e) {
            log.error("❌ RPC domain attestation failed - payment verification stays disabled: {}", e.getMessage());
            throw e;
        } catch (Exception e) {
            log.error("❌ Failed to start Web3j event subscription", e);
            log.warn("⚠️  Chain-backed verification is unavailable until the Web3j subscription can connect and recover");
        }
    }
    
    /** Returns the client only once its chain and contract are attested; a failed client is never kept. */
    private Web3j attestedClient(Web3j client) {
        if (!mode.isChainBacked()) {
            return client;
        }
        web3j = null;
        try {
            new RpcDomainAttestor(client, mode.getExpectedChainId()).attest(mode, contractAddress, expectedCodeHash);
        } catch (RpcDomainAttestor.AttestationException e) {
            client.shutdown();
            throw e;
        }
        log.info("✅ RPC domain attested: chainId={}, contract={}", mode.getExpectedChainId(), contractAddress);
        return client;
    }

    /**
     * Schedule reconnection attempt after subscription failure.
     */
    private void scheduleReconnect() {
        if (!running) return;
        
        reconnectScheduler.schedule("Web3j-Reconnect", 30000L, () -> {
            if (running) {
                log.info("🔄 Attempting to reconnect Web3j subscription...");
                try {
                    startWeb3jEventSubscription();
                } catch (RpcDomainAttestor.AttestationException e) {
                    // Logged; the bridge stays unsubscribed rather than trust a substituted RPC domain.
                }
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
                if (event == null || !proposalId.equalsIgnoreCase(event.proposalId)) {
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
        PaymentProof refreshed = proof.withConfirmations(confirmations);
        payments.put(proof.getProposalId(), refreshed);
        return refreshed;
    }

    /**
     * Confirmations are counted against the latest block. In finalized-tag modes (mainnet) a payment has none
     * until its block is at or below the {@code finalized} block.
     */
    int confirmationsForBlock(long blockNumber) {
        long latestBlock = resolveLatestBlockNumber();
        if (mode.usesFinalizedTag() && !isFinalized(blockNumber)) {
            return 0;
        }
        long confirmations = latestBlock >= blockNumber ? (latestBlock - blockNumber) + 1L : 1L;
        return confirmations > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) confirmations;
    }

    private boolean isFinalized(long blockNumber) {
        if (web3j == null) {
            return false;
        }
        try {
            EthBlock response = web3j.ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false).send();
            EthBlock.Block finalized = response != null && !response.hasError() ? response.getBlock() : null;
            return finalized != null && finalized.getNumber() != null
                && blockNumber <= finalized.getNumber().longValue();
        } catch (Exception e) {
            log.debug("Unable to read the finalized block; treating block {} as not finalized", blockNumber, e);
            return false;
        }
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

    /**
     * The single gate for every log the bridge reads (subscription, log query, receipt): only mined, non-removed
     * logs emitted by the configured contract are parsed.
     */
    private WriteAuthorizedEvent parsePaymentLog(
            org.web3j.protocol.core.methods.response.Log ethLog) {
        if (ethLog == null) {
            return null;
        }
        if (ethLog.isRemoved()) {
            log.warn("Skipping removed (reorged) payment log: tx={}", ethLog.getTransactionHash());
            return null;
        }
        if (ethLog.getAddress() == null || !contractAddress.equalsIgnoreCase(ethLog.getAddress())) {
            log.warn("Rejecting payment log from {} (configured contract {}): tx={}",
                ethLog.getAddress(), contractAddress, ethLog.getTransactionHash());
            return null;
        }
        if (ethLog.getBlockNumber() == null) {
            log.debug("Skipping pending payment log without a block number: tx={}", ethLog.getTransactionHash());
            return null;
        }
        return paymentEventParser.parsePaymentLog(ethLog, currentBlock);
    }
    
    // ========== Event Processing ==========
    
    /**
     * Process a payment event from the Web3j subscription or a log lookup.
     */
    void processWriteAuthorizedEvent(WriteAuthorizedEvent event) {
        log.info("📨 Processing payment event: proposalId={}, payer={}, amount={}, block={}",
            event.proposalId, event.payer, event.amount, event.blockNumber);
        
        PaymentProof proof = new PaymentProof(
            event.txHash,
            event.blockNumber,
            event.payer,
            event.emitterAddress != null ? event.emitterAddress : contractAddress,
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
     * A decoded payment event ({@code ProposalSettledV5} or {@code ProposalPaid}).
     */
    public static class WriteAuthorizedEvent {
        public final String proposalId;
        public final String payer;
        public final BigInteger amount;
        public final long blockNumber;
        public final String txHash;
        public final ValidatorEarningsTracker.PaymentTier paymentTier;
        public final PaymentProof.ProposalKind proposalKind;
        public final PaymentProof.PaymentToken paymentToken;
        public final int capabilityFlags;
        /** Address of the contract that emitted the log; null for events that did not come from a log. */
        public final String emitterAddress;
        
        public WriteAuthorizedEvent(
                String proposalId,
                String payer,
                BigInteger amount,
                long blockNumber,
                String txHash,
                ValidatorEarningsTracker.PaymentTier paymentTier,
                PaymentProof.ProposalKind proposalKind,
                PaymentProof.PaymentToken paymentToken,
                int capabilityFlags,
                String emitterAddress) {
            this.proposalId = proposalId;
            this.payer = payer;
            this.amount = amount;
            this.blockNumber = blockNumber;
            this.txHash = txHash;
            this.paymentTier = paymentTier;
            this.proposalKind = proposalKind;
            this.paymentToken = paymentToken;
            this.capabilityFlags = capabilityFlags;
            this.emitterAddress = emitterAddress;
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
