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

import io.aeron.cluster.service.Cluster;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.consensus.security.EthereumWallet;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Focused unit tests for core AeronConsensusEngine behavior that can be verified
 * without a running Aeron cluster.
 */
public class AeronConsensusEngineTest {

    @Rule
    public TemporaryFolder tempFolder = new TemporaryFolder();

    @Mock
    private FileStore mockFileStore;

    @Mock
    private NodeStore mockNodeStore;

    @Mock
    private EthereumWallet mockWallet;

    private File storeDirectory;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        storeDirectory = tempFolder.newFolder("segmentstore");
    }

    @Test
    public void roleChangeToLeaderUpdatesRoleTermAndLeaderUrl() {
        AeronConsensusEngine engine = createEngine();

        assertEquals(0, engine.getCurrentTerm());

        engine.onRoleChange(Cluster.Role.LEADER);

        assertEquals(1, engine.getCurrentTerm());
        assertTrue(engine.isLeader());
        assertEquals("http://self:8080", engine.getCurrentLeader());
    }

    @Test
    public void currentRoleUsesClusterAsSourceOfTruthWhenAvailable() throws Exception {
        AeronConsensusEngine engine = createEngine();
        engine.onRoleChange(Cluster.Role.LEADER);

        Cluster cluster = mock(Cluster.class);
        when(cluster.role()).thenReturn(Cluster.Role.FOLLOWER);
        setField(engine, "cluster", cluster);

        assertFalse(engine.isLeader());
    }

    @Test
    public void leadershipHistoryCapturesMemberMetadata() throws Exception {
        AeronConsensusEngine engine = createEngine();
        Cluster cluster = mock(Cluster.class);
        when(cluster.memberId()).thenReturn(7);
        when(cluster.time()).thenReturn(12345L);
        setField(engine, "cluster", cluster);

        engine.onRoleChange(Cluster.Role.LEADER);

        List<LeadershipChange> history = engine.getLeadershipHistory(0);
        assertEquals(1, history.size());
        assertEquals(Cluster.Role.LEADER, history.get(0).newRole);
        assertEquals(7, history.get(0).memberId);
        assertEquals(12345L, history.get(0).timestamp);
    }

    @Test
    public void timerEventExpiresTransactionAndInvokesAbortCallback() throws Exception {
        AeronConsensusEngine engine = createEngine();
        AeronConsensusEngine.TransactionLifecycleCallback callback =
            mock(AeronConsensusEngine.TransactionLifecycleCallback.class);
        engine.setTransactionLifecycleCallback(callback);

        TransactionLifecycleManager manager =
            (TransactionLifecycleManager) getField(engine, "transactionLifecycleManager");
        manager.onStart("tx-1", "corr-1", 1L, "0xabc");
        Thread.sleep(5L);

        engine.onTimerEvent(100L, System.currentTimeMillis());

        verify(callback).onAbortTransaction("tx-1", "corr-1", "timeout");
        Optional<Map<String, Object>> tx = engine.getTransactionRecord("tx-1");
        assertTrue(tx.isPresent());
        assertEquals("TIMED_OUT", tx.get().get("status"));
    }

    @Test
    public void reconnectShortCircuitsWhenClientAlreadyHealthy() throws Exception {
        AeronConsensusEngine engine = createEngine();
        io.aeron.cluster.client.AeronCluster healthyClient = mock(io.aeron.cluster.client.AeronCluster.class);
        when(healthyClient.isClosed()).thenReturn(false);
        setField(engine, "internalClusterClient", healthyClient);
        setField(engine, "reconnectInProgress", true);

        AtomicInteger ensureCalls = new AtomicInteger(0);
        engine.attemptReconnectForTest(
            "test",
            3,
            attempt -> 0L,
            ensureCalls::incrementAndGet,
            backoff -> {
                fail("sleep should not be called when client is healthy");
                return false;
            }
        );

        assertEquals(0, ensureCalls.get());
        assertFalse((Boolean) getField(engine, "reconnectInProgress"));
    }

    @Test
    public void reconnectRetriesUntilClientBecomesHealthy() throws Exception {
        AeronConsensusEngine engine = createEngine();
        setField(engine, "reconnectInProgress", true);

        AtomicInteger ensureCalls = new AtomicInteger(0);
        List<Long> backoffs = new ArrayList<>();
        engine.attemptReconnectForTest(
            "test",
            4,
            attempt -> (long) attempt * 10L,
            () -> {
                int call = ensureCalls.incrementAndGet();
                if (call == 2) {
                    try {
                        io.aeron.cluster.client.AeronCluster healthyClient = mock(io.aeron.cluster.client.AeronCluster.class);
                        when(healthyClient.isClosed()).thenReturn(false);
                        setField(engine, "internalClusterClient", healthyClient);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            },
            backoff -> {
                backoffs.add(backoff);
                return true;
            }
        );

        assertEquals(2, ensureCalls.get());
        assertEquals(1, backoffs.size());
        assertEquals(Long.valueOf(10L), backoffs.get(0));
        assertFalse((Boolean) getField(engine, "reconnectInProgress"));
    }

    @Test
    public void reconnectExhaustionDoesNotSleepAfterFinalAttempt() throws Exception {
        AeronConsensusEngine engine = createEngine();
        setField(engine, "reconnectInProgress", true);

        AtomicInteger ensureCalls = new AtomicInteger(0);
        List<Long> backoffs = new ArrayList<>();
        engine.attemptReconnectForTest(
            "test",
            3,
            attempt -> (long) attempt,
            ensureCalls::incrementAndGet,
            backoff -> {
                backoffs.add(backoff);
                return true;
            }
        );

        assertEquals(3, ensureCalls.get());
        assertEquals(2, backoffs.size());
        assertEquals(Long.valueOf(1L), backoffs.get(0));
        assertEquals(Long.valueOf(2L), backoffs.get(1));
        assertFalse((Boolean) getField(engine, "reconnectInProgress"));
    }

    @Test
    public void durabilityAckFailureInvokesFailureCallbackAndClearsPendingState() throws Exception {
        AeronConsensusEngine engine = createEngine();
        AeronConsensusEngine.DurabilityStatusCallback callback =
            mock(AeronConsensusEngine.DurabilityStatusCallback.class);
        engine.setDurabilityStatusCallback(callback);

        DurabilityAckTracker tracker = (DurabilityAckTracker) getField(engine, "durabilityAckTracker");
        tracker.track("p-fail", 3, 2);
        assertEquals(1, pendingDurabilityCount(tracker));

        MessageDispatcher dispatcher = (MessageDispatcher) getField(engine, "messageDispatcher");
        MessageDispatcher.DurabilityCallback durabilityCallback =
            (MessageDispatcher.DurabilityCallback) getField(dispatcher, "durabilityCallback");
        durabilityCallback.onAckSegmentPersisted("p-fail", false, null, "disk full", 3, 2);

        verify(callback).onFailure("p-fail", "disk full");
        verify(callback, never()).onDurable("p-fail", null);
        assertEquals(0, pendingDurabilityCount(tracker));
    }

    @Test
    public void durabilityAckFailureWithoutErrorUsesDefaultMessage() throws Exception {
        AeronConsensusEngine engine = createEngine();
        AeronConsensusEngine.DurabilityStatusCallback callback =
            mock(AeronConsensusEngine.DurabilityStatusCallback.class);
        engine.setDurabilityStatusCallback(callback);

        DurabilityAckTracker tracker = (DurabilityAckTracker) getField(engine, "durabilityAckTracker");
        tracker.track("p-default-error", 3, 2);
        assertEquals(1, pendingDurabilityCount(tracker));

        MessageDispatcher dispatcher = (MessageDispatcher) getField(engine, "messageDispatcher");
        MessageDispatcher.DurabilityCallback durabilityCallback =
            (MessageDispatcher.DurabilityCallback) getField(dispatcher, "durabilityCallback");
        durabilityCallback.onAckSegmentPersisted("p-default-error", false, null, null, 3, 2);

        verify(callback).onFailure("p-default-error", "durability failed");
        assertEquals(0, pendingDurabilityCount(tracker));
    }

    private AeronConsensusEngine createEngine() {
        return new AeronConsensusEngine(
            mockFileStore,
            mockNodeStore,
            "http://self:8080",
            List.of(),
            mockWallet,
            storeDirectory.getAbsolutePath(),
            null
        );
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object getField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int pendingDurabilityCount(DurabilityAckTracker tracker) throws Exception {
        Field pendingField = DurabilityAckTracker.class.getDeclaredField("pending");
        pendingField.setAccessible(true);
        Map<?, ?> pending = (Map<?, ?>) pendingField.get(tracker);
        return pending.size();
    }
}
