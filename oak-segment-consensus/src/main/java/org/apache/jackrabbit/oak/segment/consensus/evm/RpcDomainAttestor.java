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

import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetCode;

/**
 * Proves that an RPC endpoint serves the expected chain and that a contract address holds the expected bytecode,
 * before anything read through that endpoint is trusted. Checks {@code eth_chainId} against the expected chain id
 * and the keccak256 of {@code eth_getCode} (at {@code latest}) against an expected code hash; an address without
 * code always fails. Any failure, including an unreachable endpoint, is an {@link AttestationException}: callers
 * treat it as a startup failure.
 */
public final class RpcDomainAttestor {

    private static final Logger log = LoggerFactory.getLogger(RpcDomainAttestor.class);
    private static final AtomicBoolean WARNED_UNPINNED_CODE = new AtomicBoolean();

    private final Web3j web3j;
    private final long expectedChainId;

    public RpcDomainAttestor(@NotNull Web3j web3j, long expectedChainId) {
        this.web3j = web3j;
        this.expectedChainId = expectedChainId;
    }

    /**
     * Attests the chain and a contract for a configured mode. Modes that require a pinned code hash (mainnet)
     * fail without one; elsewhere an unpinned contract is accepted with a one-time warning.
     */
    public void attest(@NotNull BlockchainConfig.Mode mode, @NotNull String contractAddress,
                       @Nullable String expectedCodeHash) {
        boolean pinned = expectedCodeHash != null && !expectedCodeHash.trim().isEmpty();
        if (!pinned && mode.requiresContractCodeHash()) {
            throw new AttestationException(mode.getKey() + " mode requires a pinned contract code hash "
                + "(OAK_BLOCKCHAIN_CONTRACT_CODEHASH/oak.blockchain.contract.codehash)");
        }
        attestChainId();
        String actual = attestContract(contractAddress, pinned ? expectedCodeHash.trim() : null);
        if (!pinned && WARNED_UNPINNED_CODE.compareAndSet(false, true)) {
            log.warn("Contract {} code hash is not pinned; set OAK_BLOCKCHAIN_CONTRACT_CODEHASH={} to fail closed "
                + "on a redeployed or substituted contract", contractAddress, actual);
        }
    }

    public void attestChainId() {
        EthChainId response = send(() -> web3j.ethChainId().send(), "eth_chainId");
        BigInteger chainId = response.getChainId();
        if (chainId == null || !chainId.equals(BigInteger.valueOf(expectedChainId))) {
            throw new AttestationException("RPC endpoint reports chainId " + chainId + ", expected " + expectedChainId);
        }
    }

    /**
     * Attests that {@code address} holds code and, when {@code expectedCodeHash} is given, that its keccak256
     * matches.
     *
     * @return the keccak256 of the deployed code
     */
    @NotNull
    public String attestContract(@NotNull String address, @Nullable String expectedCodeHash) {
        EthGetCode response = send(() -> web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send(),
            "eth_getCode");
        String code = response.getCode();
        if (code == null || code.isEmpty() || "0x".equalsIgnoreCase(code)) {
            throw new AttestationException("No contract code at " + address + " on chain " + expectedChainId);
        }
        String actual = Hash.sha3(code);
        if (expectedCodeHash != null && !expectedCodeHash.equalsIgnoreCase(actual)) {
            throw new AttestationException("Contract " + address + " code hash " + actual
                + " does not match the pinned " + expectedCodeHash);
        }
        return actual;
    }

    private static <T extends Response<?>> T send(RpcCall<T> call, String method) {
        T response;
        try {
            response = call.send();
        } catch (Exception e) {
            throw new AttestationException("Cannot attest RPC domain: " + method + " failed: " + e.getMessage(), e);
        }
        if (response == null) {
            throw new AttestationException("Cannot attest RPC domain: " + method + " returned no response");
        }
        if (response.hasError()) {
            throw new AttestationException("Cannot attest RPC domain: " + method + " returned error "
                + response.getError().getMessage());
        }
        return response;
    }

    @FunctionalInterface
    private interface RpcCall<T> {
        T send() throws Exception;
    }

    /** The RPC endpoint or contract is not the configured domain, or could not be checked. */
    public static final class AttestationException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public AttestationException(String message) {
            super(message);
        }

        public AttestationException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
