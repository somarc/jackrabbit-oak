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
package org.apache.jackrabbit.oak.segment.consensus.mount.http;

import org.apache.jackrabbit.oak.segment.remote.WriteAccessController;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileReader;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFileWriter;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class HttpRemoteMetadataFileTest {

    @Test
    public void testJournalReaderLoadsLinesAndExistsDelegates() throws Exception {
        RecordingStringPool pool = new RecordingStringPool();
        pool.response = "head\nroot\n";
        pool.existsResult = true;
        HttpJournalFile journalFile = new HttpJournalFile("http://validator.example", new WriteAccessController(), pool);

        JournalFileReader reader = journalFile.openJournalReader();

        assertEquals("http://validator.example/journal.log", pool.lastGetStringUrl);
        assertEquals("root", reader.readLine());
        assertEquals("head", reader.readLine());
        assertNull(reader.readLine());
        reader.close();
        assertTrue(journalFile.exists());
        assertEquals("http://validator.example/journal.log", pool.lastExistsUrl);
        assertEquals("journal.log", journalFile.getName());
    }

    @Test
    public void testJournalReaderWrapsFailuresAndWriterIsNoOp() throws Exception {
        RecordingStringPool pool = new RecordingStringPool();
        pool.failure = new RuntimeException("broken");
        HttpJournalFile journalFile = new HttpJournalFile("http://validator.example", new WriteAccessController(), pool);

        try {
            journalFile.openJournalReader();
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to fetch journal.log via HTTP/2"));
        }

        JournalFileWriter writer = journalFile.openJournalWriter();
        writer.truncate();
        writer.writeLine("ignored");
        writer.batchWriteLines(List.of("ignored", "still ignored"));
        writer.close();
    }

    @Test
    public void testManifestLoadParsesPropertiesAnd404ReturnsEmpty() throws Exception {
        RecordingStringPool pool = new RecordingStringPool();
        pool.response = "store=global\nmode=readonly\n";
        HttpManifestFile manifestFile = new HttpManifestFile("http://validator.example", pool);

        Properties props = manifestFile.load();

        assertEquals("http://validator.example/manifest", pool.lastGetStringUrl);
        assertEquals("global", props.getProperty("store"));
        assertEquals("readonly", props.getProperty("mode"));

        pool.failure = new RuntimeException("HTTP 404");
        Properties empty = manifestFile.load();
        assertNotNull(empty);
        assertTrue(empty.isEmpty());
    }

    @Test
    public void testManifestExistsSaveAndUnexpectedFailure() throws Exception {
        RecordingStringPool pool = new RecordingStringPool();
        pool.existsResult = true;
        HttpManifestFile manifestFile = new HttpManifestFile("http://validator.example", pool);

        assertTrue(manifestFile.exists());
        assertEquals("http://validator.example/manifest", pool.lastExistsUrl);
        manifestFile.save(new Properties());

        pool.failure = new RuntimeException("down");
        try {
            manifestFile.load();
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to fetch manifest via HTTP/2"));
        }
    }

    @Test
    public void testManifestWrapsNullMessageFailure() throws Exception {
        RecordingStringPool pool = new RecordingStringPool();
        pool.failure = new RuntimeException();
        HttpManifestFile manifestFile = new HttpManifestFile("http://validator.example", pool);

        try {
            manifestFile.load();
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to fetch manifest via HTTP/2"));
        }
    }

    @Test
    public void testGcJournalReadsLinesAndHandles404() throws Exception {
        RecordingStringPool pool = new RecordingStringPool();
        pool.response = "gc-1\ngc-2\n";
        HttpGCJournalFile gcJournalFile = new HttpGCJournalFile("http://validator.example", pool);

        assertEquals(List.of("gc-1", "gc-2"), gcJournalFile.readLines());
        assertEquals("http://validator.example/gc.log", pool.lastGetStringUrl);

        pool.failure = new RuntimeException("HTTP 404");
        assertTrue(gcJournalFile.readLines().isEmpty());
    }

    @Test
    public void testGcJournalWrapsUnexpectedFailureAndWriteOperationsAreNoOp() throws Exception {
        RecordingStringPool pool = new RecordingStringPool();
        pool.failure = new RuntimeException("offline");
        HttpGCJournalFile gcJournalFile = new HttpGCJournalFile("http://validator.example", pool);

        try {
            gcJournalFile.readLines();
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to fetch gc.log via HTTP/2"));
        }

        gcJournalFile.writeLine("ignored");
        gcJournalFile.truncate();
    }

    @Test
    public void testGcJournalWrapsNullMessageFailure() throws Exception {
        RecordingStringPool pool = new RecordingStringPool();
        pool.failure = new RuntimeException();
        HttpGCJournalFile gcJournalFile = new HttpGCJournalFile("http://validator.example", pool);

        try {
            gcJournalFile.readLines();
            fail("Expected IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("Failed to fetch gc.log via HTTP/2"));
        }
    }

    private static final class RecordingStringPool extends Http2ClientPool {

        private String response = "";
        private RuntimeException failure;
        private boolean existsResult;
        private String lastGetStringUrl;
        private String lastExistsUrl;

        @Override
        public String getString(String url) {
            lastGetStringUrl = url;
            if (failure != null) {
                throw failure;
            }
            return response;
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
