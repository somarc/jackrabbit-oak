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
package org.apache.jackrabbit.oak.segment.consensus.sharding;

import jakarta.servlet.http.HttpServletResponse;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.federation.Charter;
import org.apache.jackrabbit.oak.segment.consensus.federation.DirectorySnapshot;
import org.apache.jackrabbit.oak.segment.consensus.federation.FederationCoordinator;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.junit.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Paths;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_A2;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_B;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.STRANGER;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.WRITER_A;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.charterA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.directory;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryA;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.entryB;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.store;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ShardWriteAuthorityEnforcerTest {

    private static final String ROUTE = "/v1/propose-write";

    private final StringWriter body = new StringWriter();
    private final HttpServletResponse response = mock(HttpServletResponse.class);

    private ServerContext context(Charter charter, DirectorySnapshot directory) throws Exception {
        when(response.getWriter()).thenAnswer(invocation -> new PrintWriter(body, true));
        MemoryNodeStore store = store(charter, directory);
        return new ServerContext(mock(FileStore.class), store, Paths.get("/tmp/store"), "http://localhost:8090");
    }

    private boolean allow(ServerContext context, String signer, String namespace) throws Exception {
        return ShardWriteAuthorityEnforcer.allowLocalWrite(context, signer, namespace, ROUTE, response);
    }

    @Test
    public void unfederatedClustersKeepTheLegacyRules() throws Exception {
        ServerContext context = context(null, null);
        assertTrue(allow(context, STRANGER, STRANGER));
        assertTrue(ShardWriteAuthorityEnforcer.allowLocalWrite(context, STRANGER, ROUTE, response));
        assertFalse(allow(context, STRANGER, NAMESPACE_A));
        verify(response).setStatus(HttpServletResponse.SC_BAD_REQUEST);
        assertTrue(body.toString().contains("namespace_requires_federation"));
    }

    @Test
    public void federatedClustersAllowOwnersAndWriters() throws Exception {
        ServerContext context = context(charterA(), directory(1, entryA("ACTIVE"), entryB()));
        assertTrue(allow(context, NAMESPACE_A, NAMESPACE_A));
        assertTrue(allow(context, WRITER_A, NAMESPACE_A2));
        verify(response, never()).setStatus(org.mockito.ArgumentMatchers.anyInt());
        assertEquals(0L, context.apiRejectedRequests.get());
    }

    @Test
    public void redirectsToTheOwningEnterpriseCluster() throws Exception {
        ServerContext context = context(charterA(), directory(1, entryA("ACTIVE"), entryB()));
        assertFalse(allow(context, NAMESPACE_B, NAMESPACE_B));
        verify(response).setStatus(HttpServletResponse.SC_TEMPORARY_REDIRECT);
        verify(response).setHeader("Location", "http://b0:8190" + ROUTE);
        assertTrue(body.toString().contains("\"code\":\"wrong_cluster\""));
        assertTrue(body.toString().contains(NAMESPACE_B));
        assertEquals(1L, context.apiRejectedRequests.get());
    }

    @Test
    public void refusesUnclaimedNamespacesAndUnlistedWriters() throws Exception {
        ServerContext context = context(charterA(), directory(1, entryA("ACTIVE"), entryB()));
        assertFalse(allow(context, STRANGER, STRANGER));
        assertTrue(body.toString().contains("namespace_unclaimed"));
        assertFalse(allow(context, STRANGER, NAMESPACE_A));
        assertTrue(body.toString().contains("writer_not_authorized"));
        verify(response, org.mockito.Mockito.times(2)).setStatus(HttpServletResponse.SC_FORBIDDEN);
    }

    @Test
    public void refusesWritesWhileTheEnterpriseIsInactive() throws Exception {
        ServerContext context = context(charterA(), directory(1, entryA("SUSPENDED")));
        assertFalse(allow(context, NAMESPACE_A, NAMESPACE_A));
        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(body.toString().contains("enterprise_inactive"));
    }

    @Test
    public void aMemberConfiguredForAnotherCharterRefusesWrites() throws Exception {
        ServerContext context = context(charterA(), directory(1, entryA("ACTIVE")));
        Charter other = Charter.of(ENTERPRISE_A, "local", 0L, Charter.STATIC_REGISTRY);
        FederationCoordinator coordinator = new FederationCoordinator(other, null, context::authoritativeRoot,
            new FederationCoordinator.Proposer() {
                @Override
                public boolean canPropose() {
                    return false;
                }

                @Override
                public boolean propose(int templateId, String json) {
                    return false;
                }
            }, System::currentTimeMillis, 1000L);
        coordinator.tick();
        context.federationCoordinator = coordinator;
        assertFalse(allow(context, NAMESPACE_A, NAMESPACE_A));
        verify(response).setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        assertTrue(body.toString().contains("charter_mismatch"));
    }
}
