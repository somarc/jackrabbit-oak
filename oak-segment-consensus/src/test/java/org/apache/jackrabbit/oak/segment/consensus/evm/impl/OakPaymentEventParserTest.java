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

import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;
import org.junit.Test;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.abi.datatypes.generated.Uint96;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.request.Filter;
import org.web3j.protocol.core.methods.response.Log;

import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class OakPaymentEventParserTest {

    private static final Event WRITE_AUTHORIZED_EVENT = new Event("WriteAuthorized",
        Arrays.asList(
            new TypeReference<Bytes32>(true) {},
            new TypeReference<Address>(true) {},
            new TypeReference<Bytes32>(true) {},
            new TypeReference<Uint96>() {},
            new TypeReference<Uint32>() {}
        ));

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

    private static final Event PROPOSAL_SETTLED_EVENT = new Event("ProposalSettled",
        Arrays.asList(
            new TypeReference<Bytes32>(true) {},
            new TypeReference<Address>(true) {},
            new TypeReference<Uint8>() {},
            new TypeReference<Uint8>() {},
            new TypeReference<Uint256>() {},
            new TypeReference<Uint8>(true) {},
            new TypeReference<Uint32>() {},
            new TypeReference<Address>() {},
            new TypeReference<Uint256>() {}
        ));

    private static final Event PROPOSAL_SETTLED_V5_EVENT = new Event("ProposalSettledV5",
        Arrays.asList(
            new TypeReference<Bytes32>(true) {},
            new TypeReference<Address>(true) {},
            new TypeReference<Uint8>() {},
            new TypeReference<Uint256>() {},
            new TypeReference<Uint32>() {}
        ));

    private final OakPaymentEventParser parser = new OakPaymentEventParser();

    @Test
    public void addSupportedEventTopicsAddsOakPaymentSignatures() {
        EthFilter filter = new EthFilter();

        parser.addSupportedEventTopics(filter);

        assertEquals(1, filter.getTopics().size());
        Object value = ((Filter.FilterTopic<?>) filter.getTopics().get(0)).getValue();
        assertTrue(value instanceof java.util.List);
        assertEquals(
            Arrays.asList(
                EventEncoder.encode(WRITE_AUTHORIZED_EVENT),
                EventEncoder.encode(PROPOSAL_PAID_EVENT),
                EventEncoder.encode(PROPOSAL_SETTLED_EVENT),
                EventEncoder.encode(PROPOSAL_SETTLED_V5_EVENT)
            ),
            topicValues((Filter.FilterTopic<?>) filter.getTopics().get(0))
        );
    }

    @Test
    public void maskRpcUrlMasksLongApiKeysButLeavesShortUrlsUntouched() {
        assertEquals("null", parser.maskRpcUrl(null));
        assertEquals(
            "https://rpc.example/v3/abcd***7890",
            parser.maskRpcUrl("https://rpc.example/v3/abcdef1234567890")
        );
        assertEquals("https://rpc.example/local", parser.maskRpcUrl("https://rpc.example/local"));
    }

    @Test
    public void parsePaymentLogDecodesWriteAuthorizedEvents() {
        String proposalId = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String payer = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String shardHash = "0xcccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc";
        BigInteger amount = new BigInteger("3250000");
        long blockNumber = 12345L;

        Log ethLog = new Log();
        ethLog.setTransactionHash("0xdddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd");
        ethLog.setTopics(Arrays.asList(
            EventEncoder.encode(WRITE_AUTHORIZED_EVENT),
            proposalId,
            paddedAddressTopic(payer),
            shardHash
        ));
        ethLog.setData("0x" + paddedUint(amount) + paddedUint(BigInteger.valueOf(blockNumber)));

        EventDrivenEvmBridge.WriteAuthorizedEvent event = parser.parsePaymentLog(ethLog, 99L);

        assertNotNull(event);
        assertEquals(proposalId, event.proposalId);
        assertEquals(payer, event.payer);
        assertEquals(shardHash, event.shardHash);
        assertEquals(amount, event.amount);
        assertEquals(blockNumber, event.blockNumber);
        assertEquals(PaymentProof.ProposalKind.WRITE, event.proposalKind);
        assertEquals(PaymentProof.PaymentToken.UNKNOWN, event.paymentToken);
        assertEquals(0, event.capabilityFlags);
        assertNull(event.paymentTier);
    }

    @Test
    public void parsePaymentLogUsesFallbackBlockForProposalPaidEvents() {
        String proposalId = "0x1111111111111111111111111111111111111111111111111111111111111111";
        String payer = "0x2222222222222222222222222222222222222222";
        BigInteger amount = new BigInteger("1000000000000000");

        Log ethLog = new Log();
        ethLog.setTransactionHash("0x3333333333333333333333333333333333333333333333333333333333333333");
        ethLog.setTopics(Arrays.asList(
            EventEncoder.encode(PROPOSAL_PAID_EVENT),
            proposalId,
            paddedAddressTopic(payer)
        ));
        ethLog.setData("0x"
            + paddedUint(amount)
            + paddedUint(BigInteger.valueOf(ValidatorEarningsTracker.PaymentTier.EXPRESS.ordinal()))
            + paddedAddressWord("0x4444444444444444444444444444444444444444")
            + paddedUint(BigInteger.valueOf(1_710_000_000L)));

        EventDrivenEvmBridge.WriteAuthorizedEvent event = parser.parsePaymentLog(ethLog, 43210L);

        assertNotNull(event);
        assertEquals(43210L, event.blockNumber);
        assertEquals(ValidatorEarningsTracker.PaymentTier.EXPRESS, event.paymentTier);
        assertEquals(PaymentProof.PaymentToken.UNKNOWN, event.paymentToken);
        assertEquals(PaymentProof.ProposalKind.WRITE, event.proposalKind);
        assertEquals(amount, event.amount);
    }

    @Test
    public void parsePaymentLogRejectsUnknownProposalKinds() {
        Log ethLog = new Log();
        ethLog.setTopics(Arrays.asList(
            EventEncoder.encode(PROPOSAL_SETTLED_EVENT),
            "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            paddedAddressTopic("0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"),
            paddedUint(BigInteger.ZERO)
        ));
        ethLog.setData("0x"
            + paddedUint(BigInteger.valueOf(2L))
            + paddedUint(BigInteger.ZERO)
            + paddedUint(BigInteger.valueOf(123L))
            + paddedUint(BigInteger.ZERO)
            + paddedAddressWord("0x0000000000000000000000000000000000000000")
            + paddedUint(BigInteger.valueOf(1_710_000_001L)));

        assertNull(parser.parsePaymentLog(ethLog, 55L));
    }

    @Test
    public void parsePaymentLogDecodesProposalSettledV5WithoutTier() {
        String proposalId = "0x9999999999999999999999999999999999999999999999999999999999999999";
        String payer = "0x8888888888888888888888888888888888888888";
        BigInteger amount = new BigInteger("3250000");

        Log ethLog = new Log();
        ethLog.setTransactionHash("0x7777777777777777777777777777777777777777777777777777777777777777");
        ethLog.setTopics(Arrays.asList(
            EventEncoder.encode(PROPOSAL_SETTLED_V5_EVENT),
            proposalId,
            paddedAddressTopic(payer)
        ));
        ethLog.setData("0x"
            + paddedUint(BigInteger.ONE)
            + paddedUint(amount)
            + paddedUint(BigInteger.ONE));

        EventDrivenEvmBridge.WriteAuthorizedEvent event = parser.parsePaymentLog(ethLog, 43211L);

        assertNotNull(event);
        assertEquals(proposalId, event.proposalId);
        assertEquals(payer, event.payer);
        assertEquals(amount, event.amount);
        assertEquals(43211L, event.blockNumber);
        assertEquals(PaymentProof.ProposalKind.DELETE, event.proposalKind);
        assertEquals(PaymentProof.PaymentToken.ETH, event.paymentToken);
        assertEquals(1, event.capabilityFlags);
        assertNull(event.paymentTier);
    }

    @Test
    public void parsePaymentLogIgnoresUnknownEventSignatures() {
        Log ethLog = new Log();
        ethLog.setTopics(Arrays.asList(
            "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
        ));

        assertNull(parser.parsePaymentLog(ethLog, 55L));
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

    private static java.util.List<String> topicValues(Filter.FilterTopic<?> topic) {
        Object value = topic.getValue();
        if (!(value instanceof java.util.List)) {
            return java.util.Collections.singletonList(String.valueOf(value));
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
}
