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
package org.apache.jackrabbit.oak.segment.consensus.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class BootstrapPreflightPlannerTest {

    @Test
    public void testPlanUsesReachablePeerWhenStandbyBootstrapEnabled() {
        RecordingProbe probe = new RecordingProbe(Collections.singletonList("http://validator-1:8090/health"));
        BootstrapPreflightPlanner.Decision decision = new BootstrapPreflightPlanner(probe::isReachable).plan(
            true,
            true,
            Collections.singletonList("http://validator-1:8090"),
            "",
            "",
            true,
            9001
        );

        assertTrue(decision.needsBootstrapBeforeBuild());
        assertTrue(decision.hasVerifiedReachablePeers());
        assertEquals("validator-1", decision.verifiedBootstrapPrimaryHost());
        assertEquals(8091, decision.verifiedBootstrapPrimaryPort());
        assertEquals(Collections.singletonList("http://validator-1:8090/health"), probe.visitedUrls);
    }

    @Test
    public void testPlanDoesNotBootstrapWhenStandbyBootstrapDisabled() {
        RecordingProbe probe = new RecordingProbe(Collections.singletonList("http://validator-1:8090/health"));
        BootstrapPreflightPlanner.Decision decision = new BootstrapPreflightPlanner(probe::isReachable).plan(
            true,
            true,
            Collections.singletonList("http://validator-1:8090"),
            "",
            "",
            false,
            9001
        );

        assertFalse(decision.needsBootstrapBeforeBuild());
        assertTrue(decision.hasVerifiedReachablePeers());
        assertEquals("validator-1", decision.verifiedBootstrapPrimaryHost());
        assertEquals(8091, decision.verifiedBootstrapPrimaryPort());
    }

    @Test
    public void testPlanFallsBackToDefaultPortWhenBootstrapPrimaryPortIsInvalid() {
        RecordingProbe probe = new RecordingProbe(Collections.singletonList("http://bootstrap-node:8090/health"));
        BootstrapPreflightPlanner.Decision decision = new BootstrapPreflightPlanner(probe::isReachable).plan(
            true,
            true,
            Collections.<String>emptyList(),
            "bootstrap-node",
            "not-a-port",
            true,
            9001
        );

        assertTrue(decision.needsBootstrapBeforeBuild());
        assertTrue(decision.hasVerifiedReachablePeers());
        assertEquals("bootstrap-node", decision.verifiedBootstrapPrimaryHost());
        assertEquals(8091, decision.verifiedBootstrapPrimaryPort());
        assertEquals(Collections.singletonList("http://bootstrap-node:8090/health"), probe.visitedUrls);
    }

    @Test
    public void testPlanSkipsProbeWhenStoreIsNotEmpty() {
        RecordingProbe probe = new RecordingProbe(Collections.singletonList("http://validator-1:8090/health"));
        BootstrapPreflightPlanner.Decision decision = new BootstrapPreflightPlanner(probe::isReachable).plan(
            true,
            false,
            Collections.singletonList("http://validator-1:8090"),
            "bootstrap-node",
            "8091",
            true,
            9001
        );

        assertFalse(decision.needsBootstrapBeforeBuild());
        assertFalse(decision.hasVerifiedReachablePeers());
        assertTrue(probe.visitedUrls.isEmpty());
    }

    @Test
    public void testPlanFallsBackToBootstrapPrimaryAfterUnreachablePeers() {
        RecordingProbe probe = new RecordingProbe(Collections.<String>emptyList());
        probe.markReachable("http://bootstrap-node:9000/health");

        BootstrapPreflightPlanner.Decision decision = new BootstrapPreflightPlanner(probe::isReachable).plan(
            true,
            true,
            Arrays.asList("http://validator-1:8090", "http://validator-2:8090"),
            "bootstrap-node",
            "9001",
            true,
            9001
        );

        assertTrue(decision.needsBootstrapBeforeBuild());
        assertTrue(decision.hasVerifiedReachablePeers());
        assertEquals("bootstrap-node", decision.verifiedBootstrapPrimaryHost());
        assertEquals(9001, decision.verifiedBootstrapPrimaryPort());
        assertEquals(Arrays.asList(
            "http://validator-1:8090/health",
            "http://validator-2:8090/health",
            "http://bootstrap-node:9000/health"
        ), probe.visitedUrls);
    }

    private static final class RecordingProbe {
        private final List<String> visitedUrls = new ArrayList<>();
        private final List<String> reachableUrls = new ArrayList<>();

        private RecordingProbe(List<String> reachableUrls) {
            this.reachableUrls.addAll(reachableUrls);
        }

        private void markReachable(String url) {
            if (!reachableUrls.contains(url)) {
                reachableUrls.add(url);
            }
        }

        private boolean isReachable(String url) {
            visitedUrls.add(url);
            return reachableUrls.contains(url);
        }
    }
}
