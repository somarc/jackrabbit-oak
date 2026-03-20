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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class BlockchainConfigApiHandlerTest {

    private static final String PROP_MODE = "oak.blockchain.mode";
    private static final String PROP_RPC = "oak.blockchain.rpcUrl";
    private static final String PROP_GAS_PRICE_GWEI = "oak.blockchain.gasPriceGwei";

    @Before
    public void setUp() {
        System.clearProperty(PROP_MODE);
        System.clearProperty(PROP_RPC);
        System.clearProperty(PROP_GAS_PRICE_GWEI);
        BlockchainConfig.reset();
    }

    @After
    public void tearDown() {
        System.clearProperty(PROP_MODE);
        System.clearProperty(PROP_RPC);
        System.clearProperty(PROP_GAS_PRICE_GWEI);
        BlockchainConfig.reset();
    }

    @Test
    public void testHandleReturnsSepoliaConfig() throws Exception {
        System.setProperty(PROP_MODE, "sepolia");
        System.setProperty(PROP_RPC, "https://example.invalid/rpc");
        System.setProperty(PROP_GAS_PRICE_GWEI, "3");
        BlockchainConfig.reset();

        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = new ServerContext(mock(FileStore.class), mock(NodeStore.class),
            Paths.get("/tmp/store"), "http://validator-1:8090");

        BlockchainConfigApiHandler handler = new BlockchainConfigApiHandler(context);
        handler.handle(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"mode\":\"sepolia\""));
        assertTrue(json.contains("\"chainId\":11155111"));
        assertTrue(json.contains("\"rpcUrl\":\"https://example.invalid/rpc\""));
        assertTrue(json.contains("\"validatorUrl\":\"http://validator-1:8090\""));
        assertTrue(json.contains("\"configSource\":"));
        assertTrue(json.contains("\"gasModel\":"));
        assertTrue(json.contains("\"gasPriceGwei\":3"));
        assertTrue(json.contains("\"estimatedTotalWei\":"));
    }
}
