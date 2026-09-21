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
package org.apache.jackrabbit.oak.segment.consensus.service;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class FileStoreFlushServiceTest {

    @Test
    public void testOnChangeAppliedFlushesSynchronouslyWhenAsyncIsDisabled() throws Exception {
        withProperty("oak.filestore.flush.ms", "0", () -> withProperty("oak.filestore.flush.batch", "1", () -> {
            FileStore fileStore = mock(FileStore.class);
            FileStoreFlushService service = new FileStoreFlushService(fileStore);

            assertTrue(service.onChangeApplied());
            verify(fileStore).flush();
        }));
    }

    @Test
    public void testOnChangeAppliedDefersWhenAsyncBatchNotReached() throws Exception {
        withProperty("oak.filestore.flush.ms", "60000", () -> withProperty("oak.filestore.flush.batch", "100", () -> {
            FileStore fileStore = mock(FileStore.class);
            FileStoreFlushService service = new FileStoreFlushService(fileStore);

            assertFalse(service.onChangeApplied());
            verifyNoInteractions(fileStore);
        }));
    }

    @Test
    public void testOnChangeAppliedReturnsFalseWhenFlushFails() throws Exception {
        withProperty("oak.filestore.flush.ms", "0", () -> withProperty("oak.filestore.flush.batch", "1", () -> {
            FileStore fileStore = mock(FileStore.class);
            doThrow(new IOException("boom")).when(fileStore).flush();
            FileStoreFlushService service = new FileStoreFlushService(fileStore);

            assertFalse(service.onChangeApplied());
            verify(fileStore).flush();
        }));
    }

    @Test
    public void testOnChangeAppliedRunsFlushCallbackImmediatelyWhenFlushedSynchronously() throws Exception {
        withProperty("oak.filestore.flush.ms", "0", () -> withProperty("oak.filestore.flush.batch", "1", () -> {
            FileStore fileStore = mock(FileStore.class);
            FileStoreFlushService service = new FileStoreFlushService(fileStore);
            AtomicBoolean callbackRan = new AtomicBoolean(false);

            assertTrue(service.onChangeApplied(() -> callbackRan.set(true)));

            assertTrue(callbackRan.get());
            verify(fileStore).flush();
        }));
    }

    @Test
    public void testDeferredFlushCallbackRunsWhenServiceEventuallyFlushes() throws Exception {
        withProperty("oak.filestore.flush.ms", "60000", () -> withProperty("oak.filestore.flush.batch", "100", () -> {
            FileStore fileStore = mock(FileStore.class);
            AtomicBoolean callbackRan = new AtomicBoolean(false);

            try (FileStoreFlushService service = new FileStoreFlushService(fileStore)) {
                assertFalse(service.onChangeApplied(() -> callbackRan.set(true)));
                assertFalse(callbackRan.get());
            }

            assertTrue(callbackRan.get());
            verify(fileStore).flush();
        }));
    }

    @Test
    public void callbackRegisteredDuringFlushWaitsForNextSuccessfulFlush() throws Exception {
        withProperty("oak.filestore.flush.ms", "0", () -> withProperty("oak.filestore.flush.batch", "1", () -> {
            FileStore fileStore = mock(FileStore.class);
            AtomicInteger first = new AtomicInteger();
            AtomicInteger second = new AtomicInteger();
            AtomicInteger flushes = new AtomicInteger();
            AtomicBoolean inject = new AtomicBoolean(true);
            try (FileStoreFlushService service = new FileStoreFlushService(fileStore)) {
                doAnswer(invocation -> {
                    flushes.incrementAndGet();
                    if (inject.getAndSet(false)) {
                        assertFalse(service.onChangeApplied(second::incrementAndGet));
                    }
                    assertEquals(0, second.get());
                    return null;
                }).when(fileStore).flush();
                assertTrue(service.onChangeApplied(first::incrementAndGet));
                assertEquals(1, first.get());
                assertEquals(1, second.get());
                assertEquals(2, flushes.get());
            }
        }));
    }

    private static void withProperty(String key, String value, ThrowingRunnable runnable) throws Exception {
        String previous = System.getProperty(key);
        try {
            if (value == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, value);
            }
            runnable.run();
        } finally {
            if (previous == null) {
                System.clearProperty(key);
            } else {
                System.setProperty(key, previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
