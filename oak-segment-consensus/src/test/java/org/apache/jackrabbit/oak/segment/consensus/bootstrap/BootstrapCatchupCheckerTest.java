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
package org.apache.jackrabbit.oak.segment.consensus.bootstrap;

import com.sun.net.httpserver.HttpServer;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class BootstrapCatchupCheckerTest {

    private final BootstrapCatchupChecker checker = new BootstrapCatchupChecker();
    private HttpServer server;

    @After
    public void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void returnsTrueWhenPrimaryUrlIsMissing() {
        assertTrue(checker.isCaughtUp(mock(FileStore.class), null));
    }

    @Test
    public void returnsTrueWhenBothStoresAreEmpty() throws Exception {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.size()).thenReturn(0L);
        when(fileStore.getHead().getRecordId().toString10()).thenThrow(new IllegalStateException("empty"));
        server = headServer(200, "{\"latestHead\": null}");

        assertTrue(checker.isCaughtUp(fileStore, primaryUrl()));
    }

    @Test
    public void returnsFalseWhenPrimaryHasDataAndLocalStoreIsEmpty() throws Exception {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.size()).thenReturn(0L);
        when(fileStore.getHead().getRecordId().toString10()).thenThrow(new IllegalStateException("empty"));
        server = headServer(200, "{\"latestHead\":\"primary-uuid:99\"}");

        assertFalse(checker.isCaughtUp(fileStore, primaryUrl()));
    }

    @Test
    public void returnsTrueWhenHeadUuidsMatch() throws Exception {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.size()).thenReturn(1024L);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn("shared-uuid:1");
        server = headServer(200, "{\"committedHead\":\"shared-uuid:42\"}");

        assertTrue(checker.isCaughtUp(fileStore, primaryUrl()));
    }

    @Test
    public void returnsFalseWhenHeadUuidsDiffer() throws Exception {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.size()).thenReturn(1024L);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn("local-uuid:1");
        server = headServer(200, "{\"latestHead\":\"primary-uuid:42\"}");

        assertFalse(checker.isCaughtUp(fileStore, primaryUrl()));
    }

    @Test
    public void returnsFalseWhenPrimaryHeadEndpointIsUnavailable() throws Exception {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.size()).thenReturn(1024L);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn("local-uuid:1");
        server = headServer(503, "{\"error\":\"unavailable\"}");

        assertFalse(checker.isCaughtUp(fileStore, primaryUrl()));
    }

    private HttpServer headServer(int status, String body) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/v1/head", exchange -> {
            byte[] bytes = body.getBytes("UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        });
        httpServer.start();
        return httpServer;
    }

    private String primaryUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
