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
package org.apache.jackrabbit.oak.segment.consensus.queue;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProposalPayloadStoreTest {

    @Test
    public void closeResetsEphemeralBytesAndStorePayloadRecreatesDirectory() throws Exception {
        Path payloadDir = Files.createTempDirectory("proposal-payload-store");
        ProposalPayloadStore store = new ProposalPayloadStore(payloadDir, true);

        ProposalPayloadStore.StoredPayload first = store.storePayload("proposal-1", "hello world", 1024L);

        assertTrue(Files.exists(payloadDir.resolve(first.getPayloadRef())));
        assertEquals("hello world".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, store.getTotalBytes());

        store.close();

        assertFalse(Files.exists(payloadDir));
        assertEquals(0L, store.getTotalBytes());

        ProposalPayloadStore.StoredPayload second = store.storePayload("proposal-2", "hello again", 1024L);

        assertTrue(Files.exists(payloadDir.resolve(second.getPayloadRef())));
        assertEquals("hello again".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, store.getTotalBytes());
    }

    @Test
    public void closeLeavesNonEphemeralDirectoryInPlace() throws Exception {
        Path payloadDir = Files.createTempDirectory("proposal-payload-store-persistent");
        ProposalPayloadStore store = new ProposalPayloadStore(payloadDir, false);

        ProposalPayloadStore.StoredPayload stored = store.storePayload("proposal-1", "persistent", 1024L);

        store.close();

        assertTrue(Files.exists(payloadDir));
        assertTrue(Files.exists(payloadDir.resolve(stored.getPayloadRef())));
        assertEquals("persistent".getBytes(java.nio.charset.StandardCharsets.UTF_8).length, store.getTotalBytes());
    }
}
