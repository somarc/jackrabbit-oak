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

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class HeadStateServiceTest {

    @Test
    public void activateDeactivateAndLeaderGuardAreNoOps() {
        HeadStateService service = new HeadStateService();

        service.activate();
        service.deactivate();

        assertFalse(service.checkAndCommitFinalityBoundary(false, 5, "head:1"));
        assertFalse(service.checkAndCommitFinalityBoundary(true, 0, "head:1"));
    }

    @Test
    public void checkAndCommitUsesExplicitQualifiedHead() {
        HeadStateService service = new HeadStateService();

        assertTrue(service.checkAndCommitFinalityBoundary(true, 1, "qualified:1"));
        assertEquals("qualified:1", service.getCommittedHead());
        assertEquals("qualified:1", service.getLatestHead());
        assertEquals(0, service.getLastCommittedEpoch());
    }

    @Test
    public void checkAndCommitFallsBackToTrackedLatestHead() {
        HeadStateService service = new HeadStateService();
        service.updateLatestHead("tracked:2");

        assertTrue(service.checkAndCommitFinalityBoundary(true, 2, null));
        assertEquals("tracked:2", service.getCommittedHead());
        assertEquals("tracked:2", service.getLatestHead());
        assertEquals(1, service.getLastCommittedEpoch());
    }

    @Test
    public void checkAndCommitUsesFileStoreHeadForUnqualifiedOrMissingValues() {
        FileStore fileStore = fileStoreWithHead("store:9");
        HeadStateService service = new HeadStateService(fileStore);

        assertTrue(service.checkAndCommitFinalityBoundary(true, 1, "plain-head"));
        assertEquals("store:9", service.getCommittedHead());
        assertEquals("store:9", service.getLatestHead());
        assertEquals(0, service.getLastCommittedEpoch());

        HeadStateService fallbackService = new HeadStateService(fileStore);
        assertTrue(fallbackService.checkAndCommitFinalityBoundary(true, 1, null));
        assertEquals("store:9", fallbackService.getCommittedHead());
    }

    @Test
    public void checkAndCommitReturnsFalseWhenNoHeadIsAvailable() {
        HeadStateService service = new HeadStateService();

        assertFalse(service.checkAndCommitFinalityBoundary(true, 1, ""));
        assertNull(service.getCommittedHead());
        assertNull(service.getLatestHead());
    }

    @Test
    public void updateLatestHeadFallsBackToFileStoreAndInputOnFailure() {
        HeadStateService fromStore = new HeadStateService(fileStoreWithHead("store:10"));
        fromStore.updateLatestHead("plain-head");
        assertEquals("store:10", fromStore.getLatestHead());

        HeadStateService qualified = new HeadStateService(fileStoreWithHead("ignored:1"));
        qualified.updateLatestHead("qualified:2");
        assertEquals("qualified:2", qualified.getLatestHead());

        HeadStateService fallback = new HeadStateService(throwingFileStore());
        fallback.updateLatestHead("plain-fallback");
        assertEquals("plain-fallback", fallback.getLatestHead());

        HeadStateService viaSetter = new HeadStateService();
        viaSetter.setFileStore(fileStoreWithHead("store:12"));
        viaSetter.updateLatestHead("plain-after-setter");
        assertEquals("store:12", viaSetter.getLatestHead());
    }

    @Test
    public void updateLatestHeadAndGetLatestHeadHandleEmptyInputAndMissingStore() {
        HeadStateService fromStore = new HeadStateService(fileStoreWithHead("store:11"));
        fromStore.updateLatestHead(null);
        assertEquals("store:11", fromStore.getLatestHead());

        HeadStateService failingStore = new HeadStateService(throwingFileStore());
        failingStore.updateLatestHead(null);
        boolean threw = false;
        try {
            failingStore.getLatestHead();
        } catch (IllegalStateException e) {
            threw = true;
            assertEquals("empty", e.getMessage());
        }
        assertTrue(threw);

        HeadStateService empty = new HeadStateService();
        assertNull(empty.getLatestHead());
    }

    private static FileStore fileStoreWithHead(String head) {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString10()).thenReturn(head);
        return fileStore;
    }

    private static FileStore throwingFileStore() {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString10()).thenThrow(new IllegalStateException("empty"));
        return fileStore;
    }
}
