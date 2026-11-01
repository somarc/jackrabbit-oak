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
package org.apache.jackrabbit.oak.segment.consensus.mount;

import org.apache.jackrabbit.oak.segment.consensus.federation.DirectoryEntry;
import org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures;
import org.junit.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.util.List;
import java.util.Map;

import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.ENTERPRISE_B;
import static org.apache.jackrabbit.oak.segment.consensus.federation.FederationFixtures.NAMESPACE_B;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class FederatedMountStoreTest {

    static final String PRIMARY = "http://b0:8190";
    static final String SECONDARY = "http://b1:8190";

    @Test
    public void testNothingIsReadOrConnectedBeforeAnEndpointIsVerified() {
        FakeRemotes remotes = new FakeRemotes().serve(PRIMARY, "primary").serve(SECONDARY, "secondary");
        FederatedMountStore store = remotes.mount(entryB(PRIMARY, SECONDARY));

        assertFalse(store.getRoot().exists());
        assertEquals(0, remotes.connects(PRIMARY));
        assertEquals(0, remotes.probes(PRIMARY));

        RemoteMountStatus status = store.status(List.of(NAMESPACE_B), 4L);
        assertFalse(status.available());
        assertNull(status.federation().activeEndpoint());
        assertFalse(status.federation().verified());
        assertTrue(status.lastError().contains(PRIMARY + ": identity not verified yet"));
    }

    @Test
    public void testReadsUseTheFirstVerifiedEndpointAndLeaveLaterOnesAlone() {
        FakeRemotes remotes = new FakeRemotes().serve(PRIMARY, "primary").serve(SECONDARY, "secondary");
        FederatedMountStore store = remotes.mount(entryB(PRIMARY, SECONDARY));

        store.maintain();

        assertEquals("primary", FakeRemotes.servedBy(store.getRoot()));
        assertEquals(0, remotes.probes(SECONDARY));
        assertEquals(0, remotes.connects(SECONDARY));
        RemoteMountStatus status = store.status(List.of(NAMESPACE_B), 4L);
        assertTrue(status.available());
        assertEquals(PRIMARY, status.endpoint());
        assertEquals(PRIMARY, status.federation().activeEndpoint());
        assertNull(status.lastError());
    }

    @Test
    public void testFailsOverInOrderWhenTheActiveEndpointTripsAndReverifiesAfterItsBreakerResets() throws Exception {
        FakeRemotes remotes = new FakeRemotes().serve(PRIMARY, "primary").serve(SECONDARY, "secondary");
        FederatedMountStore store = remotes.mount(entryB(PRIMARY, SECONDARY));
        store.maintain();
        assertEquals(1, remotes.probes(PRIMARY));

        remotes.down(PRIMARY, true);
        assertEquals("the last known root is kept, reported unavailable", "primary",
            FakeRemotes.servedBy(store.getRoot()));
        RemoteMountStatus tripped = store.status(List.of(NAMESPACE_B), 4L);
        assertFalse(tripped.available());
        assertEquals("OPEN", tripped.federation().endpoints().get(0).circuit());
        assertFalse("a tripped endpoint loses its verification", tripped.federation().endpoints().get(0).verified());

        store.maintain();
        assertEquals("secondary", FakeRemotes.servedBy(store.getRoot()));
        assertEquals(SECONDARY, store.status(List.of(NAMESPACE_B), 4L).federation().activeEndpoint());
        assertEquals("primary is not re-checked while its breaker is open", 1, remotes.probes(PRIMARY));

        remotes.down(PRIMARY, false);
        Thread.sleep(FakeRemotes.RESET_MS + 20L);
        store.maintain();

        assertEquals("primary is checked again once its breaker resets", 2, remotes.probes(PRIMARY));
        assertEquals("primary", FakeRemotes.servedBy(store.getRoot()));
        assertEquals(PRIMARY, store.status(List.of(NAMESPACE_B), 4L).federation().activeEndpoint());
    }

    @Test
    public void testAnEndpointThatFailsTheIdentityCheckIsNeverConnected() {
        FakeRemotes remotes = new FakeRemotes().serve(PRIMARY, "impostor").serve(SECONDARY, "secondary")
            .reject(PRIMARY, new IOException("identity mismatch: endpoint serves enterprise 0xcc"));
        FederatedMountStore store = remotes.mount(entryB(PRIMARY, SECONDARY));

        store.maintain();

        assertEquals("secondary", FakeRemotes.servedBy(store.getRoot()));
        assertEquals(0, remotes.connects(PRIMARY));
        RemoteMountStatus.EndpointState primary = store.status(List.of(NAMESPACE_B), 4L).federation().endpoints().get(0);
        assertFalse(primary.verified());
        assertEquals("identity mismatch: endpoint serves enterprise 0xcc", primary.lastError());
    }

    @Test
    public void testAMountWithNoUsableEndpointIsUnavailableWithEveryReason() {
        FakeRemotes remotes = new FakeRemotes()
            .reject(PRIMARY, new IOException("identity mismatch: endpoint reports cluster 0x01"))
            .reject(SECONDARY, new ConnectException("Connection refused"));
        FederatedMountStore store = remotes.mount(entryB(PRIMARY, SECONDARY));

        store.maintain();

        assertFalse(store.getRoot().exists());
        RemoteMountStatus status = store.status(List.of(NAMESPACE_B), 4L);
        assertFalse(status.available());
        assertEquals(PRIMARY + ": identity mismatch: endpoint reports cluster 0x01; "
            + SECONDARY + ": Connection refused", status.lastError());
        Map<String, Object> map = status.toMap();
        assertEquals(ENTERPRISE_B, map.get("enterpriseId"));
        assertEquals(List.of(NAMESPACE_B), map.get("namespaces"));
        assertEquals(4L, map.get("directoryEpoch"));
        assertEquals(false, map.get("verified"));
        assertNull(map.get("activeEndpoint"));
        assertEquals(2, ((List<?>) map.get("endpoints")).size());
    }

    @Test
    public void testCloseClosesEveryEndpointStore() throws Exception {
        FakeRemotes remotes = new FakeRemotes().serve(PRIMARY, "primary").serve(SECONDARY, "secondary");
        FederatedMountStore store = remotes.mount(entryB(PRIMARY, SECONDARY));
        store.maintain();

        store.close();

        assertTrue(remotes.closed(PRIMARY));
        assertEquals("closed", store.status(List.of(NAMESPACE_B), 4L).federation().endpoints().get(0).lastError());
    }

    static DirectoryEntry entryB(String... endpoints) {
        return DirectoryEntry.fromMap(FederationFixtures.entry(ENTERPRISE_B, "ACTIVE", List.of(endpoints),
            List.of(NAMESPACE_B), List.of()));
    }
}
