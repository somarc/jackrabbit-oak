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

import java.io.IOException;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.junit.Test;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthChainId;
import org.web3j.protocol.core.methods.response.EthGetCode;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class RpcDomainAttestorTest {

    private static final String CONTRACT = "0x5FbDB2315678afecb367f032d93F642f64180aa3";
    private static final String CODE = "0x6080604052348015600f57600080fd5b50";

    @Test
    public void attestsMatchingChainAndPinnedCodeHash() throws Exception {
        Web3j web3j = web3j(31337L, CODE);

        String pinnedUpperHex = "0x" + Hash.sha3(CODE).substring(2).toUpperCase();
        new RpcDomainAttestor(web3j, 31337L).attest(BlockchainConfig.Mode.LOCAL, CONTRACT, pinnedUpperHex);

        assertEquals(Hash.sha3(CODE), new RpcDomainAttestor(web3j, 31337L).attestContract(CONTRACT, null));
    }

    @Test
    public void unpinnedContractIsAcceptedOutsideMainnet() throws Exception {
        new RpcDomainAttestor(web3j(11155111L, CODE), 11155111L).attest(BlockchainConfig.Mode.SEPOLIA, CONTRACT, null);
    }

    @Test
    public void chainIdMismatchFails() throws Exception {
        RpcDomainAttestor attestor = new RpcDomainAttestor(web3j(1L, CODE), 11155111L);

        RpcDomainAttestor.AttestationException e = assertThrows(RpcDomainAttestor.AttestationException.class,
            () -> attestor.attest(BlockchainConfig.Mode.SEPOLIA, CONTRACT, null));

        assertTrue(e.getMessage(), e.getMessage().contains("chainId 1, expected 11155111"));
    }

    @Test
    public void codeHashMismatchFails() throws Exception {
        RpcDomainAttestor attestor = new RpcDomainAttestor(web3j(31337L, CODE), 31337L);
        String pinned = "0x" + "11".repeat(32);

        RpcDomainAttestor.AttestationException e = assertThrows(RpcDomainAttestor.AttestationException.class,
            () -> attestor.attest(BlockchainConfig.Mode.LOCAL, CONTRACT, pinned));

        assertTrue(e.getMessage(), e.getMessage().contains("does not match the pinned " + pinned));
    }

    @Test
    public void emptyCodeFailsWithOrWithoutPinnedHash() throws Exception {
        for (String code : new String[] {"0x", "", null}) {
            RpcDomainAttestor attestor = new RpcDomainAttestor(web3j(31337L, code), 31337L);

            RpcDomainAttestor.AttestationException e = assertThrows(RpcDomainAttestor.AttestationException.class,
                () -> attestor.attest(BlockchainConfig.Mode.LOCAL, CONTRACT, null));

            assertTrue(e.getMessage(), e.getMessage().contains("No contract code at " + CONTRACT));
        }
    }

    @Test
    public void mainnetRequiresPinnedCodeHashBeforeAnyRpcCall() {
        Web3j web3j = mock(Web3j.class);

        RpcDomainAttestor.AttestationException e = assertThrows(RpcDomainAttestor.AttestationException.class,
            () -> new RpcDomainAttestor(web3j, 1L).attest(BlockchainConfig.Mode.MAINNET, CONTRACT, " "));

        assertTrue(e.getMessage(), e.getMessage().contains("requires a pinned contract code hash"));
        verifyNoInteractions(web3j);
    }

    @Test
    public void unreachableOrErroringEndpointFailsClosed() throws Exception {
        Web3j unreachable = mock(Web3j.class);
        Request<?, EthChainId> failing = request(null);
        when(failing.send()).thenThrow(new IOException("connection refused"));
        doReturn(failing).when(unreachable).ethChainId();
        assertTrue(assertThrows(RpcDomainAttestor.AttestationException.class,
            () -> new RpcDomainAttestor(unreachable, 31337L).attestChainId()).getMessage().contains("connection refused"));

        Web3j erroring = mock(Web3j.class);
        EthChainId error = new EthChainId();
        error.setError(new Response.Error(-32601, "method not found"));
        doReturn(request(error)).when(erroring).ethChainId();
        assertTrue(assertThrows(RpcDomainAttestor.AttestationException.class,
            () -> new RpcDomainAttestor(erroring, 31337L).attestChainId()).getMessage().contains("method not found"));
    }

    @Test
    public void getCodeIsReadAtLatest() throws Exception {
        Web3j web3j = web3j(31337L, CODE);
        new RpcDomainAttestor(web3j, 31337L).attestContract(CONTRACT, null);
        verify(web3j).ethGetCode(CONTRACT, DefaultBlockParameterName.LATEST);
    }

    static Web3j web3j(long chainId, String code) throws Exception {
        Web3j web3j = mock(Web3j.class);
        EthChainId chainIdResponse = new EthChainId();
        chainIdResponse.setResult("0x" + Long.toHexString(chainId));
        doReturn(request(chainIdResponse)).when(web3j).ethChainId();
        EthGetCode codeResponse = new EthGetCode();
        codeResponse.setResult(code);
        doReturn(request(codeResponse)).when(web3j).ethGetCode(anyString(), any());
        return web3j;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Response<?>> Request<?, T> request(T response) throws Exception {
        Request<?, T> request = mock(Request.class);
        when(request.send()).thenReturn(response);
        return request;
    }
}
