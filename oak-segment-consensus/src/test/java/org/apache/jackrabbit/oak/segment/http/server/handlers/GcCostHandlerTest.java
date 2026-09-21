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

import org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimate;
import org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimator;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.junit.Test;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class GcCostHandlerTest {

    @Test
    public void testHandleEstimateSuccess() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getParameter("revision")).thenReturn("r1");

        Map<String, Long> byTar = new LinkedHashMap<>();
        byTar.put("data00000a.tar", 123L);
        GCCostEstimate estimate = new GCCostEstimate(
            10L, 1024L * 1024L, 100L, 10L * 1024L * 1024L,
            new BigDecimal("1.23"), byTar
        );

        ServerContext context = new ServerContext(null, null, Paths.get("/tmp/store"), "http://localhost:8090");
        context.gcCostEstimator = mock(GCCostEstimator.class);
        when(context.gcCostEstimator.estimateCost("r1")).thenReturn(estimate);

        GcCostHandler handler = new GcCostHandler(context);
        handler.handleGCCostEstimate(request, response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"reclaimableSegmentCount\":10"));
        assertTrue(json.contains("\"estimatedCostUSDC\":\"1.23\""));
        assertTrue(json.contains("\"data00000a.tar\":123"));
    }

    @Test
    public void testHandleEstimateWhenEstimatorMissing() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        HttpServletRequest request = mock(HttpServletRequest.class);

        ServerContext context = new ServerContext(null, null, Paths.get("/tmp/store"), "http://localhost:8090");
        context.gcCostEstimator = null;

        GcCostHandler handler = new GcCostHandler(context);
        handler.handleGCCostEstimate(request, response);

        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        String json = body.toString();
        assertTrue(json.contains("\"code\":\"service_unavailable\""));
    }
}
