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

import io.reactivex.Flowable;
import io.reactivex.disposables.Disposable;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.apache.jackrabbit.oak.segment.consensus.evm.RpcDomainAttestor;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Filter;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetCode;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.core.methods.response.Web3ClientVersion;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class EventDrivenEvmBridgeRuntimeTest {

    private static final String CONTRACT = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String CODE = "0x6080604052";
    private static final String PROPOSAL_ID = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static final String FOREIGN = "0x9999999999999999999999999999999999999999";
    private static final String PAYER = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
    private static final String TX_HASH = "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
    private static final BigInteger AMOUNT = new BigInteger("1000000000000000");

    private static final Event PROPOSAL_PAID_EVENT = new Event("ProposalPaid",
        Arrays.asList(
            new TypeReference<Bytes32>(true) {},
            new TypeReference<Address>(true) {},
            new TypeReference<Uint256>() {},
            new TypeReference<Uint8>() {},
            new TypeReference<Uint8>(true) {},
            new TypeReference<Address>() {},
            new TypeReference<Uint256>() {}
        ));

    @After
    public void tearDown() {
        System.clearProperty("oak.blockchain.rpcUrl");
        BlockchainConfig.reset();
    }

    @Test
    public void verifyPaymentAutoConfirmsMockProposalAndRefreshesConfirmations() {
        MockEventDrivenEvmBridge bridge = new MockEventDrivenEvmBridge("sepolia", "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0");

        String proposalId = "123e4567-e89b-12d3-a456-426614174000";

        PaymentProof initial = bridge.verifyPayment(proposalId);

        assertNotNull(initial);
        assertEquals(proposalId, initial.getProposalId());
        assertEquals(1, initial.getConfirmations());
        assertEquals(66, initial.getTransactionHash().length());

        bridge.advanceMockBlocks(3);

        PaymentProof refreshed = bridge.verifyPayment(proposalId);
        assertEquals(initial.getBlockNumber(), refreshed.getBlockNumber());
        assertEquals(4, refreshed.getConfirmations());
    }

    @Test
    public void verifyPaymentRealModeRejectsMalformedProposalIdsWithoutChainCalls() throws Exception {
        Web3j web3j = mock(Web3j.class);
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x1234567890abcdef1234567890abcdef12345678",
            new OakPaymentEventParser(),
            rpcUrl -> web3j,
            (threadName, delayMs, reconnectTask) -> { }
        );
        setField(bridge, "web3j", web3j);

        assertNull(bridge.verifyPayment("not-a-chain-proposal"));
        verifyNoInteractions(web3j);
    }

    @Test
    public void verifyPaymentInRealModeFallsBackToChainLogs() throws Exception {
        String contractAddress = "0x1234567890abcdef1234567890abcdef12345678";
        String proposalId = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String payer = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        BigInteger amount = new BigInteger("1000000000000000");

        Web3j web3j = mock(Web3j.class);
        doReturn(blockNumberRequest(123456L)).when(web3j).ethBlockNumber();
        doReturn(logRequest(logResponse(proposalPaidLog(
            contractAddress,
            proposalId,
            payer,
            amount,
            123450L,
            "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        )))).when(web3j).ethGetLogs(any(EthFilter.class));

        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            contractAddress,
            new OakPaymentEventParser(),
            rpcUrl -> web3j,
            (threadName, delayMs, reconnectTask) -> { }
        );
        setField(bridge, "currentBlock", 0L);
        setField(bridge, "web3j", web3j);

        PaymentProof proof = bridge.verifyPayment(proposalId);

        assertNotNull(proof);
        assertEquals(proposalId, proof.getProposalId());
        assertEquals(payer, proof.getFromAddress());
        assertEquals(amount.toString(), proof.getAmountWei());
        assertEquals(ValidatorEarningsTracker.PaymentTier.PRIORITY, proof.getPaymentTier());
        assertEquals(PaymentProof.PaymentToken.ETH, proof.getPaymentToken());
        assertEquals(123450L, proof.getBlockNumber());
        assertEquals(7, proof.getConfirmations());

        ArgumentCaptor<EthFilter> filterCaptor = ArgumentCaptor.forClass(EthFilter.class);
        verify(web3j).ethGetLogs(filterCaptor.capture());
        EthFilter filter = filterCaptor.getValue();
        assertEquals(Collections.singletonList(contractAddress), filter.getAddress());
        assertEquals(2, filter.getTopics().size());
        assertTrue(topicValues((Filter.FilterTopic<?>) filter.getTopics().get(1)).contains(proposalId));
    }

    @Test
    public void settlementDetailsByTransactionHashInRealModeFallBackToReceiptLogs() throws Exception {
        String contractAddress = "0x1234567890abcdef1234567890abcdef12345678";
        String proposalId = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String payer = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String txHash = "0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd";
        BigInteger amount = new BigInteger("1000000000000000");

        Web3j web3j = mock(Web3j.class);
        doReturn(blockNumberRequest(123456L)).when(web3j).ethBlockNumber();
        doReturn(receiptRequest(transactionReceiptResponse(proposalPaidLog(
            contractAddress,
            proposalId,
            payer,
            amount,
            123450L,
            txHash
        )))).when(web3j).ethGetTransactionReceipt(txHash);

        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            contractAddress,
            new OakPaymentEventParser(),
            rpcUrl -> web3j,
            (threadName, delayMs, reconnectTask) -> { }
        );
        setField(bridge, "currentBlock", 0L);
        setField(bridge, "web3j", web3j);

        org.apache.jackrabbit.oak.segment.consensus.evm.SettlementDetails details =
            bridge.getSettlementDetailsByTransactionHash(txHash);

        assertNotNull(details);
        assertEquals("sepolia", details.getNetworkName());
        assertEquals(proposalId, details.getProposalId());
        assertEquals(txHash, details.getTransactionHash());
        assertEquals(123450L, details.getBlockNumber());
        assertEquals(PaymentProof.PaymentToken.ETH, details.getPaymentToken());
    }

    @Test
    public void stopDisposesSubscriptionAndShutsDownWeb3j() throws Exception {
        Web3j web3j = mock(Web3j.class);
        Disposable disposable = mock(Disposable.class);
        when(disposable.isDisposed()).thenReturn(false);

        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x1234567890abcdef1234567890abcdef12345678",
            new OakPaymentEventParser(),
            rpcUrl -> web3j,
            (threadName, delayMs, reconnectTask) -> { }
        );
        setField(bridge, "web3j", web3j);
        setField(bridge, "eventSubscription", disposable);

        bridge.stop();

        verify(disposable).dispose();
        verify(web3j).shutdown();
    }

    @Test
    public void startRealModeSchedulesReconnectAfterSubscriptionFailure() throws Exception {
        System.setProperty("oak.blockchain.rpcUrl", "https://rpc.example/v3/abcdef1234567890");
        BlockchainConfig.reset();

        Web3j web3j = attestableWeb3j(11155111L, CODE);
        doReturn(clientVersionRequest("test-client")).when(web3j).web3ClientVersion();
        doReturn(blockNumberRequest(123456L)).when(web3j).ethBlockNumber();
        when(web3j.ethLogFlowable(any(EthFilter.class))).thenReturn(Flowable.error(new RuntimeException("boom")));

        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger scheduledCount = new AtomicInteger();
        AtomicLong scheduledDelay = new AtomicLong();
        AtomicReference<String> scheduledThreadName = new AtomicReference<>();
        AtomicReference<Runnable> reconnectTask = new AtomicReference<>();

        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x1234567890abcdef1234567890abcdef12345678",
            new OakPaymentEventParser(),
            rpcUrl -> {
                factoryCalls.incrementAndGet();
                return web3j;
            },
            (threadName, delayMs, task) -> {
                scheduledCount.incrementAndGet();
                scheduledThreadName.set(threadName);
                scheduledDelay.set(delayMs);
                reconnectTask.set(task);
            }
        );

        try {
            bridge.start();

            assertEquals(1, factoryCalls.get());
            assertEquals(1, scheduledCount.get());
            assertEquals("Web3j-Reconnect", scheduledThreadName.get());
            assertEquals(30000L, scheduledDelay.get());
            assertNotNull(reconnectTask.get());

            reconnectTask.get().run();

            assertEquals(2, factoryCalls.get());
            assertEquals(2, scheduledCount.get());
        } finally {
            bridge.stop();
        }

        verify(web3j, times(2)).web3ClientVersion();
        verify(web3j, times(2)).ethBlockNumber();
    }

    @Test
    public void startFailsClosedWhenRpcServesAnotherChain() throws Exception {
        System.setProperty("oak.blockchain.rpcUrl", "http://127.0.0.1:8545");
        BlockchainConfig.reset();
        Web3j web3j = attestableWeb3j(1L, CODE);
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge("local", CONTRACT, new OakPaymentEventParser(),
            rpcUrl -> web3j, (threadName, delayMs, task) -> { throw new AssertionError("no reconnect"); });

        RpcDomainAttestor.AttestationException e =
            assertThrows(RpcDomainAttestor.AttestationException.class, bridge::start);

        assertTrue(e.getMessage(), e.getMessage().contains("expected 31337"));
        assertNull(getField(bridge, "web3j"));
        verify(web3j).shutdown();
        verify(web3j, never()).ethLogFlowable(any(EthFilter.class));
        assertNull(bridge.verifyPayment(PROPOSAL_ID));
    }

    @Test
    public void startFailsClosedOnPinnedCodeHashMismatchOrMissingCode() throws Exception {
        System.setProperty("oak.blockchain.rpcUrl", "http://127.0.0.1:8545");
        BlockchainConfig.reset();
        String[][] cases = {{CODE, "0x" + "22".repeat(32), "does not match the pinned"}, {"0x", null, "No contract code"}};
        for (String[] c : cases) {
            Web3j web3j = attestableWeb3j(31337L, c[0]);
            EventDrivenEvmBridge bridge = new EventDrivenEvmBridge("local", CONTRACT, new OakPaymentEventParser(),
                rpcUrl -> web3j, (threadName, delayMs, task) -> { }, c[1]);

            RpcDomainAttestor.AttestationException e =
                assertThrows(RpcDomainAttestor.AttestationException.class, bridge::start);

            assertTrue(e.getMessage(), e.getMessage().contains(c[2]));
            verify(web3j, never()).ethLogFlowable(any(EthFilter.class));
        }
    }

    @Test
    public void startAttestsThenSubscribesWithPinnedCodeHash() throws Exception {
        System.setProperty("oak.blockchain.rpcUrl", "http://127.0.0.1:8545");
        BlockchainConfig.reset();
        Web3j web3j = attestableWeb3j(31337L, CODE);
        doReturn(clientVersionRequest("anvil")).when(web3j).web3ClientVersion();
        doReturn(blockNumberRequest(10L)).when(web3j).ethBlockNumber();
        when(web3j.ethLogFlowable(any(EthFilter.class))).thenReturn(Flowable.never());
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge("local", CONTRACT, new OakPaymentEventParser(),
            rpcUrl -> web3j, (threadName, delayMs, task) -> { }, Hash.sha3(CODE));

        try {
            bridge.start();
            assertSame(web3j, getField(bridge, "web3j"));
            verify(web3j).ethChainId();
            verify(web3j).ethLogFlowable(any(EthFilter.class));
        } finally {
            bridge.stop();
        }
    }

    @Test
    public void reconnectToASubstitutedRpcDomainLeavesVerificationDisabled() throws Exception {
        System.setProperty("oak.blockchain.rpcUrl", "http://127.0.0.1:8545");
        BlockchainConfig.reset();
        Web3j good = attestableWeb3j(31337L, CODE);
        doReturn(clientVersionRequest("anvil")).when(good).web3ClientVersion();
        doReturn(blockNumberRequest(10L)).when(good).ethBlockNumber();
        when(good.ethLogFlowable(any(EthFilter.class))).thenReturn(Flowable.error(new RuntimeException("dropped")));
        Web3j substituted = attestableWeb3j(31337L, "0x");
        AtomicInteger connects = new AtomicInteger();
        AtomicReference<Runnable> reconnect = new AtomicReference<>();
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge("local", CONTRACT, new OakPaymentEventParser(),
            rpcUrl -> connects.getAndIncrement() == 0 ? good : substituted,
            (threadName, delayMs, task) -> reconnect.set(task));

        try {
            bridge.start();
            reconnect.get().run();

            assertNull(getField(bridge, "web3j"));
            verify(substituted).shutdown();
            verify(substituted, never()).ethLogFlowable(any(EthFilter.class));
        } finally {
            bridge.stop();
        }
    }

    @Test
    public void subscriptionRejectsLogsFromAForeignEmitter() throws Exception {
        System.setProperty("oak.blockchain.rpcUrl", "http://127.0.0.1:8545");
        BlockchainConfig.reset();
        String own = "0x" + "11".repeat(32);
        Web3j web3j = attestableWeb3j(11155111L, CODE);
        doReturn(clientVersionRequest("node")).when(web3j).web3ClientVersion();
        doReturn(blockNumberRequest(200L)).when(web3j).ethBlockNumber();
        when(web3j.ethLogFlowable(any(EthFilter.class))).thenReturn(Flowable.fromArray(
            proposalPaidLog(FOREIGN, PROPOSAL_ID, PAYER, AMOUNT, 100L, TX_HASH),
            proposalPaidLog(CONTRACT, own, PAYER, AMOUNT, 101L, TX_HASH)));
        EventDrivenEvmBridge bridge = chainBridge("sepolia", web3j);

        try {
            bridge.start();
        } finally {
            bridge.stop();
        }

        assertNull(bridge.payments.get(PROPOSAL_ID));
        assertNotNull(bridge.payments.get(own));
    }

    @Test
    public void logQueryRejectsForeignEmitterRemovedPendingAndOtherProposalLogs() throws Exception {
        Log foreign = proposalPaidLog(FOREIGN, PROPOSAL_ID, PAYER, AMOUNT, 100L, TX_HASH);
        Log removed = proposalPaidLog(CONTRACT, PROPOSAL_ID, PAYER, AMOUNT, 100L, TX_HASH);
        removed.setRemoved(true);
        Log pending = proposalPaidLog(CONTRACT, PROPOSAL_ID, PAYER, AMOUNT, 100L, TX_HASH);
        pending.setBlockNumber(null);
        Log otherProposal = proposalPaidLog(CONTRACT, "0x" + "22".repeat(32), PAYER, AMOUNT, 100L, TX_HASH);

        for (Log rejected : new Log[] {foreign, removed, pending, otherProposal}) {
            Web3j web3j = mock(Web3j.class);
            doReturn(blockNumberRequest(200L)).when(web3j).ethBlockNumber();
            doReturn(logRequest(logsResponse(rejected))).when(web3j).ethGetLogs(any(EthFilter.class));
            EventDrivenEvmBridge bridge = chainBridge("sepolia", web3j);
            setField(bridge, "web3j", web3j);

            assertNull(bridge.verifyPayment(PROPOSAL_ID));
            assertTrue(bridge.payments.isEmpty());
        }
    }

    @Test
    public void receiptLookupParsesOnlyLogsEmittedByTheConfiguredContract() throws Exception {
        String foreignProposal = "0x" + "33".repeat(32);
        Web3j web3j = mock(Web3j.class);
        doReturn(blockNumberRequest(200L)).when(web3j).ethBlockNumber();
        doReturn(receiptRequest(receiptResponse(
            proposalPaidLog(FOREIGN, foreignProposal, PAYER, AMOUNT, 100L, TX_HASH),
            proposalPaidLog(FOREIGN, PROPOSAL_ID, PAYER, AMOUNT, 100L, TX_HASH)
        ))).when(web3j).ethGetTransactionReceipt(TX_HASH);
        EventDrivenEvmBridge bridge = chainBridge("sepolia", web3j);
        setField(bridge, "web3j", web3j);

        assertNull(bridge.getSettlementDetailsByTransactionHash(TX_HASH));
        assertTrue("foreign receipt logs must not enter the payment cache", bridge.payments.isEmpty());
    }

    @Test
    public void proofCarriesTheEmittingAddress() throws Exception {
        String emitted = CONTRACT.toUpperCase().replace("0X", "0x");
        Web3j web3j = mock(Web3j.class);
        doReturn(blockNumberRequest(200L)).when(web3j).ethBlockNumber();
        doReturn(logRequest(logsResponse(proposalPaidLog(emitted, PROPOSAL_ID, PAYER, AMOUNT, 100L, TX_HASH))))
            .when(web3j).ethGetLogs(any(EthFilter.class));
        EventDrivenEvmBridge bridge = chainBridge("sepolia", web3j);
        setField(bridge, "web3j", web3j);

        PaymentProof proof = bridge.verifyPayment(PROPOSAL_ID);

        assertNotNull(proof);
        assertEquals(emitted, proof.getContractAddress());
        assertEquals(101, proof.getConfirmations());
    }

    @Test
    public void finalizedTagModeCountsConfirmationsOnlyForFinalizedBlocks() throws Exception {
        Web3j web3j = mock(Web3j.class);
        doReturn(blockNumberRequest(200L)).when(web3j).ethBlockNumber();
        EthBlock.Block finalizedBlock = new EthBlock.Block();
        finalizedBlock.setNumber(hex(150L));
        EthBlock finalized = new EthBlock();
        finalized.setResult(finalizedBlock);
        doReturn(genericRequest(finalized)).when(web3j).ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false);
        EventDrivenEvmBridge mainnet = chainBridge("mainnet", web3j);
        setField(mainnet, "web3j", web3j);

        assertEquals(0, mainnet.confirmationsForBlock(151L));
        assertEquals(51, mainnet.confirmationsForBlock(150L));

        EventDrivenEvmBridge sepolia = chainBridge("sepolia", web3j);
        setField(sepolia, "web3j", web3j);
        assertEquals(50, sepolia.confirmationsForBlock(151L));
        verify(web3j, times(2)).ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false);
    }

    @Test
    public void finalizedTagModeHasNoConfirmationsWhenFinalityCannotBeRead() throws Exception {
        Web3j web3j = mock(Web3j.class);
        doReturn(blockNumberRequest(200L)).when(web3j).ethBlockNumber();
        EthBlock error = new EthBlock();
        error.setError(new org.web3j.protocol.core.Response.Error(-32000, "finalized not supported"));
        doReturn(genericRequest(error)).when(web3j).ethGetBlockByNumber(DefaultBlockParameterName.FINALIZED, false);
        EventDrivenEvmBridge mainnet = chainBridge("mainnet", web3j);
        setField(mainnet, "web3j", web3j);

        assertEquals(0, mainnet.confirmationsForBlock(10L));
    }

    private static EventDrivenEvmBridge chainBridge(String mode, Web3j web3j) {
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(mode, CONTRACT, new OakPaymentEventParser(),
            rpcUrl -> web3j, (threadName, delayMs, task) -> { });
        bridge.currentBlock = 0L;
        return bridge;
    }

    private static EthLog logsResponse(Log log) {
        EthLog response = new EthLog();
        response.setResult(Collections.singletonList((EthLog.LogResult) log));
        return response;
    }

    private static EthGetTransactionReceipt receiptResponse(Log... logs) {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setLogs(Arrays.asList(logs));
        EthGetTransactionReceipt response = new EthGetTransactionReceipt();
        response.setResult(receipt);
        return response;
    }

    private static Web3j attestableWeb3j(long chainId, String code) throws Exception {
        Web3j web3j = mock(Web3j.class);
        EthChainId chainIdResponse = new EthChainId();
        chainIdResponse.setResult(hex(chainId));
        doReturn(genericRequest(chainIdResponse)).when(web3j).ethChainId();
        EthGetCode codeResponse = new EthGetCode();
        codeResponse.setResult(code);
        doReturn(genericRequest(codeResponse)).when(web3j).ethGetCode(anyString(), any());
        return web3j;
    }

    @SuppressWarnings("unchecked")
    private static <T extends org.web3j.protocol.core.Response<?>> Request<?, T> genericRequest(T response)
            throws Exception {
        Request<?, T> request = mock(Request.class);
        when(request.send()).thenReturn(response);
        return request;
    }

    private static Object getField(Object target, String fieldName) throws Exception {
        Field field = EventDrivenEvmBridge.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Log proposalPaidLog(String contractAddress,
                                       String proposalId,
                                       String payer,
                                       BigInteger amount,
                                       long blockNumber,
                                       String txHash) {
        Log ethLog = new EthLog.LogObject();
        ethLog.setAddress(contractAddress);
        ethLog.setBlockNumber(hex(blockNumber));
        ethLog.setTransactionHash(txHash);
        ethLog.setTopics(Arrays.asList(
            EventEncoder.encode(PROPOSAL_PAID_EVENT),
            proposalId,
            paddedAddressTopic(payer),
            paddedUint(BigInteger.ZERO)
        ));
        ethLog.setData("0x"
            + paddedUint(amount)
            + paddedUint(BigInteger.valueOf(ValidatorEarningsTracker.PaymentTier.PRIORITY.ordinal()))
            + paddedAddressWord("0xcccccccccccccccccccccccccccccccccccccccc")
            + paddedUint(BigInteger.valueOf(1_710_000_000L)));
        return ethLog;
    }

    @SuppressWarnings("unchecked")
    private static Request<?, Web3ClientVersion> clientVersionRequest(String value) throws Exception {
        Request<?, Web3ClientVersion> request = mock(Request.class);
        when(request.send()).thenReturn(clientVersionResponse(value));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Request<?, EthBlockNumber> blockNumberRequest(long value) throws Exception {
        Request<?, EthBlockNumber> request = mock(Request.class);
        when(request.send()).thenReturn(blockNumberResponse(value));
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Request<?, EthLog> logRequest(EthLog response) throws Exception {
        Request<?, EthLog> request = mock(Request.class);
        when(request.send()).thenReturn(response);
        return request;
    }

    @SuppressWarnings("unchecked")
    private static Request<?, EthGetTransactionReceipt> receiptRequest(EthGetTransactionReceipt response) throws Exception {
        Request<?, EthGetTransactionReceipt> request = mock(Request.class);
        when(request.send()).thenReturn(response);
        return request;
    }

    private static Web3ClientVersion clientVersionResponse(String value) {
        Web3ClientVersion response = new Web3ClientVersion();
        response.setResult(value);
        return response;
    }

    private static EthBlockNumber blockNumberResponse(long value) {
        EthBlockNumber response = new EthBlockNumber();
        response.setResult(hex(value));
        return response;
    }

    private static EthLog logResponse(Log log) {
        EthLog response = new EthLog();
        EthLog.LogObject logObject = new EthLog.LogObject();
        logObject.setAddress(log.getAddress());
        logObject.setBlockNumber(hex(log.getBlockNumber().longValue()));
        logObject.setTransactionHash(log.getTransactionHash());
        logObject.setTopics(log.getTopics());
        logObject.setData(log.getData());
        response.setResult(Collections.singletonList(logObject));
        return response;
    }

    private static EthGetTransactionReceipt transactionReceiptResponse(Log log) {
        TransactionReceipt receipt = new TransactionReceipt();
        receipt.setLogs(Collections.singletonList(log));

        EthGetTransactionReceipt response = new EthGetTransactionReceipt();
        response.setResult(receipt);
        return response;
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = EventDrivenEvmBridge.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static java.util.List<String> topicValues(Filter.FilterTopic<?> topic) {
        Object value = topic.getValue();
        if (!(value instanceof java.util.List)) {
            return Collections.singletonList(String.valueOf(value));
        }

        java.util.List<?> raw = (java.util.List<?>) value;
        java.util.List<String> normalized = new java.util.ArrayList<>(raw.size());
        for (Object entry : raw) {
            if (entry instanceof Filter.FilterTopic) {
                normalized.add(String.valueOf(((Filter.FilterTopic<?>) entry).getValue()));
            } else {
                normalized.add(String.valueOf(entry));
            }
        }
        return normalized;
    }

    private static String hex(long value) {
        return "0x" + Long.toHexString(value);
    }

    private static String paddedUint(BigInteger value) {
        return String.format("%064x", value);
    }

    private static String paddedAddressTopic(String address) {
        return "0x" + paddedAddressWord(address);
    }

    private static String paddedAddressWord(String address) {
        return String.format("%064x", new BigInteger(address.substring(2), 16));
    }
}
