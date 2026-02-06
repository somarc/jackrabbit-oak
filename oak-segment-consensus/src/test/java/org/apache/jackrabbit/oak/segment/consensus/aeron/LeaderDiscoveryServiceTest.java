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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;

import static org.junit.Assert.*;

public class LeaderDiscoveryServiceTest {

    @Test
    public void testNotifyBecameLeaderUpdatesKnownAndCache() {
        LeaderDiscoveryService service = new LeaderDiscoveryService(new HashMap<>(), Collections.emptyList());
        service.setSelfUrl("http://localhost:8090");

        service.notifyBecameLeader(3);

        assertEquals("http://localhost:8090", service.getKnownLeaderUrl());
        assertEquals(3, service.getKnownLeaderMemberId());
        assertEquals("http://localhost:8090", service.getCachedLeaderUrl());
        assertTrue(service.isLeaderKnown());
    }

    @Test
    public void testInvalidateCacheClearsCachedLeader() {
        LeaderDiscoveryService service = new LeaderDiscoveryService(new HashMap<>(), Collections.emptyList());
        service.setKnownLeader("http://localhost:8090", 1);

        service.invalidateCache();

        assertNull(service.getCachedLeaderUrl());
        assertEquals("http://localhost:8090", service.getKnownLeaderUrl());
    }

    @Test
    public void testIsSameUrlHandlesLocalhostAndIp() {
        LeaderDiscoveryService service = new LeaderDiscoveryService(new HashMap<>(), Collections.emptyList());

        assertTrue(service.isSameUrl("http://localhost:8090", "http://127.0.0.1:8090"));
        assertFalse(service.isSameUrl("http://localhost:8090", "http://127.0.0.1:8091"));
    }

    @Test
    public void testBestKnownLeaderPrefersKnown() {
        LeaderDiscoveryService service = new LeaderDiscoveryService(new HashMap<>(), Collections.emptyList());
        service.setKnownLeader("http://leader-1:8090", 2);

        assertEquals("http://leader-1:8090", service.getBestKnownLeaderUrl());
    }
}
