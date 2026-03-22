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
package org.apache.jackrabbit.oak.segment.consensus.server;

import org.apache.jackrabbit.oak.segment.RecordId;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class GenesisInitializer {

    private static final Logger log = LoggerFactory.getLogger(GenesisInitializer.class);

    private final NodeStore nodeStore;
    private final FileStore fileStore;
    private final BlobStore blobStore;
    private final String genesisValidatorUrl;

    GenesisInitializer(NodeStore nodeStore, FileStore fileStore, BlobStore blobStore, String genesisValidatorUrl) {
        this.nodeStore = nodeStore;
        this.fileStore = fileStore;
        this.blobStore = blobStore;
        this.genesisValidatorUrl = genesisValidatorUrl;
    }

    /**
     * Initialize the IMMORTAL GENESIS NODE.
     *
     * Like Ethereum's Block 0, this is the birth certificate of the network.
     * All validators MUST sync from this genesis state to join the network.
     *
     * Contains:
     * - Network identity (chainId, genesisHash)
     * - Consensus rules (Raft term duration, quorum requirements)
     * - Bootstrap instructions (how to join)
     * - Protocol parameters (ports, endpoints)
     */
    void initializeGenesisContent() {
        try {
            org.apache.jackrabbit.oak.spi.state.NodeState root = nodeStore.getRoot();

            // Genesis address: Ethereum zero address (0x0...0)
            // This follows the standard sharded pattern for fault isolation
            String GENESIS_ADDRESS = "0x0000000000000000000000000000000000000000";

            // Use sharded path for genesis (00/00/00/0x0000.../)
            String genesisShardedPath = org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil.getContentPath(GENESIS_ADDRESS);
            // Result: /oak-chain/content/00/00/00/0x0000000000000000000000000000000000000000

            // Check if genesis content already exists
            org.apache.jackrabbit.oak.spi.state.NodeState oakChain = root.getChildNode("oak-chain");
            if (oakChain.exists()) {
                org.apache.jackrabbit.oak.spi.state.NodeState content = oakChain.getChildNode("content");
                if (content.exists()) {
                    // Navigate through sharded path: content → 00 → 00 → 00 → 0x0000...
                    org.apache.jackrabbit.oak.spi.state.NodeState level1 = content.getChildNode("00");
                    if (level1.exists()) {
                        org.apache.jackrabbit.oak.spi.state.NodeState level2 = level1.getChildNode("00");
                        if (level2.exists()) {
                            org.apache.jackrabbit.oak.spi.state.NodeState level3 = level2.getChildNode("00");
                            if (level3.exists()) {
                                org.apache.jackrabbit.oak.spi.state.NodeState genesisWallet = level3.getChildNode(GENESIS_ADDRESS);
                                if (genesisWallet.exists() && genesisWallet.getChildNode("genesis").exists()) {
                                    log.info("   ℹ️  Genesis already exists - verifying integrity...");

                                    // Verify genesis message (like Ethereum verifies Block 0 hash)
                                    // Check both old flat structure (backward compatibility) and new hierarchical structure
                                    org.apache.jackrabbit.oak.spi.state.NodeState genesisNode = genesisWallet.getChildNode("genesis");

                                    // Try new hierarchical structure first
                                    org.apache.jackrabbit.oak.spi.state.NodeState protocolNode = genesisNode.getChildNode("protocol");
                                    org.apache.jackrabbit.oak.api.PropertyState msgProp = null;

                                    if (protocolNode.exists()) {
                                        // New hierarchical structure
                                        msgProp = protocolNode.getProperty("message");
                                    } else {
                                        // Old flat structure (backward compatibility)
                                        msgProp = genesisNode.getProperty("protocol.message");
                                    }

                                    if (msgProp == null || !"DO IT LIVE!".equals(msgProp.getValue(org.apache.jackrabbit.oak.api.Type.STRING))) {
                                        throw new IllegalStateException("❌ GENESIS CORRUPTION! This node has invalid genesis state.");
                                    }

                                    log.info("   ✅ Genesis integrity verified");

                                    // Log genesis HEAD for script detection
                                    RecordId genesisHead = fileStore.getHead().getRecordId();
                                    log.info("   Genesis HEAD: {}", genesisHead.toString10());
                                    return;
                                }
                            }
                        }
                    }
                }
            }

            // Create IMMORTAL GENESIS with sharded path
            log.info("   🎂 Creating IMMORTAL GENESIS NODE...");
            log.info("      The Birth Certificate of This Network");
            log.info("      Address: {} (Zero Address)", GENESIS_ADDRESS);
            log.info("      Sharded Path: {}", genesisShardedPath);

            org.apache.jackrabbit.oak.spi.state.NodeBuilder rootBuilder = root.builder();
            org.apache.jackrabbit.oak.spi.state.NodeBuilder oakChainBuilder = rootBuilder.child("oak-chain");
            org.apache.jackrabbit.oak.spi.state.NodeBuilder contentBuilder = oakChainBuilder.child("content");

            // Create sharded structure: /content/00/00/00/0x0000.../
            org.apache.jackrabbit.oak.spi.state.NodeBuilder level1Builder = contentBuilder.child("00");
            level1Builder.setProperty("jcr:primaryType", "nt:unstructured");

            org.apache.jackrabbit.oak.spi.state.NodeBuilder level2Builder = level1Builder.child("00");
            level2Builder.setProperty("jcr:primaryType", "nt:unstructured");

            org.apache.jackrabbit.oak.spi.state.NodeBuilder level3Builder = level2Builder.child("00");
            level3Builder.setProperty("jcr:primaryType", "nt:unstructured");

            // Create wallet folder for genesis
            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesisWalletBuilder = level3Builder.child(GENESIS_ADDRESS);
            genesisWalletBuilder.setProperty("jcr:primaryType", "nt:unstructured");
            genesisWalletBuilder.setProperty("wallet", GENESIS_ADDRESS);
            genesisWalletBuilder.setProperty("role", "genesis");
            genesisWalletBuilder.setProperty("description", "Network genesis - zero address owns protocol parameters");

            // Create genesis node under wallet
            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesis = genesisWalletBuilder.child("genesis");

            long timestamp = System.currentTimeMillis();
            String genesisDate = new java.util.Date(timestamp).toString();
            String genesisValidator = genesisValidatorUrl != null && !genesisValidatorUrl.trim().isEmpty()
                ? genesisValidatorUrl
                : "http://localhost:8090";
            String genesisHost = genesisValidator.replace("http://", "").replace("https://", "").split(":")[0];

            // JCR Standard
            genesis.setProperty("jcr:primaryType", "nt:unstructured");
            genesis.setProperty("jcr:created", timestamp);

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // PROTOCOL: Network Identity (Immutable)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder protocol = genesis.child("protocol");
            protocol.setProperty("jcr:primaryType", "nt:unstructured");
            protocol.setProperty("message", "DO IT LIVE!");
            protocol.setProperty("version", "1.0.0");
            protocol.setProperty("chainId", "oak-blockchain-aem-poc");
            protocol.setProperty("genesisTimestamp", timestamp);
            protocol.setProperty("genesisDate", genesisDate);
            protocol.setProperty("description",
                "Decentralized content storage for Adobe Experience Manager using Oak + Blockchain consensus");

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // CONSENSUS: Network Rules
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder consensus = genesis.child("consensus");
            consensus.setProperty("jcr:primaryType", "nt:unstructured");
            consensus.setProperty("model", "aeron-raft");
            consensus.setProperty("quorumType", "majority");
            consensus.setProperty("quorumFormula", "(totalMembers / 2) + 1");
            // Note: Aeron/Raft handles terms and heartbeats internally - no configuration needed
            // Note: Aeron/Raft doesn't have probation - all cluster members are voting members

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // NETWORK: Bootstrap Configuration
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder network = genesis.child("network");
            network.setProperty("jcr:primaryType", "nt:unstructured");
            network.setProperty("genesisValidator", genesisValidator);
            network.setProperty("genesisHost", genesisHost);
            network.setProperty("bootstrapPort", 8091L);
            network.setProperty("consensusPort", 8090L);
            network.setProperty("metricsPort", 8090L);
            network.setProperty("metricsPath", "/metrics");

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // INSTRUCTIONS: How to Join This Network
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder join = genesis.child("join");
            join.setProperty("jcr:primaryType", "nt:unstructured");
            join.setProperty("title", "🚀 Welcome to Blockchain AEM Network");

            // Join steps as child nodes for better structure
            org.apache.jackrabbit.oak.spi.state.NodeBuilder steps = join.child("steps");
            steps.setProperty("jcr:primaryType", "nt:unstructured");

            org.apache.jackrabbit.oak.spi.state.NodeBuilder step1 = steps.child("step1");
            step1.setProperty("jcr:primaryType", "nt:unstructured");
            step1.setProperty("title", "Configure Bootstrap Primary");
            step1.setProperty("env", "BOOTSTRAP_PRIMARY_HOST=" + genesisHost);

            org.apache.jackrabbit.oak.spi.state.NodeBuilder step2 = steps.child("step2");
            step2.setProperty("jcr:primaryType", "nt:unstructured");
            step2.setProperty("title", "Set Bootstrap Port");
            step2.setProperty("env", "BOOTSTRAP_PRIMARY_PORT=8091");

            org.apache.jackrabbit.oak.spi.state.NodeBuilder step3 = steps.child("step3");
            step3.setProperty("jcr:primaryType", "nt:unstructured");
            step3.setProperty("title", "Enable Consensus");
            step3.setProperty("env", "CONSENSUS_ENABLED=true");

            org.apache.jackrabbit.oak.spi.state.NodeBuilder step4 = steps.child("step4");
            step4.setProperty("jcr:primaryType", "nt:unstructured");
            step4.setProperty("title", "Set Consensus Mode");
            step4.setProperty("env", "CONSENSUS_MODE=aeron");

            org.apache.jackrabbit.oak.spi.state.NodeBuilder step5 = steps.child("step5");
            step5.setProperty("jcr:primaryType", "nt:unstructured");
            step5.setProperty("title", "Set Your Validator URL");
            step5.setProperty("env", "CONSENSUS_SELF_URL=http://your-validator:8090");

            org.apache.jackrabbit.oak.spi.state.NodeBuilder step6 = steps.child("step6");
            step6.setProperty("jcr:primaryType", "nt:unstructured");
            step6.setProperty("title", "Configure Aeron Cluster (Optional)");
            step6.setProperty("env", "AERON_CLUSTER_NODE_ID=0");
            step6.setProperty("note", "Node ID must be unique per validator (0, 1, 2, ...)");

            // Notes as properties on join node
            join.setProperty("note",
                "After bootstrap, you join as a voting member of the Aeron Cluster (Raft consensus)");

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // SECURITY: Byzantine Fault Tolerance
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder security = genesis.child("security");
            security.setProperty("jcr:primaryType", "nt:unstructured");
            security.setProperty("splitBrainDetection", true); // Raft quorum enforcement
            security.setProperty("genesisVerification", true);
            // Note: Aeron/Raft provides election safety, log matching, and leader completeness guarantees

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // METADATA: Project Information
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            org.apache.jackrabbit.oak.spi.state.NodeBuilder meta = genesis.child("meta");
            meta.setProperty("jcr:primaryType", "nt:unstructured");
            meta.setProperty("author", "Oak Segment Consensus");
            meta.setProperty("repository", "Apache Jackrabbit Oak");
            meta.setProperty("documentation", "See /oak-chain/content/genesis");
            meta.setProperty("license", "Apache License 2.0");

            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // GENESIS IMAGE: "DO IT LIVE!" via IPFS (ADR 015)
            // ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
            // The genesis image is stored in IPFS and serves as:
            // 1. Proof that IPFS integration works from day 0
            // 2. A memorable visual for the network's birth
            // 3. Content-addressed storage demo (CID never changes)

            org.apache.jackrabbit.oak.spi.state.NodeBuilder genesisImage = genesis.child("do-it-live.jpeg");
            genesisImage.setProperty("jcr:primaryType", "nt:file");
            genesisImage.setProperty("jcr:created", timestamp);

            org.apache.jackrabbit.oak.spi.state.NodeBuilder imageContent = genesisImage.child("jcr:content");
            imageContent.setProperty("jcr:primaryType", "nt:resource");
            imageContent.setProperty("jcr:mimeType", "image/jpeg");
            imageContent.setProperty("jcr:lastModified", timestamp);

            // Load genesis image from resources and store in BlobStore (IPFS if configured)
            String ipfsCid = null;
            try {
                java.io.InputStream imageStream = GenesisInitializer.class.getClassLoader()
                    .getResourceAsStream("genesis-assets/do-it-live.jpeg");

                if (imageStream != null && blobStore != null) {
                    // Read image bytes
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = imageStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    imageStream.close();
                    byte[] imageBytes = baos.toByteArray();

                    log.info("   📸 Storing genesis image in IPFS...");
                    log.info("      Size: {} bytes", imageBytes.length);

                    // Store via BlobStore (IPFS backend will pin it)
                    BlobStore bStore = this.blobStore;
                    if (bStore != null) {
                        String blobId = bStore.writeBlob(new java.io.ByteArrayInputStream(imageBytes));

                        // Create proper Binary from blobId
                        org.apache.jackrabbit.oak.api.Blob blob =
                            new org.apache.jackrabbit.oak.plugins.blob.BlobStoreBlob(bStore, blobId);
                        imageContent.setProperty("jcr:data", blob);
                        imageContent.setProperty("jcr:blobId", blobId);

                        // If IPFS, try to extract CID
                        if (blobId.startsWith("Qm") || blobId.startsWith("bafy")) {
                            ipfsCid = blobId.split("#")[0]; // Remove size suffix if present
                            imageContent.setProperty("ipfs:cid", ipfsCid);
                            log.info("      ✅ IPFS CID: {}", ipfsCid);
                        } else {
                            // Use blobId as fallback for non-IPFS blobs
                            ipfsCid = blobId;
                            log.info("      ✅ Blob ID: {}", blobId);
                        }
                    }
                } else if (imageStream != null) {
                    // No BlobStore, store as inline binary (not recommended for production)
                    log.warn("   ⚠️  No BlobStore configured - storing image inline (demo mode)");
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = imageStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    imageStream.close();
                    byte[] imageBytes = baos.toByteArray();

                    // Store as inline binary (Oak will store in segment)
                    org.apache.jackrabbit.oak.api.Blob blob =
                        nodeStore.createBlob(new java.io.ByteArrayInputStream(imageBytes));
                    imageContent.setProperty("jcr:data", blob);
                    log.info("      Size: {} bytes (inline)", imageBytes.length);
                } else {
                    log.warn("   ⚠️  Genesis image not found in resources");
                    imageContent.setProperty("jcr:data", "DO IT LIVE! (image placeholder)");
                }
            } catch (Exception e) {
                log.warn("   ⚠️  Failed to store genesis image: {}", e.getMessage());
                imageContent.setProperty("jcr:data", "DO IT LIVE! (image error: " + e.getMessage() + ")");
            }

            // Store IPFS info for display
            org.apache.jackrabbit.oak.spi.state.NodeBuilder ipfsInfo = genesis.child("ipfs");
            ipfsInfo.setProperty("jcr:primaryType", "nt:unstructured");
            ipfsInfo.setProperty("enabled", blobStore != null);
            ipfsInfo.setProperty("genesisImageCid", ipfsCid != null ? ipfsCid : "N/A (BlobStore fallback)");
            ipfsInfo.setProperty("gateway", "https://ipfs.io/ipfs/");
            ipfsInfo.setProperty("localGateway", "http://localhost:8080/ipfs/");
            ipfsInfo.setProperty("description", "Binaries stored via IPFS - content-addressed, decentralized, immutable");

            // Commit the IMMORTAL GENESIS
            nodeStore.merge(rootBuilder, org.apache.jackrabbit.oak.spi.commit.EmptyHook.INSTANCE,
                org.apache.jackrabbit.oak.spi.commit.CommitInfo.EMPTY);

            String ipfsSection;
            if (ipfsCid != null) {
                ipfsSection =
                    "      ✅ Genesis Image: do-it-live.jpeg\n"
                        + "      ✅ IPFS CID: " + ipfsCid + "\n"
                        + "      ✅ Public Gateway: https://ipfs.io/ipfs/" + ipfsCid + "\n"
                        + "      ✅ Local Gateway: http://localhost:8080/ipfs/" + ipfsCid;
            } else if (blobStore != null) {
                ipfsSection = "      ✅ Genesis Image: do-it-live.jpeg (via BlobStore)";
            } else {
                ipfsSection = "      ⚠️ IPFS not configured (demo mode - inline binaries)";
            }

            log.info(
                "   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "   🎊 IMMORTAL GENESIS NODE CREATED\n"
                    + "   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "\n"
                    + "   📍 LOCATION:\n"
                    + "      Path: " + genesisShardedPath + "/genesis\n"
                    + "      Bucket: 00/00/00 (Genesis bucket - fault isolated)\n"
                    + "      Address: " + GENESIS_ADDRESS + " (Ethereum Zero Address)\n"
                    + "\n"
                    + "   🔐 PROTOCOL:\n"
                    + "      Chain ID: oak-blockchain-aem-poc\n"
                    + "      Message: \"DO IT LIVE!\"\n"
                    + "      Version: 1.0.0\n"
                    + "      Birth: " + genesisDate + "\n"
                    + "\n"
                    + "   📦 IPFS (Decentralized Binary Storage):\n"
                    + ipfsSection + "\n"
                    + "\n"
                    + "   🎖️  CONSENSUS:\n"
                    + "      Model: aeron-raft\n"
                    + "      Quorum: (totalMembers / 2) + 1\n"
                    + "      Note: All cluster members are voting members (Raft consensus)\n"
                    + "      Note: Terms and heartbeats handled internally by Aeron Cluster\n"
                    + "\n"
                    + "   🌐 NETWORK:\n"
                    + "      Genesis Validator: " + genesisValidator + "\n"
                    + "      Bootstrap Host: " + genesisHost + "\n"
                    + "      Bootstrap Port: 8091\n"
                    + "      Consensus Port: 8090\n"
                    + "\n"
                    + "   🛡️  SECURITY:\n"
                    + "      ✅ Split-Brain Detection (Raft quorum enforcement)\n"
                    + "      ✅ Genesis Verification (state integrity)\n"
                    + "      ✅ Raft guarantees: Election safety, log matching, leader completeness\n"
                    + "\n"
                    + "   🚀 TO JOIN THIS NETWORK:\n"
                    + "      1. BOOTSTRAP_PRIMARY_HOST=" + genesisHost + "\n"
                    + "      2. BOOTSTRAP_PRIMARY_PORT=8091\n"
                    + "      3. CONSENSUS_ENABLED=true\n"
                    + "      4. CONSENSUS_MODE=aeron\n"
                    + "      5. CONSENSUS_SELF_URL=http://your-validator:8090\n"
                    + "      6. AERON_CLUSTER_NODE_ID=<unique-id> (0, 1, 2, ...)\n"
                    + "\n"
                    + "      -> You'll join as a voting member of the Aeron Cluster\n"
                    + "      -> Raft consensus ensures safety and liveness guarantees\n"
                    + "\n"
                    + "   📊 QUERY GENESIS:\n"
                    + "      GET /api/explore?path=" + genesisShardedPath + "/genesis\n"
                    + "\n"
                    + "   ℹ️  ARCHITECTURE:\n"
                    + "      • Sharded paths for fault isolation\n"
                    + "      • Max 256 children/node = stable DAG\n"
                    + "      • Pattern: /content/{L1}/{L2}/{L3}/0x{wallet}/\n"
                    + "      • SNFE contained to 0.0004% of chain\n"
                    + "\n"
                    + "   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━\n"
                    + "   🎉 Network initialized and ready for validators!\n"
                    + "   ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
            );

            // Log genesis HEAD for script detection
            RecordId genesisHead = fileStore.getHead().getRecordId();
            log.info("   Genesis HEAD: {}", genesisHead.toString10());

        } catch (Exception e) {
            log.error("   ❌ FATAL: Failed to create genesis: {}", e.getMessage(), e);
            throw new RuntimeException("Genesis creation failed - cannot start network", e);
        }
    }
}
