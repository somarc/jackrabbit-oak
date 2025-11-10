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

import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.SegmentStore;
import org.apache.jackrabbit.oak.segment.SegmentStoreProvider;
import org.apache.jackrabbit.oak.segment.file.ReadOnlyFileStore;
import org.apache.jackrabbit.oak.segment.file.ReadOnlyRevisions;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Background sync service for HTTP-backed composite mounts.
 * 
 * <p><strong>Architecture: Cold Standby Pattern - Lives in oak-segment-tar</strong></p>
 * 
 * <p>This service is intentionally placed in {@code oak-segment-tar} (not {@code oak-segment-http})
 * for the same reason Cold Standby is here: it needs direct access to {@link ReadOnlyFileStore}
 * which is an internal class not exported by the bundle.
 * 
 * <p>This service follows the exact same pattern as Oak's Cold Standby client:
 * <ol>
 *   <li>Poll the global store's {@code journal.log} for the latest revision</li>
 *   <li>Compare with the local head revision</li>
 *   <li>If remote has advanced, call {@link ReadOnlyFileStore#setRevision(String)}</li>
 *   <li>Next {@code NodeStore.getRoot()} call sees the new content!</li>
 * </ol>
 * 
 * <p><strong>Why this is needed:</strong> Oak's read-only composite mount reads
 * the journal once during initialization but never refreshes. This service
 * provides the missing "background sync" functionality.
 * 
 * @see org.apache.jackrabbit.oak.segment.standby.client.StandbyClientSync
 * @see org.apache.jackrabbit.oak.segment.standby.client.StandbyClientSyncExecution
 */
@Component(
    service = {Runnable.class, HttpSegmentStoreSync.class},
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true,
    property = {
        "scheduler.concurrent:Boolean=false",
        "scheduler.period:Long=30"  // Default: sync every 30 seconds
    }
)
@Designate(ocd = HttpSegmentStoreSync.Configuration.class)
public class HttpSegmentStoreSync implements Runnable {
    
    private static final Logger log = LoggerFactory.getLogger(HttpSegmentStoreSync.class);
    
    @ObjectClassDefinition(
        name = "HTTP Segment Store Background Sync (Cold Standby Pattern)",
        description = "Periodically syncs composite mount from HTTP global store - Lives in oak-segment-tar like Cold Standby"
    )
    @interface Configuration {
        @AttributeDefinition(
            name = "Global Store URL",
            description = "Base URL of the HTTP global store (e.g., http://oak-global-store:8090)"
        )
        String globalStoreUrl() default "http://oak-global-store:8090";
        
        @AttributeDefinition(
            name = "Sync Interval",
            description = "How often to poll for updates, in seconds"
        )
        long syncInterval() default 30;
        
        @AttributeDefinition(
            name = "Enabled",
            description = "Enable/disable background sync"
        )
        boolean enabled() default true;
    }
    
    /**
     * Reference to the SegmentStoreProvider registered by SegmentNodeStoreFactory.
     * This gives us access to the underlying ReadOnlyFileStore.
     * 
     * Following Cold Standby pattern: StandbyClientSync gets FileStore injected,
     * we get SegmentStoreProvider and cast to get the same access.
     */
    @Reference(target = "(role=composite-mount-oak-chain)")
    private volatile SegmentStoreProvider storeProvider;
    
    private String globalStoreUrl;
    private boolean enabled;
    private String lastKnownRevision;
    private long syncCount = 0;
    private long updateCount = 0;
    private long refreshSuccessCount = 0;
    private long refreshFailureCount = 0;
    
    @Activate
    protected void activate(Configuration config) {
        this.globalStoreUrl = config.globalStoreUrl();
        this.enabled = config.enabled();
        
        log.info("🔄 HTTP Segment Store Sync activated (Cold Standby Pattern)");
        log.info("   Global Store: {}", globalStoreUrl);
        log.info("   Sync Interval: {}s", config.syncInterval());
        log.info("   Enabled: {}", enabled);
        log.info("   📍 Running from oak-segment-tar (like Cold Standby)");
        log.info("   ✅ Direct access to ReadOnlyFileStore internals");
    }
    
    @Deactivate
    protected void deactivate() {
        log.info("🔄 HTTP Segment Store Sync deactivated");
        log.info("   Total syncs: {}", syncCount);
        log.info("   Updates detected: {}", updateCount);
        log.info("   Refresh success: {}", refreshSuccessCount);
        log.info("   Refresh failures: {}", refreshFailureCount);
    }
    
    @Override
    public void run() {
        if (!enabled) {
            return;
        }
        
        try {
            syncCount++;
            
            // Fetch latest revision from global store journal (like Cold Standby getHead())
            String remoteRevision = fetchRemoteHead();
            
            if (remoteRevision == null) {
                log.debug("Unable to fetch remote head from {}/journal.log", globalStoreUrl);
                return;
            }
            
            // Check if remote has advanced
            if (remoteRevision.equals(lastKnownRevision)) {
                log.debug("No new revisions (revision: {})", remoteRevision);
                return;
            }
            
            // Remote has new data!
            log.info("📥 New revision detected in global store!");
            if (lastKnownRevision != null) {
                log.info("   Previous: {}", lastKnownRevision.substring(0, Math.min(40, lastKnownRevision.length())));
            }
            log.info("   Current:  {}", remoteRevision.substring(0, Math.min(40, remoteRevision.length())));
            
            // Update the head using Cold Standby pattern with direct access
            if (storeProvider != null) {
                boolean refreshSuccess = updateFileStoreHead(remoteRevision);
                if (refreshSuccess) {
                    refreshSuccessCount++;
                    log.info("✅ Composite mount HEAD refreshed successfully (success: {}, failure: {})",
                             refreshSuccessCount, refreshFailureCount);
                } else {
                    refreshFailureCount++;
                    log.warn("⚠️  Failed to refresh composite mount HEAD (success: {}, failure: {})",
                             refreshSuccessCount, refreshFailureCount);
                }
            } else {
                log.warn("⚠️  SegmentStoreProvider not available - cannot refresh HEAD");
            }
            
            lastKnownRevision = remoteRevision;
            updateCount++;
            
            log.info("✅ Sync complete (update #{} of {} syncs)", updateCount, syncCount);
            
        } catch (Exception e) {
            log.warn("Failed to sync from global store", e);
        }
    }
    
    /**
     * Fetches the latest revision from the global store's journal.log.
     * This is analogous to Cold Standby's StandbyClient.getHead().
     * 
     * @return the latest revision string, or null if unavailable
     */
    private String fetchRemoteHead() {
        try {
            URL url = new URL(globalStoreUrl + "/journal.log");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            
            if (conn.getResponseCode() != 200) {
                log.debug("Failed to fetch journal.log: HTTP {}", conn.getResponseCode());
                return null;
            }
            
            // Read last line (latest revision)
            String lastLine = null;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.trim().isEmpty()) {
                        lastLine = line.trim();
                    }
                }
            }
            
            // Journal format: "revision timestamp root-revision"
            // Extract first token (the revision)
            if (lastLine != null && !lastLine.isEmpty()) {
                String[] parts = lastLine.split("\\s+");
                if (parts.length > 0) {
                    return parts[0];
                }
            }
            
            return null;
            
        } catch (Exception e) {
            log.debug("Error fetching remote head", e);
            return null;
        }
    }
    
    /**
     * Updates the FileStore's head to the new revision.
     * 
     * <p><strong>Cold Standby Pattern (StandbyClientSyncExecution line 77):</strong></p>
     * <pre>
     * store.getRevisions().setHead(before.getRecordId(), remoteHead);
     * </pre>
     * 
     * <p><strong>Our equivalent using ReadOnlyFileStore.setRevision():</strong></p>
     * <pre>
     * ReadOnlyFileStore fileStore = (ReadOnlyFileStore) storeProvider.getSegmentStore();
     * fileStore.setRevision(newRevisionString);
     * </pre>
     * 
     * <p>Since we're in oak-segment-tar, we have direct access to {@link ReadOnlyFileStore}
     * without needing exports or Fragment-Host declarations.
     * 
     * @param newRevision the new head revision string
     * @return true if successful, false otherwise
     */
    private boolean updateFileStoreHead(String newRevision) {
        try {
            SegmentStore segmentStore = storeProvider.getSegmentStore();
            
            if (segmentStore instanceof ReadOnlyFileStore) {
                ReadOnlyFileStore fileStore = (ReadOnlyFileStore) segmentStore;
                
                // Direct access! ReadOnlyFileStore.setRevision(String) is public
                // Internally it calls: revisions.setHead(currentHead, newHead)
                // Exactly like Cold Standby does in StandbyClientSyncExecution
                fileStore.setRevision(newRevision);
                
                log.debug("Called ReadOnlyFileStore.setRevision(\"{}\")", 
                         newRevision.substring(0, Math.min(40, newRevision.length())));
                
                return true;
            } else {
                log.warn("SegmentStore is not a ReadOnlyFileStore, cannot refresh. Type: {}", 
                         segmentStore != null ? segmentStore.getClass().getName() : "null");
                return false;
            }
            
        } catch (Exception e) {
            log.error("Failed to update FileStore head to revision: {}", newRevision, e);
            return false;
        }
    }
}

