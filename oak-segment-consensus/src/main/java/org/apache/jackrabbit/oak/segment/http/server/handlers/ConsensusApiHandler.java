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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import org.apache.jackrabbit.oak.segment.consensus.queue.DurabilityState;
import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;
import org.apache.jackrabbit.oak.segment.consensus.service.DeleteApplicationService;
import org.apache.jackrabbit.oak.segment.consensus.service.FileStoreFlushService;
import org.apache.jackrabbit.oak.segment.consensus.service.WriteApplicationService;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owns the write/delete application services that apply replicated mutations, and wires their
 * head, SSE, fragmentation, CID and durability callbacks. The consensus HTTP endpoints are
 * routed by {@link org.apache.jackrabbit.oak.segment.http.server.RequestRouter} to their own handlers.
 */
public class ConsensusApiHandler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConsensusApiHandler.class);

    private final ServerContext context;
    private final WriteApplicationService writeApplicationService;
    private final DeleteApplicationService deleteApplicationService;
    private final FileStoreFlushService flushService;

    public ConsensusApiHandler(ServerContext context) {
        this.context = context;
        
        // Initialize application services
        this.flushService = new FileStoreFlushService(context.fileStore);
        this.writeApplicationService = new WriteApplicationService(
            context.fileStore,
            () -> context.authoritativeNodeStore != null ? context.authoritativeNodeStore : context.nodeStore,
            () -> context.blobStore,
            flushService
        );
        this.deleteApplicationService = new DeleteApplicationService(
            context.fileStore,
            () -> context.authoritativeNodeStore != null ? context.authoritativeNodeStore : context.nodeStore,
            flushService
        );
        
        // Wire callbacks for integration
        wireServiceCallbacks();
    }
    
    /**
     * Wire callbacks to integrate services with the broader system.
     */
    private void wireServiceCallbacks() {
        // HEAD update callback
        WriteApplicationService.HeadUpdateCallback headCallback = newHead -> {
            if (context.aeronConsensusEngine != null) {
                context.aeronConsensusEngine.updateLatestHead(newHead);
            }
        };
        writeApplicationService.setHeadUpdateCallback(headCallback);
        deleteApplicationService.setHeadUpdateCallback(headCallback::updateHead);
        
        // SSE event callback for writes
        if (context.eventBroadcaster != null) {
            writeApplicationService.setSseEventCallback(new WriteApplicationService.SSEEventCallback() {
                @Override
                public void emitContentWrite(String path, String wallet, String org, 
                                            String message, String signature, String contentType) {
                    try {
                        context.eventBroadcaster.emitContentWrite(path, wallet, org, message, signature, contentType);
                    } catch (Exception e) {
                        log.debug("Failed to emit SSE write event: {}", e.getMessage());
                    }
                }
                
                @Override
                public void emitBinaryUpload(String path, String wallet, String org, 
                                            String message, String ipfsCid, String mimeType) {
                    try {
                        context.eventBroadcaster.emitBinaryUpload(path, wallet, org, message, ipfsCid, null, mimeType);
                    } catch (Exception e) {
                        log.debug("Failed to emit SSE binary event: {}", e.getMessage());
                    }
                }
            });
            
            // SSE event callback for deletes
            deleteApplicationService.setSseEventCallback((path, wallet, org, signature) -> {
                try {
                    context.eventBroadcaster.emitContentDelete(path, wallet, org, signature);
                } catch (Exception e) {
                    log.debug("Failed to emit SSE delete event: {}", e.getMessage());
                }
            });
        }
        
        // Fragmentation tracking callback
        if (context.fragmentationTracker != null) {
            writeApplicationService.setFragmentationCallback(this::trackFragmentation);
        }
        
        // CID mapping callback
        if (context.cidMappingService != null) {
            writeApplicationService.setCidMappingCallback(blobId -> {
                try {
                    return context.cidMappingService.getCid(blobId).orElse(null);
                } catch (Exception e) {
                    log.debug("CID mapping lookup failed: {}", e.getMessage());
                    return null;
                }
            });
        }

        // Durability callbacks must tolerate late context wiring during startup.
        writeApplicationService.setDurabilityCallback(new WriteApplicationService.DurabilityCallback() {
            @Override
            public void onDurable(String proposalId, String durableHead) {
                forwardDurabilitySuccess(proposalId, durableHead);
            }

            @Override
            public void onFailure(String proposalId, String error) {
                forwardDurabilityFailure(proposalId, error);
            }
        });
        deleteApplicationService.setDurabilityCallback(new DeleteApplicationService.DurabilityCallback() {
            @Override
            public void onDurable(String proposalId, String durableHead) {
                forwardDurabilitySuccess(proposalId, durableHead);
            }

            @Override
            public void onFailure(String proposalId, String error) {
                forwardDurabilityFailure(proposalId, error);
            }
        });

        if (context.aeronConsensusEngine != null) {
            context.aeronConsensusEngine.setDurabilityStatusCallback(new org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine.DurabilityStatusCallback() {
                @Override
                public void onDurable(String proposalId, String durableHead) {
                    applyDurabilityStatus(proposalId, DurabilityState.ACKED, durableHead, null);
                }

                @Override
                public void onFailure(String proposalId, String error) {
                    applyDurabilityStatus(proposalId, DurabilityState.FAILED, null, error);
                }
            });
        }
    }

    public void refreshCallbacks() {
        wireServiceCallbacks();
    }

    private void forwardDurabilitySuccess(String proposalId, String durableHead) {
        if (context.aeronConsensusEngine != null) {
            context.aeronConsensusEngine.sendSegmentPersisted(proposalId, durableHead, true, null);
            return;
        }
        applyDurabilityStatus(proposalId, DurabilityState.ACKED, durableHead, null);
    }

    private void forwardDurabilityFailure(String proposalId, String error) {
        if (context.aeronConsensusEngine != null) {
            context.aeronConsensusEngine.sendSegmentPersisted(proposalId, null, false, error);
            return;
        }
        applyDurabilityStatus(proposalId, DurabilityState.FAILED, null, error);
    }

    private void applyDurabilityStatus(String proposalId, DurabilityState state, String durableHead, String error) {
        if (context.proposalQueueManager == null) {
            log.warn("⚠️  Proposal queue unavailable - cannot update durability for {} ({})", proposalId, state);
            return;
        }
        context.proposalQueueManager.updateDurability(proposalId, state, durableHead, error);
    }

    @Override
    public void close() {
        flushService.close();
    }
    
    /**
     * ✈️ AERON NATIVE: Apply replicated write to FileStore.
     * This is called from AeronConsensusEngine.onSessionMessage() after Aeron replicates the write.
     * 
     * <p>Delegates to {@link WriteApplicationService} for the actual write application.
     * 
     * @param ipfsCid IPFS CID from client-side upload (ADR 016), may be null
     */
    public void applyReplicatedWrite(String walletAddress, String path, String contentType,
                                     String message, String signature, String intentToken,
                                     String blobId, String mimeType, String ipfsCid,
                                     String proposalId) {
        applyReplicatedWriteWithAuditMetadata(
            walletAddress,
            path,
            contentType,
            message,
            signature,
            intentToken,
            blobId,
            mimeType,
            ipfsCid,
            MutationAuditMetadata.write(null, null, proposalId, null, null, null, null)
        );
    }

    public void applyReplicatedWriteWithAuditMetadata(String walletAddress, String path, String contentType,
                                                      String message, String signature, String intentToken,
                                                      String blobId, String mimeType, String ipfsCid,
                                                      MutationAuditMetadata auditMetadata) {
        writeApplicationService.applyWriteWithAuditMetadata(
            walletAddress, path, contentType, message, signature,
            intentToken, blobId, mimeType, ipfsCid, auditMetadata
        );
    }
    
    /**
     * ✈️ AERON NATIVE: Apply replicated delete to FileStore.
     * This is called from AeronConsensusEngine.onSessionMessage() after Aeron replicates the delete.
     * 
     * <p>Delegates to {@link DeleteApplicationService} for the actual delete application.
     * 
     * Delete in Oak = Remove node from tree (writes new segment saying "path no longer exists")
     * Old segments remain until GC/compaction runs
     */
    public void applyReplicatedDelete(String walletAddress, String path, String signature, String proposalId) {
        applyReplicatedDeleteWithAuditMetadata(
            walletAddress,
            path,
            signature,
            MutationAuditMetadata.delete(null, null, proposalId, null, null, null, null)
        );
    }

    public void applyReplicatedDeleteWithAuditMetadata(String walletAddress, String path, String signature,
                                                       MutationAuditMetadata auditMetadata) {
        deleteApplicationService.applyDeleteWithAuditMetadata(walletAddress, path, signature, auditMetadata);
    }
    
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    // DEAD CODE REMOVED - January 2026 (tech debt cleanup)
    // - discoverLeaderFromPeerClusterState() - Use LeaderDiscoveryService instead
    // - resolveUrlToIP() - Only used by discoverLeaderFromPeerClusterState
    // - getNextValidatorInRotation() - Never called
    // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
    
    /**
     * Track fragmentation: Check for new TAR files and associate with wallet address.
     */
    private void trackFragmentation(String walletAddress) {
        if (context.fragmentationTracker == null || walletAddress == null || walletAddress.isEmpty()) {
            return;
        }
        
        try {
            // Get current TAR files
            java.util.List<String> currentTarFiles = new java.util.ArrayList<>();
            try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.list(context.storeDirectory)) {
                currentTarFiles = paths
                    .filter(p -> p.toString().endsWith(".tar"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .collect(java.util.stream.Collectors.toList());
            }
            
            // Find new TAR files (not yet tracked for this entity)
            java.util.List<String> entityTarFiles = context.fragmentationTracker.getTarFilesForEntity(walletAddress);
            java.util.Set<String> knownTarFiles = new java.util.HashSet<>(entityTarFiles);
            
            for (String tarFile : currentTarFiles) {
                if (!knownTarFiles.contains(tarFile)) {
                    // New TAR file - get its size
                    java.nio.file.Path tarPath = context.storeDirectory.resolve(tarFile);
                    long tarFileSize = java.nio.file.Files.exists(tarPath) 
                        ? java.nio.file.Files.size(tarPath) 
                        : 0;
                    
                    // Record write (this will create or update metrics)
                    context.fragmentationTracker.recordWrite(walletAddress, tarFile, tarFileSize);
                    
                    log.debug("📊 Fragmentation tracked: entity={}, tarFile={}, size={}", 
                        walletAddress, tarFile, tarFileSize);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to track fragmentation for entity {}: {}", walletAddress, e.getMessage());
        }
    }
    
}
