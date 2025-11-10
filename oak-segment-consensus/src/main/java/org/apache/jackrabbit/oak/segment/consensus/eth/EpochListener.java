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
package org.apache.jackrabbit.oak.segment.consensus.eth;

import org.apache.jackrabbit.oak.api.CommitFailedException;
import org.apache.jackrabbit.oak.segment.SegmentNodeStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Listens for new Ethereum Beacon Chain epochs and writes them to Oak repository.
 * 
 * <p>This component demonstrates the Ethereum → AEM bridge by:
 * 1. Polling the Beacon Chain API every 30 seconds
 * 2. Detecting new finalized epochs
 * 3. Writing epoch metadata to /oak-chain/content/ethereum/epoch/{epochNumber}
 * 4. All connected Sling authors see the new content via HTTP segment transfer
 * 
 * <p>This creates a real-world demonstration of blockchain data flowing into
 * enterprise content management at scale.
 * 
 * <h2>Content Structure</h2>
 * <pre>
 * /oak-chain/content/ethereum/epoch/
 *   ├── 406180/
 *   │   ├── jcr:primaryType = "nt:unstructured"
 *   │   ├── epochNumber = 406180
 *   │   ├── timestamp = 1699644743000
 *   │   ├── blocksProposed = 30
 *   │   ├── blocksSkipped = 2
 *   │   ├── attestations = 172
 *   │   └── ...
 *   ├── 406181/
 *   └── 406182/
 * </pre>
 */
public class EpochListener {
    private static final Logger log = LoggerFactory.getLogger(EpochListener.class);
    
    private static final int POLL_INTERVAL_SECONDS = 30;
    private static final String CONTENT_PATH = "/oak-chain/content/ethereum/epoch";
    
    private final BeaconChainClient beaconClient;
    private final SegmentNodeStore nodeStore;
    private final ScheduledExecutorService scheduler;
    
    private long lastProcessedEpoch = -1;
    
    /**
     * Create a new Ethereum epoch listener.
     * 
     * @param beaconApiUrl Beacon Chain API base URL
     * @param nodeStore Oak NodeStore to write epoch data to
     */
    public EpochListener(String beaconApiUrl, SegmentNodeStore nodeStore) {
        this.beaconClient = new BeaconChainClient(beaconApiUrl);
        this.nodeStore = nodeStore;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "EpochListener");
            t.setDaemon(true);
            return t;
        });
    }
    
    /**
     * Start listening for new epochs.
     * 
     * <p>Polls every 30 seconds (epochs finalize every ~6.4 minutes, so this
     * provides timely detection without excessive API calls).
     */
    public void start() {
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🌉 Ethereum → AEM Bridge");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        log.info("🎧 Starting Ethereum epoch listener...");
        log.info("   Polling interval: {} seconds", POLL_INTERVAL_SECONDS);
        log.info("   Target path: {}", CONTENT_PATH);
        log.info("   Epoch duration: ~6.4 minutes (32 slots × 12 seconds)");
        log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        
        // Initialize with current epoch
        try {
            EpochData current = beaconClient.getLatestFinalizedEpoch();
            lastProcessedEpoch = current.epochNumber;
            log.info("📍 Starting from epoch: {}", lastProcessedEpoch);
            
            // Write current epoch immediately
            writeEpochToRepository(current);
            
        } catch (Exception e) {
            log.error("Failed to initialize with current epoch", e);
        }
        
        // Poll every 30 seconds for new epochs
        scheduler.scheduleAtFixedRate(() -> {
            try {
                checkForNewEpoch();
            } catch (Exception e) {
                log.error("Error checking for new epoch", e);
            }
        }, POLL_INTERVAL_SECONDS, POLL_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }
    
    /**
     * Check for new finalized epoch and write to repository.
     */
    private void checkForNewEpoch() {
        try {
            EpochData epoch = beaconClient.getLatestFinalizedEpoch();
            
            if (epoch.epochNumber > lastProcessedEpoch) {
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                log.info("🆕 NEW EPOCH DETECTED!");
                log.info("   Previous: {}", lastProcessedEpoch);
                log.info("   Current:  {}", epoch.epochNumber);
                log.info("   Blocks:   {}/{} ({}% success)", 
                         epoch.blocksProposed - epoch.blocksSkipped,
                         epoch.blocksProposed,
                         (100 * (epoch.blocksProposed - epoch.blocksSkipped) / epoch.blocksProposed));
                log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
                
                writeEpochToRepository(epoch);
                lastProcessedEpoch = epoch.epochNumber;
                
                log.info("✅ Epoch {} committed to repository", epoch.epochNumber);
                log.info("   Path: {}/{}", CONTENT_PATH, epoch.epochNumber);
                log.info("   All connected Sling authors will see this content!");
                
            } else {
                log.debug("⏰ No new epoch (current: {})", lastProcessedEpoch);
            }
            
        } catch (Exception e) {
            log.error("Failed to process epoch", e);
        }
    }
    
    /**
     * Write epoch data to Oak repository.
     * 
     * <p>Creates a new node at /oak-chain/content/ethereum/epoch/{epochNumber}
     * with all epoch metadata as properties.
     * 
     * @param epoch Epoch data to write
     * @throws CommitFailedException if commit fails
     */
    private void writeEpochToRepository(EpochData epoch) throws CommitFailedException {
        NodeState root = nodeStore.getRoot();
        NodeBuilder rootBuilder = root.builder();
        
        // Ensure path exists: /oak-chain/content/ethereum/epoch
        NodeBuilder oakChain = getOrCreateChild(rootBuilder, "oak-chain");
        NodeBuilder content = getOrCreateChild(oakChain, "content");
        NodeBuilder ethereum = getOrCreateChild(content, "ethereum");
        NodeBuilder epochRoot = getOrCreateChild(ethereum, "epoch");
        
        // Create epoch node: /oak-chain/content/ethereum/epoch/{epochNumber}
        String epochNodeName = String.valueOf(epoch.epochNumber);
        NodeBuilder epochNode = epochRoot.child(epochNodeName);
        
        // Set properties
        epochNode.setProperty("jcr:primaryType", "nt:unstructured");
        epochNode.setProperty("epochNumber", epoch.epochNumber);
        epochNode.setProperty("timestamp", epoch.timestamp);
        epochNode.setProperty("blocksProposed", epoch.blocksProposed);
        epochNode.setProperty("blocksSkipped", epoch.blocksSkipped);
        epochNode.setProperty("attestations", epoch.attestations);
        epochNode.setProperty("totalValidators", epoch.totalValidators);
        epochNode.setProperty("activeValidators", epoch.activeValidators);
        epochNode.setProperty("slashings", epoch.slashings);
        epochNode.setProperty("deposits", epoch.deposits);
        epochNode.setProperty("voluntaryExits", epoch.voluntaryExits);
        epochNode.setProperty("finalized", epoch.finalized);
        
        // Add human-readable timestamp
        epochNode.setProperty("timestampISO", 
            java.time.Instant.ofEpochMilli(epoch.timestamp).toString());
        
        // Commit to repository
        nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY);
        
        log.info("📝 Committed epoch {} to FileStore", epoch.epochNumber);
        log.info("   Journal.log updated → all clients will fetch");
    }
    
    /**
     * Get or create a child node with nt:unstructured type.
     */
    private NodeBuilder getOrCreateChild(NodeBuilder parent, String name) {
        if (!parent.hasChildNode(name)) {
            NodeBuilder child = parent.child(name);
            child.setProperty("jcr:primaryType", "nt:unstructured");
            return child;
        }
        return parent.child(name);
    }
    
    /**
     * Stop the listener.
     */
    public void stop() {
        log.info("🛑 Stopping Ethereum epoch listener...");
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("✅ Epoch listener stopped");
    }
}

