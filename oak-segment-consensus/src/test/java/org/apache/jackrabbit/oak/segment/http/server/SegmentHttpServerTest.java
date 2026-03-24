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
package org.apache.jackrabbit.oak.segment.http.server;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.eclipse.jetty.server.Server;
import org.junit.Test;

import java.net.URI;
import java.nio.file.Paths;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class SegmentHttpServerTest {

    @Test
    public void testStartDelegatesToJettyServer() throws Exception {
        Server server = mock(Server.class);
        when(server.getURI()).thenReturn(new URI("http://localhost:8090"));

        SegmentHttpServer httpServer = newServer(server, mock(RequestRouter.class));

        httpServer.start();

        verify(server).start();
    }

    @Test
    public void testStopClosesRouterAfterStoppingServer() throws Exception {
        Server server = mock(Server.class);
        RequestRouter router = mock(RequestRouter.class);
        SegmentHttpServer httpServer = newServer(server, router);

        httpServer.stop();

        verify(server).stop();
        verify(router).close();
    }

    @Test
    public void testStopStillClosesRouterWhenServerStopFails() throws Exception {
        Server server = mock(Server.class);
        RequestRouter router = mock(RequestRouter.class);
        doThrow(new Exception("boom")).when(server).stop();
        SegmentHttpServer httpServer = newServer(server, router);

        try {
            httpServer.stop();
        } catch (Exception e) {
            assertEquals("boom", e.getMessage());
        }

        verify(server).stop();
        verify(router).close();
    }

    private SegmentHttpServer newServer(Server server, RequestRouter router) {
        FileStore fileStore = mock(FileStore.class);
        NodeStore nodeStore = mock(NodeStore.class);
        ServerContext context = new ServerContext(fileStore, nodeStore, Paths.get("/tmp"), "http://localhost:8090");
        return new SegmentHttpServer(server, context, router, new TlsConfiguration(), fileStore, nodeStore, Paths.get("/tmp"));
    }
}
