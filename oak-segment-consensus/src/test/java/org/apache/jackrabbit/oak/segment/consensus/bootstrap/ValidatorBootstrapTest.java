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
package org.apache.jackrabbit.oak.segment.consensus.bootstrap;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.junit.Test;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ValidatorBootstrapTest {

    @Test
    public void needsBootstrapReturnsTrueWhenFileStoreIsEmpty() throws Exception {
        FileStore fileStore = mock(FileStore.class);
        when(fileStore.size()).thenReturn(0L);

        ValidatorBootstrap bootstrap = new ValidatorBootstrap(fileStore, 8091);

        assertTrue(bootstrap.needsBootstrap());
    }

    @Test
    public void needsBootstrapReturnsFalseWhenFileStoreHasData() throws Exception {
        FileStore fileStore = mock(FileStore.class);
        when(fileStore.size()).thenReturn(1024L * 1024L);

        ValidatorBootstrap bootstrap = new ValidatorBootstrap(fileStore, 8091);

        assertFalse(bootstrap.needsBootstrap());
    }

    @Test
    public void needsBootstrapReturnsFalseWhenFileStoreSizeCheckFails() throws Exception {
        FileStore fileStore = mock(FileStore.class);
        when(fileStore.size()).thenThrow(new RuntimeException("size failed"));

        ValidatorBootstrap bootstrap = new ValidatorBootstrap(fileStore, 8091);

        assertFalse(bootstrap.needsBootstrap());
    }

    @Test
    public void bootstrapFromPrimaryPromotesImmediatelyWhenAlreadyCaughtUp() throws Exception {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.size()).thenReturn(1024L);

        RecordingRuntimeClient client = new RecordingRuntimeClient();
        RecordingRuntimeServer server = new RecordingRuntimeServer();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingRuntimeFactory runtimeFactory = new RecordingRuntimeFactory(client, server, scheduler);
        AtomicInteger promotedCount = new AtomicInteger();
        AtomicReference<String> primaryUrl = new AtomicReference<>();

        ValidatorBootstrap bootstrap = new ValidatorBootstrap(
            fileStore,
            8091,
            runtimeFactory,
            (store, url) -> {
                primaryUrl.set(url);
                return true;
            }
        );

        bootstrap.bootstrapFromPrimary("primary-1", 8081, promotedCount::incrementAndGet);

        assertEquals("http://primary-1:8080", primaryUrl.get());
        assertEquals(1, client.syncCalls);
        assertEquals(1, client.closeCalls);
        assertEquals(1, promotedCount.get());
        assertEquals(0, runtimeFactory.schedulerCreateCalls);
    }

    @Test
    public void bootstrapFromPrimarySchedulesPeriodicSyncUntilCaughtUp() throws Exception {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.size()).thenReturn(1024L);

        RecordingRuntimeClient client = new RecordingRuntimeClient();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingRuntimeFactory runtimeFactory =
            new RecordingRuntimeFactory(client, new RecordingRuntimeServer(), scheduler);
        AtomicInteger promotedCount = new AtomicInteger();
        AtomicInteger catchupChecks = new AtomicInteger();

        ValidatorBootstrap bootstrap = new ValidatorBootstrap(
            fileStore,
            8091,
            runtimeFactory,
            (store, url) -> catchupChecks.incrementAndGet() >= 2
        );

        bootstrap.bootstrapFromPrimary("primary-1", 8081, promotedCount::incrementAndGet);

        assertEquals(1, client.syncCalls);
        assertEquals(0, client.closeCalls);
        assertEquals(0, promotedCount.get());
        assertEquals(1, runtimeFactory.schedulerCreateCalls);
        assertNotNull(scheduler.command);
        assertEquals(5L, scheduler.initialDelay);
        assertEquals(5L, scheduler.period);
        assertEquals(TimeUnit.SECONDS, scheduler.unit);

        scheduler.runScheduledTask();

        assertEquals(2, client.syncCalls);
        assertEquals(1, client.closeCalls);
        assertEquals(1, promotedCount.get());
        assertEquals(1, scheduler.shutdownCalls);
        assertEquals(2, catchupChecks.get());
    }

    @Test
    public void startStandbyServerWrapsStartFailures() throws Exception {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        RecordingRuntimeServer server = new RecordingRuntimeServer();
        server.startFailure = new IllegalStateException("start failed");

        ValidatorBootstrap bootstrap = new ValidatorBootstrap(
            fileStore,
            8091,
            new RecordingRuntimeFactory(new RecordingRuntimeClient(), server, new RecordingScheduler()),
            (store, url) -> true
        );

        try {
            bootstrap.startStandbyServer();
            fail("Expected startStandbyServer to throw");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("8091"));
            assertTrue(e.getCause() instanceof IllegalStateException);
        }
    }

    @Test
    public void shutdownClosesRuntimeServicesEvenWhenCloseFails() throws Exception {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.size()).thenReturn(1024L);

        RecordingRuntimeClient client = new RecordingRuntimeClient();
        RecordingRuntimeServer server = new RecordingRuntimeServer();
        RecordingScheduler scheduler = new RecordingScheduler();
        RecordingRuntimeFactory runtimeFactory = new RecordingRuntimeFactory(client, server, scheduler);

        ValidatorBootstrap bootstrap = new ValidatorBootstrap(
            fileStore,
            8091,
            runtimeFactory,
            (store, url) -> false
        );

        bootstrap.bootstrapFromPrimary("primary-1", 8081, null);
        bootstrap.startStandbyServer();

        client.closeFailure = new RuntimeException("client close failed");
        server.closeFailure = new RuntimeException("server close failed");

        bootstrap.shutdown();

        assertEquals(1, client.closeCalls);
        assertEquals(1, server.startCalls);
        assertEquals(1, server.closeCalls);
        assertEquals(1, scheduler.shutdownCalls);
    }

    private static final class RecordingRuntimeFactory implements ValidatorBootstrap.BootstrapRuntimeFactory {
        private final RecordingRuntimeClient client;
        private final RecordingRuntimeServer server;
        private final RecordingScheduler scheduler;
        private int schedulerCreateCalls;

        private RecordingRuntimeFactory(RecordingRuntimeClient client,
                                        RecordingRuntimeServer server,
                                        RecordingScheduler scheduler) {
            this.client = client;
            this.server = server;
            this.scheduler = scheduler;
        }

        @Override
        public ValidatorBootstrap.BootstrapRuntimeClient createStandbyClient(String primaryHost,
                                                                             int primaryPort,
                                                                             FileStore fileStore) {
            return client;
        }

        @Override
        public ValidatorBootstrap.BootstrapRuntimeServer createStandbyServer(int standbyPort, FileStore fileStore) {
            return server;
        }

        @Override
        public ScheduledExecutorService createSyncScheduler() {
            schedulerCreateCalls++;
            return scheduler;
        }
    }

    private static final class RecordingRuntimeClient implements ValidatorBootstrap.BootstrapRuntimeClient {
        private int syncCalls;
        private int closeCalls;
        private RuntimeException syncFailure;
        private RuntimeException closeFailure;

        @Override
        public void sync() {
            syncCalls++;
            if (syncFailure != null) {
                throw syncFailure;
            }
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class RecordingRuntimeServer implements ValidatorBootstrap.BootstrapRuntimeServer {
        private int startCalls;
        private int closeCalls;
        private RuntimeException startFailure;
        private RuntimeException closeFailure;

        @Override
        public void start() {
            startCalls++;
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }

    private static final class RecordingScheduler extends AbstractExecutorService implements ScheduledExecutorService {
        private Runnable command;
        private long initialDelay;
        private long period;
        private TimeUnit unit;
        private int shutdownCalls;
        private boolean shutdown;

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit) {
            this.command = command;
            this.initialDelay = initialDelay;
            this.period = period;
            this.unit = unit;
            return mock(ScheduledFuture.class);
        }

        @Override
        public void shutdown() {
            shutdown = true;
            shutdownCalls++;
        }

        @Override
        public List<Runnable> shutdownNow() {
            shutdown();
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return shutdown;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <V> ScheduledFuture<V> schedule(Callable<V> callable, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay, TimeUnit unit) {
            throw new UnsupportedOperationException();
        }

        private void runScheduledTask() {
            if (command == null) {
                fail("No task was scheduled");
            }
            command.run();
        }
    }
}
