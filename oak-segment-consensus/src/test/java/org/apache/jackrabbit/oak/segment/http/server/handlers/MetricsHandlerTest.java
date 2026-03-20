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
import org.apache.jackrabbit.oak.segment.consensus.leader.ValidatorRole;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.junit.Test;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class MetricsHandlerTest {

    @Test
    public void testHandleMetricsWithEngine() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.getCurrentRole()).thenReturn(ValidatorRole.LEADER);
        when(engine.isLeader()).thenReturn(true);
        when(engine.getCurrentEpoch()).thenReturn(42);
        when(engine.getCurrentTerm()).thenReturn(7);
        when(engine.getReachableValidatorCount()).thenReturn(2);
        when(engine.getTotalMemberCount()).thenReturn(3);
        when(engine.getQuorumSize()).thenReturn(2);
        when(engine.getHeartbeatAgeMs()).thenReturn(123L);
        when(engine.isClusterHealthy()).thenReturn(true);
        when(engine.getUnhealthyReason()).thenReturn(null);
        Map<String, Object> lagStatus = new HashMap<>();
        lagStatus.put("role", "LEADER");
        lagStatus.put("myLogPosition", 10L);
        lagStatus.put("leaderLogPosition", 10L);
        lagStatus.put("replicationLag", 0L);
        lagStatus.put("lagThreshold", 5L);
        lagStatus.put("healthy", true);
        when(engine.getReplicationLagStatus()).thenReturn(lagStatus);

        MetricsHandler handler = new MetricsHandler(
            engine,
            Paths.get("/tmp/store"),
            Collections.singletonMap("c1", new Object()),
            Collections.singletonMap("v1", new Object()),
            new ServerContext(null, null, Paths.get("/tmp/store"), "http://localhost:8090")
        );

        handler.handleMetrics(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"role\":\"LEADER\""));
        assertTrue(json.contains("\"reachableValidators\":2"));
        assertTrue(json.contains("\"replicationLag\":0"));
        assertTrue(json.contains("\"registeredClients\":1"));
        assertTrue(json.contains("\"registeredValidators\":1"));
    }

    @Test
    public void testHandleMetricsWithoutEngine() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));

        MetricsHandler handler = new MetricsHandler(
            null,
            Paths.get("/tmp/store"),
            Collections.emptyMap(),
            Collections.emptyMap(),
            new ServerContext(null, null, Paths.get("/tmp/store"), "http://localhost:8090")
        );

        handler.handleMetrics(response);

        String json = body.toString();
        assertTrue(json.contains("\"consensus\":null"));
        assertTrue(json.contains("\"replication\":null"));
    }
}
