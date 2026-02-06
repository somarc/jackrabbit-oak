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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class HealthHandlerTest {

    @Test
    public void testHandleHealthHealthyWithoutEngine() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = new ServerContext(mock(FileStore.class), mock(NodeStore.class),
            Paths.get("/tmp/store"), "http://localhost:8090");

        HealthHandler handler = new HealthHandler(
            context.fileStore,
            context.nodeStore,
            context.storeDirectory,
            null,
            Collections.emptyMap(),
            Collections.emptyMap(),
            context
        );

        handler.handleHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"status\": \"UP\""));
        assertTrue(json.contains("\"store\":"));
    }

    @Test
    public void testHandleHealthUnhealthyWithReason() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        ServerContext context = new ServerContext(mock(FileStore.class), mock(NodeStore.class),
            Paths.get("/tmp/store"), "http://localhost:8090");
        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.isClusterHealthy()).thenReturn(false);
        when(engine.getUnhealthyReason()).thenReturn("no_leader");
        when(engine.getReachableValidatorCount()).thenReturn(0);
        when(engine.getTotalMemberCount()).thenReturn(3);
        when(engine.getQuorumSize()).thenReturn(2);
        when(engine.getCurrentRole()).thenReturn(org.apache.jackrabbit.oak.segment.consensus.leader.ValidatorRole.FOLLOWER);
        when(engine.getCommittedHead()).thenReturn("");
        when(engine.getLatestHead()).thenReturn("");
        when(engine.getLatestEpochSeen()).thenReturn(-1);
        when(engine.getLastCommittedEpoch()).thenReturn(-1);
        context.aeronConsensusEngine = engine;

        HealthHandler handler = new HealthHandler(
            context.fileStore,
            context.nodeStore,
            context.storeDirectory,
            engine,
            Collections.emptyMap(),
            Collections.emptyMap(),
            context
        );

        handler.handleHealth(response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        String json = body.toString();
        assertTrue(json.contains("\"unhealthyReason\": \"no_leader\""));
        assertTrue(json.contains("\"clusterHealthy\": false"));
    }
}
