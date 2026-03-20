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
import org.apache.jackrabbit.oak.segment.http.server.model.ValidatorRegistration;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PeerDiscoveryHandlerTest {

    @Test
    public void testHandlePeerListStandaloneIncludesSelfAndRegisteredValidator() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        ServerContext context = newContext("http://validator-1:8090");
        context.registeredValidators.put("validator-2", new ValidatorRegistration("validator-2", "http://validator-2:8090"));

        PeerDiscoveryHandler handler = new PeerDiscoveryHandler(context);
        handler.handlePeerList(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        String json = body.toString();
        assertTrue(json.contains("\"validatorUrl\":\"http://validator-1:8090\""));
        assertTrue(json.contains("\"validatorUrl\":\"http://validator-2:8090\""));
        assertTrue(json.contains("\"status\":\"READY\""));
    }

    @Test
    public void testHandlePeerListStandaloneMarksStaleValidatorOffline() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        ServerContext context = newContext("http://validator-1:8090");
        ValidatorRegistration stale = new ValidatorRegistration("validator-2", "http://validator-2:8090");
        stale.lastSeen = System.currentTimeMillis() - 700_000L;
        context.registeredValidators.put("validator-2", stale);

        PeerDiscoveryHandler handler = new PeerDiscoveryHandler(context);
        handler.handlePeerList(response);

        String json = body.toString();
        assertTrue(json.contains("\"validatorUrl\":\"http://validator-2:8090\""));
        assertTrue(json.contains("\"status\":\"OFFLINE\""));
    }

    @Test
    public void testHandlePeerListWithAeronUsesClusterMembershipAndDeterministicIds() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        ServerContext context = newContext("http://validator-1:8090");
        context.myValidatorId = "0x1111111111111111111111111111111111111111";
        context.registeredValidators.put("validator-2", new ValidatorRegistration("validator-2", "http://validator-2:8090"));

        AeronConsensusEngine engine = mock(AeronConsensusEngine.class);
        when(engine.getNonVotingFollowers()).thenReturn(Collections.emptyList());
        when(engine.getAllFollowers()).thenReturn(Collections.singletonList("http://validator-2:8090"));
        when(engine.getCurrentLeader()).thenReturn("http://validator-1:8090");
        when(engine.getCurrentEpoch()).thenReturn(12);
        context.aeronConsensusEngine = engine;

        PeerDiscoveryHandler handler = new PeerDiscoveryHandler(context);
        handler.handlePeerList(response);

        String json = body.toString();
        assertTrue(json.contains("\"validatorId\":\"0x1111111111111111111111111111111111111111\""));
        assertTrue(json.contains("\"validatorUrl\":\"http://validator-2:8090\""));
        assertTrue(json.contains("\"status\":\"READY\""));
    }

    @Test
    public void testHandleNgrokUrlReturnsSelfUrl() throws Exception {
        StringWriter body = new StringWriter();
        HttpServletResponse response = responseWithBody(body);
        PeerDiscoveryHandler handler = new PeerDiscoveryHandler(newContext("https://public.ngrok.app"));

        handler.handleNgrokUrl(response);

        verify(response).setStatus(HttpServletResponse.SC_OK);
        assertTrue(body.toString().contains("https://public.ngrok.app"));
    }

    private static ServerContext newContext(String selfUrl) {
        return new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("/tmp/store"),
            selfUrl
        );
    }

    private static HttpServletResponse responseWithBody(StringWriter body) throws Exception {
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(response.getWriter()).thenReturn(new PrintWriter(body));
        return response;
    }
}
