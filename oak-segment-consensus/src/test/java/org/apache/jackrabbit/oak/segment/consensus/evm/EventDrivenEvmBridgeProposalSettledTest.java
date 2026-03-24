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
package org.apache.jackrabbit.oak.segment.consensus.evm;

import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.evm.impl.EventDrivenEvmBridge;
import org.junit.Test;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.core.methods.response.Log;

import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class EventDrivenEvmBridgeProposalSettledTest {

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

    @Test
    public void testProposalSettledDeleteLogCreatesPaymentProof() throws Exception {
        String contractAddress = "0x1234567890abcdef1234567890abcdef12345678";
        String proposalId = "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        String payer = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String preferredValidator = "0x0000000000000000000000000000000000000000";
        BigInteger amount = new BigInteger("3250000");
        long blockNumber = 123789L;
        String txHash = "0xeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee";

        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge("sepolia", contractAddress, false);

        Log ethLog = new Log();
        ethLog.setAddress(contractAddress);
        ethLog.setBlockNumber(hex(blockNumber));
        ethLog.setTransactionHash(txHash);
        ethLog.setTopics(Arrays.asList(
            EventEncoder.encode(PROPOSAL_SETTLED_EVENT),
            proposalId,
            paddedAddressTopic(payer),
            paddedUint(BigInteger.ONE)
        ));
        ethLog.setData("0x"
            + paddedUint(BigInteger.ONE)
            + paddedUint(BigInteger.ZERO)
            + paddedUint(amount)
            + paddedUint(BigInteger.ZERO)
            + paddedAddressWord(preferredValidator)
            + paddedUint(BigInteger.valueOf(1_710_000_123L)));

        Method parsePaymentLog = EventDrivenEvmBridge.class.getDeclaredMethod("parsePaymentLog", Log.class);
        parsePaymentLog.setAccessible(true);
        EventDrivenEvmBridge.WriteAuthorizedEvent event =
            (EventDrivenEvmBridge.WriteAuthorizedEvent) parsePaymentLog.invoke(bridge, ethLog);
        assertNotNull(event);

        Method processWriteAuthorizedEvent = EventDrivenEvmBridge.class.getDeclaredMethod(
            "processWriteAuthorizedEvent",
            EventDrivenEvmBridge.WriteAuthorizedEvent.class
        );
        processWriteAuthorizedEvent.setAccessible(true);
        processWriteAuthorizedEvent.invoke(bridge, event);

        PaymentProof proof = bridge.verifyPayment(proposalId);
        assertNotNull(proof);
        assertEquals(proposalId, proof.getProposalId());
        assertEquals(payer, proof.getFromAddress());
        assertEquals(contractAddress, proof.getContractAddress());
        assertEquals(amount.toString(), proof.getAmountWei());
        assertEquals(ValidatorEarningsTracker.PaymentTier.STANDARD, proof.getPaymentTier());
        assertEquals(PaymentProof.ProposalKind.DELETE, proof.getProposalKind());
        assertEquals(PaymentProof.PaymentToken.USDC, proof.getPaymentToken());
        assertEquals(0, proof.getCapabilityFlags());
        assertEquals(blockNumber, proof.getBlockNumber());
    }

    @Test
    public void testProposalSettledWriteLogPreservesCapabilityFlags() throws Exception {
        String contractAddress = "0x1234567890abcdef1234567890abcdef12345678";
        String proposalId = "0xffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff";
        String payer = "0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        String preferredValidator = "0xcccccccccccccccccccccccccccccccccccccccc";
        BigInteger amount = new BigInteger("1000000000000000");
        long blockNumber = 123790L;
        String txHash = "0xabababababababababababababababababababababababababababababababab";

        EventDrivenEvmBridge bridge = new EventDrivenEvmBridge("sepolia", contractAddress, false);

        Log ethLog = new Log();
        ethLog.setAddress(contractAddress);
        ethLog.setBlockNumber(hex(blockNumber));
        ethLog.setTransactionHash(txHash);
        ethLog.setTopics(Arrays.asList(
            EventEncoder.encode(PROPOSAL_SETTLED_EVENT),
            proposalId,
            paddedAddressTopic(payer),
            paddedUint(BigInteger.ZERO)
        ));
        ethLog.setData("0x"
            + paddedUint(BigInteger.ZERO)
            + paddedUint(BigInteger.valueOf(2L))
            + paddedUint(amount)
            + paddedUint(BigInteger.ONE)
            + paddedAddressWord(preferredValidator)
            + paddedUint(BigInteger.valueOf(1_710_000_124L)));

        Method parsePaymentLog = EventDrivenEvmBridge.class.getDeclaredMethod("parsePaymentLog", Log.class);
        parsePaymentLog.setAccessible(true);
        EventDrivenEvmBridge.WriteAuthorizedEvent event =
            (EventDrivenEvmBridge.WriteAuthorizedEvent) parsePaymentLog.invoke(bridge, ethLog);
        assertNotNull(event);

        Method processWriteAuthorizedEvent = EventDrivenEvmBridge.class.getDeclaredMethod(
            "processWriteAuthorizedEvent",
            EventDrivenEvmBridge.WriteAuthorizedEvent.class
        );
        processWriteAuthorizedEvent.setAccessible(true);
        processWriteAuthorizedEvent.invoke(bridge, event);

        PaymentProof proof = bridge.verifyPayment(proposalId);
        assertNotNull(proof);
        assertEquals(PaymentProof.ProposalKind.WRITE, proof.getProposalKind());
        assertEquals(PaymentProof.PaymentToken.ETH, proof.getPaymentToken());
        assertEquals(ValidatorEarningsTracker.PaymentTier.PRIORITY, proof.getPaymentTier());
        assertEquals(1, proof.getCapabilityFlags());
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
