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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.abi.EventEncoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Event;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.core.methods.request.EthFilter;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.utils.Numeric;

import java.util.Arrays;

final class OakPaymentEventParser {

    private static final Logger log = LoggerFactory.getLogger(OakPaymentEventParser.class);

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

    private static final Event PROPOSAL_SETTLED_V5_EVENT = new Event("ProposalSettledV5",
        Arrays.asList(
            new TypeReference<Bytes32>(true) {},
            new TypeReference<Address>(true) {},
            new TypeReference<Uint8>() {},
            new TypeReference<Uint256>() {},
            new TypeReference<Uint32>() {}
        ));

    void addSupportedEventTopics(EthFilter filter) {
        filter.addOptionalTopics(
            EventEncoder.encode(PROPOSAL_PAID_EVENT),
            EventEncoder.encode(PROPOSAL_SETTLED_V5_EVENT)
        );
    }

    String maskRpcUrl(String url) {
        if (url == null) {
            return "null";
        }
        int lastSlash = url.lastIndexOf('/');
        if (lastSlash > 0 && lastSlash < url.length() - 8) {
            String key = url.substring(lastSlash + 1);
            if (key.length() > 8) {
                return url.substring(0, lastSlash + 1)
                    + key.substring(0, 4)
                    + "***"
                    + key.substring(key.length() - 4);
            }
        }
        return url;
    }

    EventDrivenEvmBridge.WriteAuthorizedEvent parsePaymentLog(Log ethLog, long currentBlock) {
        if (ethLog.getTopics() == null || ethLog.getTopics().isEmpty()) {
            return null;
        }
        String signature = ethLog.getTopics().get(0);
        if (EventEncoder.encode(PROPOSAL_PAID_EVENT).equalsIgnoreCase(signature)) {
            return parseProposalPaidLog(ethLog, currentBlock);
        }
        if (EventEncoder.encode(PROPOSAL_SETTLED_V5_EVENT).equalsIgnoreCase(signature)) {
            return parseProposalSettledV5Log(ethLog, currentBlock);
        }
        return null;
    }

    private EventDrivenEvmBridge.WriteAuthorizedEvent parseProposalPaidLog(Log ethLog, long currentBlock) {
        if (ethLog.getTopics() == null || ethLog.getTopics().size() < 3) {
            return null;
        }
        String data = ethLog.getData();
        if (data == null || data.length() < 130) {
            return null;
        }

        return new EventDrivenEvmBridge.WriteAuthorizedEvent(
            ethLog.getTopics().get(1),
            "0x" + ethLog.getTopics().get(2).substring(26),
            Numeric.toBigInt(data.substring(0, 66)),
            logBlockNumber(ethLog, currentBlock),
            ethLog.getTransactionHash(),
            decodePaymentTier(data.substring(66, 130)),
            PaymentProof.ProposalKind.WRITE,
            decodePaymentToken(ethLog.getTopics().size() > 3 ? ethLog.getTopics().get(3) : null),
            0,
            ethLog.getAddress()
        );
    }

    private EventDrivenEvmBridge.WriteAuthorizedEvent parseProposalSettledV5Log(Log ethLog, long currentBlock) {
        if (ethLog.getTopics() == null || ethLog.getTopics().size() < 3) {
            return null;
        }
        String data = ethLog.getData();
        if (data == null || data.length() < 194) {
            return null;
        }

        PaymentProof.ProposalKind proposalKind = decodeProposalKind(dataWord(data, 0));
        if (proposalKind == null) {
            return null;
        }

        return new EventDrivenEvmBridge.WriteAuthorizedEvent(
            ethLog.getTopics().get(1),
            "0x" + ethLog.getTopics().get(2).substring(26),
            Numeric.toBigInt(dataWord(data, 1)),
            logBlockNumber(ethLog, currentBlock),
            ethLog.getTransactionHash(),
            null,
            proposalKind,
            PaymentProof.PaymentToken.ETH,
            Numeric.toBigInt(dataWord(data, 2)).intValue(),
            ethLog.getAddress()
        );
    }

    /** The including block comes from the log itself, never from the event's caller-controlled data words. */
    private static long logBlockNumber(Log ethLog, long currentBlock) {
        return ethLog.getBlockNumber() != null ? ethLog.getBlockNumber().longValue() : currentBlock;
    }

    private static String dataWord(String data, int wordIndex) {
        int start = 2 + (wordIndex * 64);
        int end = start + 64;
        if (data == null || data.length() < end) {
            return null;
        }
        return "0x" + data.substring(start, end);
    }

    private PaymentProof.ProposalKind decodeProposalKind(String encodedWord) {
        if (encodedWord == null || encodedWord.isEmpty()) {
            return null;
        }
        int code = Numeric.toBigInt(encodedWord).intValue();
        switch (code) {
            case 0:
                return PaymentProof.ProposalKind.WRITE;
            case 1:
                return PaymentProof.ProposalKind.DELETE;
            default:
                log.warn("Unknown proposal kind code in ProposalSettledV5 event: {}", code);
                return null;
        }
    }

    private ValidatorEarningsTracker.PaymentTier decodePaymentTier(String encodedWord) {
        if (encodedWord == null || encodedWord.isEmpty()) {
            return null;
        }
        int code = Numeric.toBigInt(encodedWord).intValue();
        switch (code) {
            case 0:
                return ValidatorEarningsTracker.PaymentTier.STANDARD;
            case 1:
                return ValidatorEarningsTracker.PaymentTier.EXPRESS;
            case 2:
                return ValidatorEarningsTracker.PaymentTier.PRIORITY;
            default:
                log.warn("Unknown payment tier code in ProposalPaid event: {}", code);
                return null;
        }
    }

    private PaymentProof.PaymentToken decodePaymentToken(String encodedTopicOrWord) {
        if (encodedTopicOrWord == null || encodedTopicOrWord.isEmpty()) {
            return PaymentProof.PaymentToken.UNKNOWN;
        }
        int code = Numeric.toBigInt(encodedTopicOrWord).intValue();
        switch (code) {
            case 0:
                return PaymentProof.PaymentToken.ETH;
            case 1:
                return PaymentProof.PaymentToken.USDC;
            default:
                log.warn("Unknown payment token code in payment event: {}", code);
                return PaymentProof.PaymentToken.UNKNOWN;
        }
    }
}
