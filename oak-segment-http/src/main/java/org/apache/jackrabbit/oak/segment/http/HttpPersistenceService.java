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

import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi service that provides HTTP-based segment persistence for SegmentNodeStoreFactory.
 *
 * <p>This service registers an {@link HttpPersistence} implementation that can be injected
 * into SegmentNodeStoreFactory when {@code customSegmentStore=true} is configured.</p>
 *
 * <p>Configuration properties:</p>
 * <ul>
 *   <li>globalStoreUrl: URL of the GlobalStoreServer (e.g., http://oak-global-store:8090)</li>
 * </ul>
 */
@Component(
    service = {SegmentNodeStorePersistence.class, HttpPersistenceService.class},
    configurationPolicy = ConfigurationPolicy.REQUIRE
)
@Designate(ocd = HttpPersistenceService.Configuration.class)
public class HttpPersistenceService implements SegmentNodeStorePersistence {

    @ObjectClassDefinition(
        name = "HTTP Segment Persistence Configuration",
        description = "Configures HTTP-based segment persistence for read-only remote access"
    )
    public @interface Configuration {
        @AttributeDefinition(
            name = "Global Store URL",
            description = "Base URL of the remote GlobalStoreServer (e.g., http://oak-global-store:8090)"
        )
        String globalStoreUrl();
    }

    private static final Logger log = LoggerFactory.getLogger(HttpPersistenceService.class);

    private HttpPersistence delegate;
    private String globalStoreUrl;

    @Activate
    protected void activate(Configuration config) {
        log.info("Activating HTTP Segment Persistence");
        log.info("  Global Store URL: {}", config.globalStoreUrl());

        this.globalStoreUrl = config.globalStoreUrl();
        this.delegate = new HttpPersistence(globalStoreUrl);

        log.info("HTTP Segment Persistence activated successfully");
    }
    
    /**
     * Get the global store URL configured for this service.
     * @return The global store URL (e.g., http://oak-global-store:8090)
     */
    public String getGlobalStoreUrl() {
        return globalStoreUrl;
    }

    @Deactivate
    protected void deactivate() {
        log.info("Deactivating HTTP Segment Persistence");
        this.delegate = null;
    }

    // Delegate all methods to HttpPersistence

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.SegmentArchiveManager createArchiveManager(
            boolean mmap, boolean offHeapAccess,
            org.apache.jackrabbit.oak.segment.spi.monitor.IOMonitor ioMonitor,
            org.apache.jackrabbit.oak.segment.spi.monitor.FileStoreMonitor fileStoreMonitor,
            org.apache.jackrabbit.oak.segment.spi.monitor.RemoteStoreMonitor remoteStoreMonitor) {
        return delegate.createArchiveManager(mmap, offHeapAccess, ioMonitor, fileStoreMonitor, remoteStoreMonitor);
    }

    @Override
    public boolean segmentFilesExist() {
        return delegate.segmentFilesExist();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.JournalFile getJournalFile() {
        return delegate.getJournalFile();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.GCJournalFile getGCJournalFile() throws java.io.IOException {
        return delegate.getGCJournalFile();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.ManifestFile getManifestFile() throws java.io.IOException {
        return delegate.getManifestFile();
    }

    @Override
    public org.apache.jackrabbit.oak.segment.spi.persistence.RepositoryLock lockRepository() throws java.io.IOException {
        return delegate.lockRepository();
    }
}

