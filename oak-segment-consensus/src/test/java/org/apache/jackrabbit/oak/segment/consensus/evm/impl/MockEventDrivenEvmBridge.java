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

import java.math.BigInteger;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.apache.jackrabbit.oak.segment.consensus.economics.ValidatorEarningsTracker;
import org.apache.jackrabbit.oak.segment.consensus.evm.PaymentProof;

/**
 * Test double for {@link EventDrivenEvmBridge}: payments arrive through {@link #simulateWriteAuthorizedEvent}
 * (applied by a background thread, like the Web3j subscription) or are auto-confirmed for UUID proposal ids.
 */
public class MockEventDrivenEvmBridge extends EventDrivenEvmBridge {

    private static final BigInteger BASE_FEE = new BigInteger("1000000000000000"); // 0.001 ETH
    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";

    private final BlockingQueue<WriteAuthorizedEvent> events = new LinkedBlockingQueue<>();
    private volatile boolean running;

    public MockEventDrivenEvmBridge() {
        this("sepolia", "0x742d35Cc6634C0532925a3b844Bc9e7595f0bEb0");
    }

    public MockEventDrivenEvmBridge(String networkName, String contractAddress) {
        super(networkName, contractAddress, new OakPaymentEventParser(),
            rpcUrl -> {
                throw new AssertionError("Web3j is not used by the mock bridge");
            },
            (threadName, delayMs, reconnectTask) -> {
                throw new AssertionError("Reconnect is not used by the mock bridge");
            });
    }

    @Override
    PaymentProof fetchPaymentFromChain(String proposalId) {
        if (!proposalId.matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")) {
            return null;
        }
        currentBlock++;
        processWriteAuthorizedEvent(new WriteAuthorizedEvent(proposalId, ZERO_ADDRESS, ZERO_ADDRESS, BASE_FEE,
            currentBlock, "0x" + proposalId.replace("-", "") + "0".repeat(32), null,
            PaymentProof.ProposalKind.WRITE, PaymentProof.PaymentToken.UNKNOWN, 0));
        return payments.get(proposalId);
    }

    public void simulateWriteAuthorizedEvent(String proposalId, String payer, String shardHash, BigInteger amount,
                                             long blockNumber, String txHash) {
        simulateWriteAuthorizedEvent(proposalId, payer, shardHash, amount, blockNumber, txHash, null);
    }

    public void simulateWriteAuthorizedEvent(String proposalId, String payer, String shardHash, BigInteger amount,
                                             long blockNumber, String txHash,
                                             ValidatorEarningsTracker.PaymentTier paymentTier) {
        events.offer(new WriteAuthorizedEvent(proposalId, payer, shardHash, amount, blockNumber, txHash, paymentTier,
            PaymentProof.ProposalKind.WRITE, PaymentProof.PaymentToken.UNKNOWN, 0));
    }

    /** Advances the simulated chain head to add confirmations. */
    public void advanceMockBlocks(long blocks) {
        if (blocks > 0) {
            currentBlock += blocks;
        }
    }

    @Override
    public void start() {
        running = true;
        Thread processor = new Thread(() -> {
            while (running) {
                try {
                    WriteAuthorizedEvent event = events.poll(1, TimeUnit.SECONDS);
                    if (event != null) {
                        processWriteAuthorizedEvent(event);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "MockEventProcessor");
        processor.setDaemon(true);
        processor.start();
    }

    @Override
    public void stop() {
        running = false;
        super.stop();
    }
}
