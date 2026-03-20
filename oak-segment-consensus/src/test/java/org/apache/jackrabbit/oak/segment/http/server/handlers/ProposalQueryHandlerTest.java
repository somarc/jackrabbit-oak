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

import org.apache.jackrabbit.oak.segment.consensus.queue.DurabilityState;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalQueueManagerOptimized;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalState;
import org.apache.jackrabbit.oak.segment.consensus.queue.ProposalStatus;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ProposalQueryHandlerTest {

    private ProposalQueueManagerOptimized queueManager;
    private ProposalQueryHandler handler;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter body;

    @Before
    public void setUp() throws Exception {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            "http://localhost:8090"
        );
        queueManager = mock(ProposalQueueManagerOptimized.class);
        context.proposalQueueManager = queueManager;

        handler = new ProposalQueryHandler(context);
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        body = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(body));
    }

    @Test
    public void testGetProposalStatusRejectsMissingRequestUri() throws Exception {
        when(request.getRequestURI()).thenReturn(null);

        handler.handleGetProposalStatus(request, response);

        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("\"error\":\"Invalid proposal ID\""));
    }

    @Test
    public void testGetOperationStatusReturnsCommittedForAckedProcessedProposal() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/ops/operations/proposal-123");
        when(queueManager.getProposalStatus("proposal-123")).thenReturn(new ProposalStatus(
            "proposal-123",
            ProposalState.PROCESSED,
            "0xabc",
            1234L,
            42L,
            null,
            DurabilityState.ACKED,
            2345L,
            null,
            "head-123"
        ));

        handler.handleGetOperationStatus(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"contractVersion\":\"ops.v1\""));
        assertTrue(json.contains("\"operationId\":\"proposal-123\""));
        assertTrue(json.contains("\"state\":\"COMMITTED\""));
        assertTrue(json.contains("\"durabilityState\":\"ACKED\""));
        assertTrue(json.contains("\"error\":null"));
    }

    @Test
    public void testGetOperationStatusReturnsTimedOutRetryableError() throws Exception {
        when(request.getRequestURI()).thenReturn("/v1/ops/operations/proposal-timeout");
        when(queueManager.getProposalStatus("proposal-timeout")).thenReturn(new ProposalStatus(
            "proposal-timeout",
            ProposalState.REJECTED,
            null,
            4567L,
            null,
            "Timeout waiting for transaction confirmation",
            null,
            0L,
            null,
            null
        ));

        handler.handleGetOperationStatus(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"state\":\"TIMED_OUT\""));
        assertTrue(json.contains("\"error\":{\"code\":\"OPERATION_TIMED_OUT\""));
        assertTrue(json.contains("\"message\":\"Timeout waiting for transaction confirmation\""));
        assertTrue(json.contains("\"retryable\":true"));
    }

    @Test
    public void testGetOpsQueueSnapshotUsesWarmCacheWithinTtl() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pendingCount", 7);
        when(queueManager.getQueueStats()).thenReturn(stats);

        handler.handleGetOpsQueueSnapshot(response);
        body.getBuffer().setLength(0);

        handler.handleGetOpsQueueSnapshot(response);

        verify(queueManager, times(1)).getQueueStats();
        String json = body.toString();
        assertTrue(json.contains("\"contractVersion\":\"ops.v1\""));
        assertTrue(json.contains("\"cache\":{\"hit\":true,\"ttlMs\":1000}"));
        assertTrue(json.contains("\"degraded\":false"));
        assertTrue(json.contains("\"pendingCount\":7"));
    }

    @Test
    public void testGetOpsQueueSnapshotFallsBackToStaleCacheOnRefreshFailure() throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("pendingCount", 11);
        when(queueManager.getQueueStats())
            .thenReturn(stats)
            .thenThrow(new IllegalStateException("boom"));

        handler.handleGetOpsQueueSnapshot(response);
        ageQueueSnapshotCache(handler);
        body.getBuffer().setLength(0);

        handler.handleGetOpsQueueSnapshot(response);

        String json = body.toString();
        assertTrue(json.contains("\"degraded\":true"));
        assertTrue(json.contains("\"degradedReason\":\"STALE_CACHE_FALLBACK\""));
        assertTrue(json.contains("\"cache\":{\"hit\":true,\"ttlMs\":1000}"));
        assertTrue(json.contains("\"pendingCount\":11"));
    }

    private static void ageQueueSnapshotCache(ProposalQueryHandler handler) throws Exception {
        Field field = ProposalQueryHandler.class.getDeclaredField("cachedQueueStatsSourceTimestampMs");
        field.setAccessible(true);
        field.setLong(handler, System.currentTimeMillis() - 5000L);
    }
}
