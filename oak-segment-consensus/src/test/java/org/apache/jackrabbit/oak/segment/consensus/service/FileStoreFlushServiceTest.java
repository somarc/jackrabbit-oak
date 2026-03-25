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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doThrow;
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
