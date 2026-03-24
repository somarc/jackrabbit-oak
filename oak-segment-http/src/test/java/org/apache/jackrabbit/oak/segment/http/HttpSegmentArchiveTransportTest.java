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
package org.apache.jackrabbit.oak.segment.http;

import org.apache.jackrabbit.oak.commons.Buffer;
import org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitorAdapter;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveWriter;
import org.junit.Test;

import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpSegmentArchiveTransportTest {

    @Test
    public void testReadSegmentBuildsUuidUrlAndReturnsData() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        UUID uuid = UUID.randomUUID();
        byte[] expected = new byte[] {9, 8, 7, 6};
        pool.segmentData = expected;

        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(
            "http://validator.example/",
            "data00000a.tar",
            new IOMonitorAdapter(),
            pool
        );

        Buffer buffer = reader.readSegment(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
        byte[] actual = new byte[expected.length];
        buffer.get(actual);

        assertEquals("http://validator.example/segments/" + uuid, pool.lastGetUrl);
        assertArrayEquals(expected, actual);
        assertEquals("data00000a.tar", reader.getName());
    }

    @Test
    public void testReadSegmentReturnsNullFor404() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        pool.getFailure = new RuntimeException("HTTP 404");
        UUID uuid = UUID.randomUUID();
        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(
            "http://validator.example",
            "data00000a.tar",
            new IOMonitorAdapter(),
            pool
        );

        assertNull(reader.readSegment(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()));
    }

    @Test
    public void testReadSegmentWrapsNon404Failures() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        pool.getFailure = new RuntimeException("boom");
        UUID uuid = UUID.randomUUID();
        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(
            "http://validator.example",
            "data00000a.tar",
            new IOMonitorAdapter(),
            pool
        );

        try {
            reader.readSegment(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to fetch segment via HTTP/2"));
        }
    }

    @Test
    public void testReadSegmentWrapsNullMessageFailures() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        pool.getFailure = new RuntimeException();
        UUID uuid = UUID.randomUUID();
        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(
            "http://validator.example",
            "data00000a.tar",
            new IOMonitorAdapter(),
            pool
        );

        try {
            reader.readSegment(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits());
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to fetch segment via HTTP/2"));
        }
    }

    @Test
    public void testReadSegmentToBufferParsesArchiveFileName() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        pool.segmentData = new byte[] {1, 2, 3};
        String uuid = UUID.randomUUID().toString();
        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(
            "http://validator.example",
            "data00000a.tar",
            new IOMonitorAdapter(),
            pool
        );

        Buffer target = Buffer.allocate(8);
        reader.doReadSegmentToBuffer("000123." + uuid, target);
        byte[] actual = new byte[3];
        target.get(actual);

        assertEquals("http://validator.example/segments/" + uuid, pool.lastGetUrl);
        assertArrayEquals(new byte[] {1, 2, 3}, actual);
        assertNull(reader.doReadDataFile(".gph"));
        assertEquals("http:/validator.example/data00000a.tar", reader.archivePathAsFile().getPath());
    }

    @Test
    public void testReadSegmentToBufferSupportsRawUuidAndClose() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        pool.segmentData = new byte[] {4, 5, 6};
        String uuid = UUID.randomUUID().toString();
        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(
            "http://validator.example",
            "data00000a.tar",
            new IOMonitorAdapter(),
            pool
        );

        Buffer target = Buffer.allocate(8);
        reader.doReadSegmentToBuffer(uuid, target);
        byte[] actual = new byte[3];
        target.get(actual);

        assertEquals("http://validator.example/segments/" + uuid, pool.lastGetUrl);
        assertArrayEquals(new byte[] {4, 5, 6}, actual);
        reader.close();
    }

    @Test
    public void testReadSegmentToBufferWrapsFailures() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        pool.getFailure = new RuntimeException("buffer boom");
        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(
            "http://validator.example",
            "data00000a.tar",
            new IOMonitorAdapter(),
            pool
        );

        try {
            reader.doReadSegmentToBuffer(UUID.randomUUID().toString(), Buffer.allocate(8));
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to fetch segment via HTTP/2"));
        }
    }

    @Test
    public void testContainsSegmentDelegatesToPool() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        pool.existsResult = true;
        UUID uuid = UUID.randomUUID();
        HttpSegmentArchiveReader reader = new HttpSegmentArchiveReader(
            "http://validator.example",
            "data00000a.tar",
            new IOMonitorAdapter(),
            pool
        );

        assertTrue(reader.containsSegment(uuid.getMostSignificantBits(), uuid.getLeastSignificantBits()));
        assertEquals("http://validator.example/segments/" + uuid, pool.lastExistsUrl);
    }

    @Test
    public void testArchiveManagerOpenForceOpenAndListArchives() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        HttpSegmentArchiveManager manager = new HttpSegmentArchiveManager(
            "http://validator.example/",
            new IOMonitorAdapter(),
            pool
        );

        assertEquals(Arrays.asList("data00000a.tar"), manager.listArchives());

        SegmentArchiveReader reader = manager.open("data00000a.tar");
        assertNotNull(reader);
        assertEquals("data00000a.tar", reader.getName());
        assertNull(manager.open("other.tar"));

        SegmentArchiveReader forced = manager.forceOpen("other.tar");
        assertNotNull(forced);
        assertEquals("other.tar", forced.getName());
        assertTrue(manager.exists("data00000a.tar"));
        assertFalse(manager.exists("other.tar"));
        assertTrue(manager.isReadOnly("data00000a.tar"));
    }

    @Test
    public void testArchiveManagerReadOnlyWriterAndUnsupportedMutations() throws Exception {
        RecordingHttp2ClientPool pool = new RecordingHttp2ClientPool();
        HttpSegmentArchiveManager manager = new HttpSegmentArchiveManager(
            "http://validator.example",
            new IOMonitorAdapter(),
            pool
        );

        SegmentArchiveWriter writer = manager.create("data00000a.tar");
        writer.writeSegment(1L, 2L, new byte[] {1, 2}, 0, 2, 0, 0, false);
        writer.writeGraph(new byte[] {3});
        writer.writeBinaryReferences(new byte[] {4});
        writer.flush();
        writer.close();

        assertEquals("data00000a.tar", writer.getName());
        assertNull(writer.readSegment(1L, 2L));
        assertFalse(writer.containsSegment(1L, 2L));
        assertEquals(0L, writer.getLength());
        assertEquals(0, writer.getEntryCount());
        assertEquals(0, writer.getMaxEntryCount());
        assertFalse(writer.isCreated());
        assertTrue(writer.isRemote());
        assertFalse(manager.delete("data00000a.tar"));
        assertFalse(manager.renameTo("from", "to"));

        try {
            manager.copyFile("from", "to");
            fail("Expected copyFile to be unsupported");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("read-only HTTP store"));
        }

        try {
            manager.recoverEntries("data00000a.tar", new LinkedHashMap<UUID, byte[]>());
            fail("Expected recoverEntries to be unsupported");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("server side"));
        }

        try {
            manager.backup("data00000a.tar", "backup.tar", Set.of());
            fail("Expected backup to be unsupported");
        } catch (UnsupportedOperationException e) {
            assertTrue(e.getMessage().contains("read-only HTTP store"));
        }
    }

    private static final class RecordingHttp2ClientPool extends Http2ClientPool {

        private byte[] segmentData = new byte[0];
        private RuntimeException getFailure;
        private boolean existsResult;
        private String lastGetUrl;
        private String lastExistsUrl;

        @Override
        public byte[] get(String url) {
            lastGetUrl = url;
            if (getFailure != null) {
                throw getFailure;
            }
            return segmentData;
        }

        @Override
        public boolean exists(String url) {
            lastExistsUrl = url;
            return existsResult;
        }

        @Override
        public String getPoolStats() {
            return "stub";
        }
    }
}
