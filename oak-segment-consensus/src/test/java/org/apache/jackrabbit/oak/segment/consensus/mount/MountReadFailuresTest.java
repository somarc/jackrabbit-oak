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

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class MountReadFailuresTest {

    @After
    public void endRequest() {
        MountReadFailures.end();
    }

    @Test
    public void testNoAnswerWithoutARequestOrWithoutFailures() {
        MountReadFailures.record("http://b:8090", MountReadFailures.Kind.MISSING, "outside a request");
        assertNull(MountReadFailures.failureAnswer());

        RemoteMounts mounts = mock(RemoteMounts.class);
        MountReadFailures.begin(mounts);
        assertNull(MountReadFailures.failureAnswer());
        verifyNoInteractions(mounts);
    }

    @Test
    public void testAReclaimedSegmentIsAnsweredAsStaleAndReportedOnce() {
        RemoteMounts mounts = claiming(true);
        MountReadFailures.begin(mounts);
        MountReadFailures.record("http://b:8090", MountReadFailures.Kind.MISSING, "segment gone");

        MountReadFailures.Answer answer = MountReadFailures.failureAnswer();
        assertEquals("mount_stale", answer.code());
        assertTrue(answer.message().contains("http://b:8090"));
        assertEquals(answer, MountReadFailures.failureAnswer());
        verify(mounts, times(1)).reportReadFailures(anyList());
    }

    @Test
    public void testAnyTransportFailureIsAnsweredAsUnavailable() {
        MountReadFailures.begin(claiming(true));
        MountReadFailures.record("http://b:8090", MountReadFailures.Kind.MISSING, "segment gone");
        MountReadFailures.record("http://c:8090", MountReadFailures.Kind.TRANSPORT, "connection refused");

        MountReadFailures.Answer answer = MountReadFailures.failureAnswer();
        assertEquals("mount_unavailable", answer.code());
        assertTrue(answer.message().contains("http://b:8090, http://c:8090"));
    }

    @Test
    public void testFailuresNoMountClaimsAreLocal() {
        MountReadFailures.begin(claiming(false));
        MountReadFailures.record("http://elsewhere:8090", MountReadFailures.Kind.MISSING, "segment gone");
        assertNull(MountReadFailures.failureAnswer());

        MountReadFailures.begin(null);
        MountReadFailures.record("http://b:8090", MountReadFailures.Kind.MISSING, "segment gone");
        assertNull("a request without mounts has no remote cause", MountReadFailures.failureAnswer());
    }

    private static RemoteMounts claiming(boolean claimed) {
        RemoteMounts mounts = mock(RemoteMounts.class);
        when(mounts.reportReadFailures(anyList())).thenReturn(claimed);
        return mounts;
    }
}
