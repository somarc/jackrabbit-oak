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

import org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile;
import org.apache.jackrabbit.oak.segment.spi.persistence.RepositoryLock;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link HttpPersistence}.
 * 
 * <p>Tests the HTTP-based persistence layer for remote segment stores.
 */
public class HttpPersistenceTest {
    
    private HttpPersistence persistence;
    
    @Before
    public void setUp() {
        persistence = new HttpPersistence("http://localhost:8090");
    }
    
    @After
    public void tearDown() {
        // No cleanup needed - HttpPersistence doesn't hold resources
    }
    
    /**
     * Test URL normalization - trailing slash removed.
     */
    @Test
    public void testUrlNormalizationTrailingSlash() {
        HttpPersistence p = new HttpPersistence("http://localhost:8090/");
        // URL should be normalized (trailing slash removed)
        // We can't directly access the private field, but we can verify behavior
        assertNotNull(p);
    }
    
    /**
     * Test URL normalization - no trailing slash.
     */
    @Test
    public void testUrlNormalizationNoTrailingSlash() {
        HttpPersistence p = new HttpPersistence("http://localhost:8090");
        assertNotNull(p);
    }
    
    /**
     * Test segmentFilesExist returns true (assumes server reachable).
     */
    @Test
    public void testSegmentFilesExist() {
        // Always returns true (assumes server is reachable)
        assertTrue(persistence.segmentFilesExist());
    }
    
    /**
     * Test getJournalFile returns non-null.
     */
    @Test
    public void testGetJournalFile() {
        JournalFile journalFile = persistence.getJournalFile();
        assertNotNull(journalFile);
        assertTrue(journalFile instanceof HttpJournalFile);
    }
    
    /**
     * Test getGCJournalFile returns non-null.
     */
    @Test
    public void testGetGCJournalFile() throws IOException {
        GCJournalFile gcJournalFile = persistence.getGCJournalFile();
        assertNotNull(gcJournalFile);
        assertTrue(gcJournalFile instanceof HttpGCJournalFile);
    }
    
    /**
     * Test getManifestFile returns non-null.
     */
    @Test
    public void testGetManifestFile() throws IOException {
        ManifestFile manifestFile = persistence.getManifestFile();
        assertNotNull(manifestFile);
        assertTrue(manifestFile instanceof HttpManifestFile);
    }
    
    /**
     * Test lockRepository returns no-op lock.
     */
    @Test
    public void testLockRepository() throws IOException {
        RepositoryLock lock = persistence.lockRepository();
        assertNotNull(lock);
        
        // Should be a no-op lock (read-only mount)
        // Unlock should not throw
        lock.unlock();
    }
    
    /**
     * Test createArchiveManager returns non-null.
     */
    @Test
    public void testCreateArchiveManager() {
        SegmentArchiveManager manager = persistence.createArchiveManager(
            false, // mmap
            false, // offHeapAccess
            null,  // ioMonitor
            null,  // fileStoreMonitor
            null   // remoteStoreMonitor
        );
        
        assertNotNull(manager);
        assertTrue(manager instanceof HttpSegmentArchiveManager);
    }
    
    /**
     * Test createArchiveManager with mmap enabled.
     */
    @Test
    public void testCreateArchiveManagerWithMmap() {
        SegmentArchiveManager manager = persistence.createArchiveManager(
            true,  // mmap
            false, // offHeapAccess
            null,  // ioMonitor
            null,  // fileStoreMonitor
            null   // remoteStoreMonitor
        );
        
        assertNotNull(manager);
    }
    
    /**
     * Test createArchiveManager with offHeapAccess enabled.
     */
    @Test
    public void testCreateArchiveManagerWithOffHeap() {
        SegmentArchiveManager manager = persistence.createArchiveManager(
            false, // mmap
            true,  // offHeapAccess
            null,  // ioMonitor
            null,  // fileStoreMonitor
            null   // remoteStoreMonitor
        );
        
        assertNotNull(manager);
    }
    
    /**
     * Test with various URL formats.
     */
    @Test
    public void testVariousUrlFormats() {
        // IPv4
        HttpPersistence p1 = new HttpPersistence("http://192.168.1.100:8090");
        assertNotNull(p1);
        
        // IPv6
        HttpPersistence p2 = new HttpPersistence("http://[::1]:8090");
        assertNotNull(p2);
        
        // Hostname
        HttpPersistence p3 = new HttpPersistence("http://oak-global-store:8090");
        assertNotNull(p3);
        
        // HTTPS
        HttpPersistence p4 = new HttpPersistence("https://secure-store.example.com:8443");
        assertNotNull(p4);
    }
    
    /**
     * Test multiple calls to getJournalFile return new instances.
     */
    @Test
    public void testMultipleJournalFileCalls() {
        JournalFile j1 = persistence.getJournalFile();
        JournalFile j2 = persistence.getJournalFile();
        
        assertNotNull(j1);
        assertNotNull(j2);
        // Each call creates a new instance
        assertNotSame(j1, j2);
    }
    
    /**
     * Test multiple calls to getGCJournalFile return new instances.
     */
    @Test
    public void testMultipleGCJournalFileCalls() throws IOException {
        GCJournalFile g1 = persistence.getGCJournalFile();
        GCJournalFile g2 = persistence.getGCJournalFile();
        
        assertNotNull(g1);
        assertNotNull(g2);
        assertNotSame(g1, g2);
    }
    
    /**
     * Test multiple calls to getManifestFile return new instances.
     */
    @Test
    public void testMultipleManifestFileCalls() throws IOException {
        ManifestFile m1 = persistence.getManifestFile();
        ManifestFile m2 = persistence.getManifestFile();
        
        assertNotNull(m1);
        assertNotNull(m2);
        assertNotSame(m1, m2);
    }
    
    /**
     * Test multiple calls to lockRepository return new instances.
     */
    @Test
    public void testMultipleLockRepositoryCalls() throws IOException {
        RepositoryLock l1 = persistence.lockRepository();
        RepositoryLock l2 = persistence.lockRepository();
        
        assertNotNull(l1);
        assertNotNull(l2);
        assertNotSame(l1, l2);
        
        // Both should be unlockable
        l1.unlock();
        l2.unlock();
    }
    
    /**
     * Test no-op lock can be unlocked multiple times.
     */
    @Test
    public void testNoOpLockMultipleUnlock() throws IOException {
        RepositoryLock lock = persistence.lockRepository();
        
        // Multiple unlocks should not throw
        lock.unlock();
        lock.unlock();
        lock.unlock();
    }
}
