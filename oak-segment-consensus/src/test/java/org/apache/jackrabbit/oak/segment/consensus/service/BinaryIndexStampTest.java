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

import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.junit.Test;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Binary index stamps written at replicated apply, and the binary reference a mapped client CID now stores. */
public class BinaryIndexStampTest {

    private static final String WALLET = "0x1234567890abcdef1234567890abcdef12345678";
    private static final String ROOT = "/oak-chain/aa/bb/cc/" + WALLET + "/Acme/content/";
    private static final String BLOB = "ab".repeat(32);
    private static final String CID = "Qm" + "a".repeat(44);

    @Test
    public void stampsAreTheSameOnEveryMemberApplyingTheSameEntries() {
        MemoryNodeStore first = new MemoryNodeStore();
        MemoryNodeStore second = new MemoryNodeStore();
        for (MemoryNodeStore member : new MemoryNodeStore[] {first, second}) {
            WriteApplicationService service = service(member);
            write(service, "doc-1", BLOB + "#40000", CID, 100, 1_000L);
            write(service, "doc-2", BLOB + "#40000", CID, 200, 2_000L);
            // A later write that only names the CID (as older builds and unmapped CIDs do) is no binary reference
            write(service, "doc-3", null, CID, 300, 3_000L);
        }

        assertEquals(entries(first), entries(second));
        assertEquals(Map.of(BLOB, "200@2000"), entries(first));
        assertEquals(first.getRoot().getChildNode(ReplicatedBinaryIndex.ROOT),
            second.getRoot().getChildNode(ReplicatedBinaryIndex.ROOT));
    }

    @Test
    public void aMappedClientCidStoresABinaryReferenceAndOldPayloadsStayStrings() {
        MemoryNodeStore member = new MemoryNodeStore();
        WriteApplicationService service = service(member);
        write(service, "upload", BLOB + "#40000", CID, 100, 1_000L);

        // New ingress: the client's ipfsCid maps to a blob id, which the proposal carries with the CID
        write(service, "by-cid", BLOB + "#40000", CID, 200, 2_000L);
        NodeState byCid = content(member, "by-cid");
        assertEquals(Type.BINARY, byCid.getProperty("jcr:data").getType());
        assertEquals(BLOB + "#40000", byCid.getProperty("jcr:blobId").getValue(Type.STRING));
        assertEquals(CID, byCid.getProperty("ipfsCid").getValue(Type.STRING));
        assertEquals(Map.of(BLOB, "200@2000"), entries(member));

        // A log entry written before the change carries only the CID and replays exactly as before
        write(service, "legacy", null, CID, 300, 3_000L);
        NodeState legacy = content(member, "legacy");
        assertFalse(legacy.hasProperty("jcr:data"));
        assertEquals("client", legacy.getProperty("oak:binaryStorageMode").getValue(Type.STRING));
        assertEquals(CID, legacy.getProperty("ipfsCid").getValue(Type.STRING));
        assertEquals(Map.of(BLOB, "200@2000"), entries(member));
    }

    @Test
    public void aBinaryWriteWithoutACidStampsAnExistingEntryButCreatesNone() {
        MemoryNodeStore member = new MemoryNodeStore();
        WriteApplicationService service = service(member);
        write(service, "no-cid", BLOB + "#40000", null, 100, 1_000L);
        assertTrue(entries(member).isEmpty());

        write(service, "upload", BLOB + "#40000", CID, 200, 2_000L);
        write(service, "no-cid-again", BLOB + "#40000", null, 300, 3_000L);
        assertEquals(Map.of(BLOB, "300@3000"), entries(member));
        assertNull(new ReplicatedBinaryIndex(member).resolve("cd".repeat(32)));
    }

    private static void write(WriteApplicationService service, String name, String blobId, String cid,
                              long position, long time) {
        service.applyWriteWithAuditMetadata(WALLET, ROOT + name, "file", "m", "0xsig", blobId,
            "image/png", cid, MutationAuditMetadata.write(null, null, "p-" + name, null, null, null, null)
                .withAppliedAt(time).withAppliedLogPosition(new AppliedLogPosition(position, 0, 1)));
    }

    private static WriteApplicationService service(MemoryNodeStore member) {
        FileStore fileStore = mock(FileStore.class, RETURNS_DEEP_STUBS);
        when(fileStore.getHead().getRecordId().toString()).thenReturn("head");
        when(fileStore.getHead().getRecordId().toString10()).thenReturn("head");
        BlobStore blobStore = mock(BlobStore.class);
        return new WriteApplicationService(fileStore, () -> member, () -> blobStore,
            mock(FileStoreFlushService.class));
    }

    static Map<String, String> entries(MemoryNodeStore member) {
        Map<String, String> entries = new TreeMap<>();
        ReplicatedBinaryIndex.forEach(member.getRoot(), entry -> entries.put(entry.identifier(),
            entry.refPosition() + "@" + entry.refTime()));
        return entries;
    }

    private static NodeState content(MemoryNodeStore member, String name) {
        NodeState current = member.getRoot();
        for (String part : (ROOT + name).substring(1).split("/")) {
            current = current.getChildNode(part);
        }
        return current;
    }
}
