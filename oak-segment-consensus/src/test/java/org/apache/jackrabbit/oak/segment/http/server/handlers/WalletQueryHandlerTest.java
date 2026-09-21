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

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class WalletQueryHandlerTest {

    private static final String WALLET_1 = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String WALLET_2 = "0xabcdefabcdefabcdefabcdefabcdefabcdefabcd";

    @Test
    public void testHandleWalletStatsReturnsSpecificWalletMetadata() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        seedWallet(nodeStore, WALLET_1, 2L, 7L);

        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("wallet")).thenReturn(WALLET_1);

        WalletQueryHandler handler = new WalletQueryHandler(newContext(nodeStore));
        handler.handleWalletStats(request, response);

        verify(response).setContentType("application/json");
        String json = body.toString();
        assertTrue(json.contains("\"wallet\":\"" + WALLET_1 + "\""));
        assertTrue(json.contains("\"path\":\"" + WalletPathUtil.getShardRoot(WALLET_1) + "\""));
        assertTrue(json.contains("\"contentCount\":2"));
        assertTrue(json.contains("\"totalWrites\":7"));
    }

    @Test
    public void testHandleWalletStatsReturnsErrorWhenWalletMissing() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("wallet")).thenReturn(WALLET_1);

        WalletQueryHandler handler = new WalletQueryHandler(newContext(new MemoryNodeStore()));
        handler.handleWalletStats(request, response);

        assertTrue(body.toString().contains("\"error\":\"Wallet not found\""));
    }

    @Test
    public void testHandleWalletStatsReturnsAllWallets() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        seedWallet(nodeStore, WALLET_1, 2L, 7L);
        seedWallet(nodeStore, WALLET_2, 4L, 9L);

        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = mock(HttpServletRequest.class);

        WalletQueryHandler handler = new WalletQueryHandler(newContext(nodeStore));
        handler.handleWalletStats(request, response);

        String json = body.toString();
        assertTrue(json.contains("\"wallets\":["));
        assertTrue(json.contains("\"wallet\":\"" + WALLET_1 + "\""));
        assertTrue(json.contains("\"wallet\":\"" + WALLET_2 + "\""));
    }

    @Test
    public void testHandleWalletContentRequiresWalletParameter() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = mock(HttpServletRequest.class);

        WalletQueryHandler handler = new WalletQueryHandler(newContext(new MemoryNodeStore()));
        handler.handleWalletContent(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("Missing wallet parameter"));
    }

    @Test
    public void testHandleWalletContentReturnsContentItems() throws Exception {
        MemoryNodeStore nodeStore = new MemoryNodeStore();
        seedWallet(nodeStore, WALLET_1, 2L, 7L);

        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("wallet")).thenReturn(WALLET_1);

        WalletQueryHandler handler = new WalletQueryHandler(newContext(nodeStore));
        handler.handleWalletContent(request, response);

        verify(response).setContentType("application/json");
        String json = body.toString();
        assertTrue(json.contains("\"content\":["));
        assertTrue(json.contains("\"name\":\"doc-1\""));
        assertTrue(json.contains("\"contentType\":\"fragment\""));
        assertTrue(json.contains("\"message\":\"hello\""));
    }

    private static ServerContext newContext(MemoryNodeStore nodeStore) {
        return new ServerContext(
            mock(FileStore.class),
            nodeStore,
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }

    private static void seedWallet(MemoryNodeStore nodeStore, String wallet, long contentCount, long totalWrites) throws Exception {
        String[] levels = WalletPathUtil.getShardLevels(wallet);
        NodeBuilder root = nodeStore.getRoot().builder();
        NodeBuilder walletNode = root.child("oak-chain")
            .child(levels[0])
            .child(levels[1])
            .child(levels[2])
            .child(wallet);
        walletNode.setProperty("contentCount", contentCount);
        walletNode.setProperty("totalWrites", totalWrites);
        walletNode.setProperty("walletCreated", 100L);
        walletNode.setProperty("lastWrite", 200L);
        walletNode.setProperty("nodeType", "wallet-root");
        walletNode.child("content").child("doc-1")
            .setProperty("contentType", "fragment")
            .setProperty("timestamp", 300L)
            .setProperty("message", "hello");
        nodeStore.merge(root, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }
}
