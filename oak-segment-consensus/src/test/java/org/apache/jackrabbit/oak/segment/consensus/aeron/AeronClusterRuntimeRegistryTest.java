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

import java.util.List;

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class AeronClusterRuntimeRegistryTest {

    @After
    public void tearDown() {
        AeronClusterRuntimeRegistry.clear();
    }

    @Test
    public void updateTrimsSelfUrlAndPeerUrls() {
        AeronClusterConfig config = mock(AeronClusterConfig.class);
        when(config.enabled()).thenReturn(false);
        when(config.nodeId()).thenReturn(7);
        when(config.selfUrl()).thenReturn(" http://self ");
        when(config.peerUrls()).thenReturn(new String[] { " http://peer-1 ", "", null, "http://peer-2  " });
        when(config.observeElections()).thenReturn(false);
        when(config.logClusterStateDetails()).thenReturn(true);

        AeronClusterRuntimeRegistry.update(config);
        AeronClusterRuntimeRegistry.Snapshot snapshot = AeronClusterRuntimeRegistry.snapshot();

        assertFalse(snapshot.enabled);
        assertEquals(7, snapshot.nodeId);
        assertEquals("http://self", snapshot.selfUrl);
        assertEquals(List.of("http://peer-1", "http://peer-2"), snapshot.peerUrls);
        assertFalse(snapshot.observeElections);
        assertTrue(snapshot.logClusterStateDetails);
    }

    @Test
    public void updateNullAndClearRestoreEmptySnapshot() {
        AeronClusterRuntimeRegistry.update(null);
        assertEmpty(AeronClusterRuntimeRegistry.snapshot());

        AeronClusterConfig config = mock(AeronClusterConfig.class);
        when(config.peerUrls()).thenReturn(new String[0]);
        when(config.selfUrl()).thenReturn("http://self");
        AeronClusterRuntimeRegistry.update(config);

        AeronClusterRuntimeRegistry.clear();
        assertEmpty(AeronClusterRuntimeRegistry.snapshot());
    }

    @Test
    public void updateHandlesNullPeerUrlsAndBlankSelfUrl() {
        AeronClusterConfig config = mock(AeronClusterConfig.class);
        when(config.peerUrls()).thenReturn(null);
        when(config.selfUrl()).thenReturn("   ");

        AeronClusterRuntimeRegistry.update(config);
        AeronClusterRuntimeRegistry.Snapshot snapshot = AeronClusterRuntimeRegistry.snapshot();

        assertNull(snapshot.selfUrl);
        assertTrue(snapshot.peerUrls.isEmpty());
    }

    private static void assertEmpty(AeronClusterRuntimeRegistry.Snapshot snapshot) {
        assertTrue(snapshot.enabled);
        assertEquals(0, snapshot.nodeId);
        assertNull(snapshot.selfUrl);
        assertTrue(snapshot.peerUrls.isEmpty());
        assertTrue(snapshot.observeElections);
        assertFalse(snapshot.logClusterStateDetails);
    }
}
