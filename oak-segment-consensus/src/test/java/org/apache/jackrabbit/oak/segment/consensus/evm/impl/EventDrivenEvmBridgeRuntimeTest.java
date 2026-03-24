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
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Filter;
import org.web3j.protocol.core.methods.response.EthBlockNumber;
import org.web3j.protocol.core.methods.response.EthLog;
import org.web3j.protocol.core.methods.response.Log;
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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class EventDrivenEvmBridgeRuntimeTest {

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
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0",
            true,
            new OakPaymentEventParser(),
            rpcUrl -> {
                throw new AssertionError("Web3j factory should not be used in mock mode");
            },
            (threadName, delayMs, reconnectTask) -> {
                throw new AssertionError("Reconnect scheduler should not be used in mock mode");
            }
        );

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
    public void calculateRequiredPaymentIncludesSegmentStorageAndBlobFees() {
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge();

        String amount = bridge.calculateRequiredPayment(2, 3_072L, 3);

        assertEquals(new BigInteger("1380000000000000").toString(), amount);
    }

    @Test
    public void verifyPaymentRealModeRejectsMalformedProposalIdsWithoutChainCalls() throws Exception {
        Web3j web3j = mock(Web3j.class);
        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x1234567890abcdef1234567890abcdef12345678",
            false,
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
            false,
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
    public void stopDisposesSubscriptionAndShutsDownWeb3j() throws Exception {
        Web3j web3j = mock(Web3j.class);
        Disposable disposable = mock(Disposable.class);
        when(disposable.isDisposed()).thenReturn(false);

        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge(
            "sepolia",
            "0x1234567890abcdef1234567890abcdef12345678",
            false,
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

        Web3j web3j = mock(Web3j.class);
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
            false,
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
