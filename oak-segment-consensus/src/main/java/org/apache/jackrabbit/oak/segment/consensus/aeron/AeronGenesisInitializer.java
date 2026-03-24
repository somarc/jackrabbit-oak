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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.plugins.blob.BlobStoreBlob;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;

final class AeronGenesisInitializer {

    private static final Logger log = LoggerFactory.getLogger(AeronGenesisInitializer.class);

    static final String GENESIS_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final String DEFAULT_GENESIS_VALIDATOR_URL = "http://localhost:8090";
    private static final String GENESIS_IMAGE_RESOURCE = "genesis-assets/do-it-live.jpeg";

    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final BlobStore blobStore;

    AeronGenesisInitializer(FileStore fileStore, NodeStore nodeStore, BlobStore blobStore) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
        this.blobStore = blobStore;
    }

    void initializeGenesisContent(String proposalJson) {
        initializeGenesisContent(GenesisProposal.fromJson(proposalJson));
    }

    void initializeGenesisContent(GenesisProposal proposal) {
        log.info("Creating deterministic genesis from replicated proposal: validator={}, timestamp={}",
            proposal.getGenesisValidatorUrl(), proposal.getTimestamp());

        try {
            if (getGenesisNode(nodeStore.getRoot()).exists()) {
                log.info("Genesis already exists - skipping duplicate genesis application");
                return;
            }

            long timestamp = proposal.getTimestamp();
            String genesisDate = new Date(timestamp).toString();
            String genesisValidator = proposal.getGenesisValidatorUrl();

            NodeBuilder rootBuilder = nodeStore.getRoot().builder();
            NodeBuilder oakChain = rootBuilder.child("oak-chain");
            oakChain.setProperty("jcr:primaryType", "nt:unstructured");

            NodeBuilder level1 = oakChain.child("00");
            level1.setProperty("jcr:primaryType", "nt:unstructured");

            NodeBuilder level2 = level1.child("00");
            level2.setProperty("jcr:primaryType", "nt:unstructured");

            NodeBuilder level3 = level2.child("00");
            level3.setProperty("jcr:primaryType", "nt:unstructured");

            NodeBuilder genesisWallet = level3.child(GENESIS_ADDRESS);
            genesisWallet.setProperty("jcr:primaryType", "nt:unstructured");
            genesisWallet.setProperty("wallet", GENESIS_ADDRESS);
            genesisWallet.setProperty("role", "genesis");
            genesisWallet.setProperty("walletCreated", timestamp);
            genesisWallet.setProperty("nodeType", "wallet-root");
            genesisWallet.setProperty("description", "Genesis wallet - Network documentation and bootstrap identity");
            genesisWallet.setProperty("contentCount", 1L);
            genesisWallet.setProperty("totalWrites", 1L);
            genesisWallet.setProperty("lastWrite", timestamp);
            genesisWallet.setProperty("owner", "OakChain Network");
            genesisWallet.setProperty("verified", true);

            NodeBuilder content = genesisWallet.child("content");
            content.setProperty("jcr:primaryType", "nt:unstructured");

            NodeBuilder genesis = content.child("genesis");
            genesis.setProperty("jcr:primaryType", "nt:unstructured");
            genesis.setProperty("jcr:created", timestamp);
            genesis.setProperty("jcr:title", "OakChain Network - Self-Documenting Genesis");
            genesis.setProperty("jcr:description", "This node is the living documentation for the OakChain network. "
                + "Read the child nodes to learn how to use this system.");
            genesis.setProperty("tagline", "Billions of enterprise content rides these rails. We're making it decentralized.");
            genesis.setProperty("ethos", "Trust the data, not the operator. Determinism first. Availability without ambiguity.");
            genesis.setProperty("northStar", "Make content verifiable, portable, and durable at global enterprise scale.");
            genesis.setProperty("version", "1.0.0");
            genesis.setProperty("chainId", "oak-blockchain-aem");
            genesis.setProperty("genesisTimestamp", timestamp);
            genesis.setProperty("genesisDate", genesisDate);
            genesis.setProperty("genesisValidator", genesisValidator);

            populateGettingStarted(genesis);
            populateApi(genesis);
            populateExamples(genesis);
            populateArchitecture(genesis);
            populateEconomics(genesis);
            populateTroubleshooting(genesis);
            populateAbout(genesis);
            String ipfsCid = populateGenesisImage(genesis, timestamp);

            NodeBuilder ipfsInfo = genesis.child("ipfs");
            ipfsInfo.setProperty("jcr:primaryType", "nt:unstructured");
            ipfsInfo.setProperty("enabled", blobStore != null);
            ipfsInfo.setProperty("genesisImageCid", ipfsCid != null ? ipfsCid : "N/A (BlobStore fallback)");
            ipfsInfo.setProperty("gateway", "https://ipfs.io/ipfs/");
            ipfsInfo.setProperty("description", "Binaries stored via IPFS - content-addressed, decentralized, immutable");

            nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY);
            String newHead = fileStore.getHead().getRecordId().toString10();

            log.info("Genesis committed deterministically - validator={}, timestamp={}, head={}",
                genesisValidator, timestamp, newHead);
        } catch (Exception e) {
            log.error("Exception during genesis creation", e);
        }
    }

    private void populateGettingStarted(NodeBuilder genesis) {
        NodeBuilder gettingStarted = genesis.child("getting-started");
        gettingStarted.setProperty("jcr:primaryType", "nt:unstructured");
        gettingStarted.setProperty("jcr:title", "Getting Started with OakChain");
        gettingStarted.setProperty("jcr:description", "Everything you need to write your first content to the blockchain");

        NodeBuilder prereqs = gettingStarted.child("1-prerequisites");
        prereqs.setProperty("jcr:primaryType", "nt:unstructured");
        prereqs.setProperty("title", "Prerequisites");
        prereqs.setProperty("item-1", "An Ethereum wallet (MetaMask recommended)");
        prereqs.setProperty("item-2", "Some ETH for transaction fees (Sepolia testnet for testing)");
        prereqs.setProperty("item-3", "curl or any HTTP client");
        prereqs.setProperty("note", "No SDK required - it's just HTTP + signatures");

        NodeBuilder connect = gettingStarted.child("2-connect");
        connect.setProperty("jcr:primaryType", "nt:unstructured");
        connect.setProperty("title", "Connect to a Validator");
        connect.setProperty("description", "Find a validator endpoint and check its health");
        connect.setProperty("curl-health", "curl http://VALIDATOR:8090/health");
        connect.setProperty("curl-status", "curl http://VALIDATOR:8090/v1/status");
        connect.setProperty("response-healthy", "{\"status\":\"healthy\",\"role\":\"LEADER\"|\"FOLLOWER\"}");

        NodeBuilder writeContent = gettingStarted.child("3-write-content");
        writeContent.setProperty("jcr:primaryType", "nt:unstructured");
        writeContent.setProperty("title", "Write Your First Content");
        writeContent.setProperty("step-1", "Sign a message with your wallet: 'OakChain Write: {path} at {timestamp}'");
        writeContent.setProperty("step-2", "POST to /v1/propose-write with your wallet, signature, path, and content");
        writeContent.setProperty("step-3", "Wait for finality (check /v1/proposal-status/{id})");
        writeContent.setProperty("step-4", "Your content is now on the blockchain!");
        writeContent.setProperty("curl-example", "curl -X POST http://VALIDATOR:8090/v1/propose-write "
            + "-d 'walletAddress=0xYOUR_WALLET' "
            + "-d 'signature=0xYOUR_SIG' "
            + "-d 'path=/my-content' "
            + "-d 'content={\"title\":\"Hello OakChain\"}'");

        NodeBuilder readContent = gettingStarted.child("4-read-content");
        readContent.setProperty("jcr:primaryType", "nt:unstructured");
        readContent.setProperty("title", "Read Content");
        readContent.setProperty("description", "Reading is free and doesn't require a wallet");
        readContent.setProperty("curl-read", "curl http://VALIDATOR:8090/v1/content/0xWALLET/path/to/content");
        readContent.setProperty("curl-list", "curl http://VALIDATOR:8090/v1/content/0xWALLET");
        readContent.setProperty("note", "Content is available from any validator - they all have the same state");
    }

    private void populateApi(NodeBuilder genesis) {
        NodeBuilder api = genesis.child("api");
        api.setProperty("jcr:primaryType", "nt:unstructured");
        api.setProperty("jcr:title", "API Reference");
        api.setProperty("jcr:description", "Complete HTTP API for interacting with OakChain validators");
        api.setProperty("baseUrl", "http://VALIDATOR:8090");
        api.setProperty("contentType", "application/x-www-form-urlencoded or application/json");

        NodeBuilder apiHealth = api.child("health-status");
        apiHealth.setProperty("jcr:primaryType", "nt:unstructured");
        apiHealth.setProperty("GET_health", "Health check - returns {status, role, epoch}");
        apiHealth.setProperty("GET_v1_status", "Detailed status - cluster info, HEAD, validators");
        apiHealth.setProperty("GET_v1_cluster_info", "Cluster membership and leader info");
        apiHealth.setProperty("GET_root", "Dashboard UI (HTML)");

        NodeBuilder apiContent = api.child("content-operations");
        apiContent.setProperty("jcr:primaryType", "nt:unstructured");
        apiContent.setProperty("POST_v1_propose_write", "Propose a write - requires wallet, signature, path, content");
        apiContent.setProperty("POST_v1_propose_delete", "Propose a delete - requires wallet, signature, path");
        apiContent.setProperty("GET_v1_content_wallet_path", "Read content at path");
        apiContent.setProperty("GET_v1_content_wallet", "List content for wallet");
        apiContent.setProperty("GET_v1_proposal_status_id", "Check proposal status");

        NodeBuilder apiBinary = api.child("binary-operations");
        apiBinary.setProperty("jcr:primaryType", "nt:unstructured");
        apiBinary.setProperty("POST_v1_binary_declare_intent", "Declare intent to upload binary - returns intentToken");
        apiBinary.setProperty("GET_v1_binary_check_intent_token", "Check upload status");
        apiBinary.setProperty("POST_v1_binary_complete_upload", "Complete upload with IPFS CID");
        apiBinary.setProperty("note", "Binaries are stored on IPFS, only CIDs are stored on-chain");

        NodeBuilder apiSegments = api.child("segment-transfer");
        apiSegments.setProperty("jcr:primaryType", "nt:unstructured");
        apiSegments.setProperty("GET_journal_log", "Journal file for segment sync");
        apiSegments.setProperty("GET_manifest", "Segment manifest");
        apiSegments.setProperty("GET_segments_id", "Fetch specific segment by ID");
        apiSegments.setProperty("note", "Used by Sling authors for read-only replication");
    }

    private void populateExamples(NodeBuilder genesis) {
        NodeBuilder examples = genesis.child("examples");
        examples.setProperty("jcr:primaryType", "nt:unstructured");
        examples.setProperty("jcr:title", "Working Examples");
        examples.setProperty("jcr:description", "Copy-paste examples for common operations");

        NodeBuilder jsExample = examples.child("javascript-browser");
        jsExample.setProperty("jcr:primaryType", "nt:unstructured");
        jsExample.setProperty("title", "JavaScript (Browser with MetaMask)");
        jsExample.setProperty("code",
            "// 1. Connect wallet\\n"
                + "const accounts = await ethereum.request({ method: 'eth_requestAccounts' });\\n"
                + "const wallet = accounts[0];\\n\\n"
                + "// 2. Sign message\\n"
                + "const path = '/my-page';\\n"
                + "const timestamp = Date.now();\\n"
                + "const message = `OakChain Write: ${path} at ${timestamp}`;\\n"
                + "const signature = await ethereum.request({\\n"
                + "  method: 'personal_sign',\\n"
                + "  params: [message, wallet]\\n"
                + "});\\n\\n"
                + "// 3. Submit write\\n"
                + "const response = await fetch('http://validator:8090/v1/propose-write', {\\n"
                + "  method: 'POST',\\n"
                + "  headers: { 'Content-Type': 'application/json' },\\n"
                + "  body: JSON.stringify({\\n"
                + "    walletAddress: wallet,\\n"
                + "    signature: signature,\\n"
                + "    path: path,\\n"
                + "    content: { title: 'Hello OakChain', body: 'My first content' }\\n"
                + "  })\\n"
                + "});");

        NodeBuilder curlExample = examples.child("curl");
        curlExample.setProperty("jcr:primaryType", "nt:unstructured");
        curlExample.setProperty("title", "curl Commands");
        curlExample.setProperty("check-health", "curl http://localhost:8090/health");
        curlExample.setProperty("get-status", "curl http://localhost:8090/v1/status | jq .");
        curlExample.setProperty("read-genesis", "curl http://localhost:8090/v1/content/0x0000000000000000000000000000000000000000/genesis");
        curlExample.setProperty("list-content", "curl http://localhost:8090/v1/content/0xYOUR_WALLET");
    }

    private void populateArchitecture(NodeBuilder genesis) {
        NodeBuilder architecture = genesis.child("architecture");
        architecture.setProperty("jcr:primaryType", "nt:unstructured");
        architecture.setProperty("jcr:title", "System Architecture");
        architecture.setProperty("jcr:description", "How OakChain works under the hood");

        NodeBuilder components = architecture.child("components");
        components.setProperty("jcr:primaryType", "nt:unstructured");
        components.setProperty("oak-segment-store", "Apache Oak TarMK - proven content storage from Adobe AEM");
        components.setProperty("aeron-cluster", "High-performance Raft consensus (io.aeron.cluster)");
        components.setProperty("ethereum", "External time oracle + payment verification");
        components.setProperty("ipfs", "Content-addressed binary storage");
        components.setProperty("http-api", "RESTful interface for all operations");

        NodeBuilder paths = architecture.child("wallet-scoped-paths");
        paths.setProperty("jcr:primaryType", "nt:unstructured");
        paths.setProperty("pattern", "/oak-chain/{shard-level-1}/{shard-level-2}/{shard-level-3}/{wallet}/content/{path}");
        paths.setProperty("example", "/oak-chain/74/2d/35/0x742d35Cc6634C0532925a3b844Bc9e7595f1b3E8/content/my-page");
        paths.setProperty("sharding", "First 3 bytes of wallet address create 3-level directory structure");
        paths.setProperty("benefit", "Segment isolation - each wallet's content is naturally partitioned");

        NodeBuilder consensus = architecture.child("consensus");
        consensus.setProperty("jcr:primaryType", "nt:unstructured");
        consensus.setProperty("algorithm", "Raft (via Aeron Cluster)");
        consensus.setProperty("quorum", "Majority of validators must agree");
        consensus.setProperty("leader-election", "Automatic - leader handles all writes");
        consensus.setProperty("replication", "All writes replicated to all validators synchronously");
        consensus.setProperty("finality", "Immediate local finality with adaptive verified-release scheduling; Ethereum beacon state remains a compatibility and confirmation signal");
    }

    private void populateEconomics(NodeBuilder genesis) {
        NodeBuilder economics = genesis.child("economics");
        economics.setProperty("jcr:primaryType", "nt:unstructured");
        economics.setProperty("jcr:title", "Transaction Pricing");
        economics.setProperty("jcr:description", "How much operations cost and why");
        economics.setProperty("philosophy", "Fragmentation costs more - incentivize batching for efficiency");

        NodeBuilder pricing = economics.child("pricing-tiers");
        pricing.setProperty("jcr:primaryType", "nt:unstructured");
        pricing.setProperty("priority-price", "0.01 ETH");
        pricing.setProperty("priority-release", "Compatibility price class; may use direct release if enabled");
        pricing.setProperty("priority-use-case", "Premium routing / explicit entitlements");
        pricing.setProperty("express-price", "0.002 ETH");
        pricing.setProperty("express-release", "Adaptive release with no fixed epoch wait");
        pricing.setProperty("express-use-case", "Compatibility price class for time-sensitive updates");
        pricing.setProperty("standard-price", "0.001 ETH");
        pricing.setProperty("standard-release", "Adaptive release with no fixed epoch wait");
        pricing.setProperty("standard-use-case", "Default economic class for bulk content and scheduled updates");

        NodeBuilder paymentFlow = economics.child("payment-flow");
        paymentFlow.setProperty("jcr:primaryType", "nt:unstructured");
        paymentFlow.setProperty("step-1", "User signs write proposal with wallet");
        paymentFlow.setProperty("step-2", "Validator verifies signature and stages proposal in the adaptive packing buffer");
        paymentFlow.setProperty("step-3", "Release governor sends work immediately when Aeron is healthy or buffers under pressure");
        paymentFlow.setProperty("step-4", "Payment verified on Ethereum (ValidatorPayment contract)");
        paymentFlow.setProperty("step-5", "Content becomes permanent");
    }

    private void populateTroubleshooting(NodeBuilder genesis) {
        NodeBuilder troubleshooting = genesis.child("troubleshooting");
        troubleshooting.setProperty("jcr:primaryType", "nt:unstructured");
        troubleshooting.setProperty("jcr:title", "Troubleshooting Guide");
        troubleshooting.setProperty("jcr:description", "Common issues and how to fix them");

        NodeBuilder issues = troubleshooting.child("common-issues");
        issues.setProperty("jcr:primaryType", "nt:unstructured");
        issues.setProperty("issue-signature-invalid", "SIGNATURE_INVALID: Ensure you're signing the exact message format 'OakChain Write: {path} at {timestamp}'");
        issues.setProperty("issue-not-leader", "NOT_LEADER: You hit a follower - retry or use the leader URL from /v1/cluster-info");
        issues.setProperty("issue-path-forbidden", "PATH_FORBIDDEN: You can only write to paths under your wallet address");
        issues.setProperty("issue-epoch-stale", "EPOCH_STALE: Your timestamp is too old - use current time");
        issues.setProperty("issue-payment-required", "PAYMENT_REQUIRED: Transaction needs payment - check ValidatorPayment contract");

        NodeBuilder healthChecks = troubleshooting.child("health-checks");
        healthChecks.setProperty("jcr:primaryType", "nt:unstructured");
        healthChecks.setProperty("check-1", "curl /health - should return {status: healthy}");
        healthChecks.setProperty("check-2", "curl /v1/status - check role is LEADER or FOLLOWER (not CANDIDATE)");
        healthChecks.setProperty("check-3", "curl /v1/cluster-info - verify all validators are connected");
        healthChecks.setProperty("check-4", "Check logs for '❌' emoji - indicates errors");
    }

    private void populateAbout(NodeBuilder genesis) {
        NodeBuilder about = genesis.child("about");
        about.setProperty("jcr:primaryType", "nt:unstructured");
        about.setProperty("jcr:title", "About OakChain");
        about.setProperty("mission", "Decentralizing enterprise content management");
        about.setProperty("foundation", "Built on Apache Oak - the proven content repository behind Adobe AEM");
        about.setProperty("value-proposition", "Billions of dollars of enterprise content already runs on Oak. "
            + "We're adding decentralization, cryptographic ownership, and blockchain finality.");
        about.setProperty("philosophy", "Bitcoin-tight reliability meets enterprise content management");
        about.setProperty("principles", "Fail Loud, Fail Fast, Never Silently Corrupt");
        about.setProperty("team", "somarc + AI collaborators - distributed intelligence building distributed systems");
        about.setProperty("license", "Apache 2.0");

        NodeBuilder thesis = genesis.child("thesis");
        thesis.setProperty("jcr:primaryType", "nt:unstructured");
        thesis.setProperty("jcr:title", "The Thesis");
        thesis.setProperty("premise-1", "Enterprise content is the most valuable data no one can prove.");
        thesis.setProperty("premise-2", "Audit trails should be data, not policy.");
        thesis.setProperty("premise-3", "Determinism is the only safe way to scale trust.");
        thesis.setProperty("result", "OakChain makes content provable, portable, and economically secure.");
        thesis.setProperty("audience", "Builders who want boring reliability and bold guarantees.");

        NodeBuilder boldBets = genesis.child("bold-bets");
        boldBets.setProperty("jcr:primaryType", "nt:unstructured");
        boldBets.setProperty("bet-1", "Every serious enterprise will demand verifiable content history.");
        boldBets.setProperty("bet-2", "AEM-scale systems can be decentralized without losing performance.");
        boldBets.setProperty("bet-3", "Proof of custody will become the default compliance standard.");
        boldBets.setProperty("bet-4", "Developers will choose APIs over platforms if guarantees are stronger.");
        boldBets.setProperty("bet-5", "Developers will choose systems with stronger guarantees over familiar platforms.");

        NodeBuilder guarantees = genesis.child("guarantees");
        guarantees.setProperty("jcr:primaryType", "nt:unstructured");
        guarantees.setProperty("determinism", "Same inputs, same state on every validator.");
        guarantees.setProperty("auditability", "Every change is traceable by ID, time, and signer.");
        guarantees.setProperty("durability", "Committed content survives validator loss.");
        guarantees.setProperty("portability", "Content is readable without privileged infrastructure.");
        guarantees.setProperty("integrity", "Signatures bind authorship to the data path.");

        NodeBuilder nonGoals = genesis.child("non-goals");
        nonGoals.setProperty("jcr:primaryType", "nt:unstructured");
        nonGoals.setProperty("non-goal-1", "We do not chase maximal throughput at the expense of determinism.");
        nonGoals.setProperty("non-goal-2", "We do not require custodial identity or closed networks.");
        nonGoals.setProperty("non-goal-3", "We do not hide failures; we surface them early and loudly.");

        NodeBuilder oath = genesis.child("operator-oath");
        oath.setProperty("jcr:primaryType", "nt:unstructured");
        oath.setProperty("oath-1", "Run the node as if the audit depends on you.");
        oath.setProperty("oath-2", "Do not change history. Fix the system.");
        oath.setProperty("oath-3", "Measure everything; guess nothing.");
        oath.setProperty("oath-4", "If it fails, document the failure in the chain.");
    }

    private String populateGenesisImage(NodeBuilder genesis, long timestamp) {
        NodeBuilder genesisImage = genesis.child("do-it-live.jpeg");
        genesisImage.setProperty("jcr:primaryType", "nt:file");
        genesisImage.setProperty("jcr:created", timestamp);

        NodeBuilder imageContent = genesisImage.child("jcr:content");
        imageContent.setProperty("jcr:primaryType", "nt:resource");
        imageContent.setProperty("jcr:mimeType", "image/jpeg");
        imageContent.setProperty("jcr:lastModified", timestamp);

        try {
            byte[] imageBytes = loadGenesisImageBytes();
            if (imageBytes == null) {
                storePlaceholderImage(imageContent, "DO IT LIVE! (image placeholder)");
                return null;
            }

            imageContent.setProperty("size", (long) imageBytes.length);
            if (blobStore != null) {
                String blobId = blobStore.writeBlob(new ByteArrayInputStream(imageBytes));
                imageContent.setProperty("jcr:data", new BlobStoreBlob(blobStore, blobId));
                imageContent.setProperty("jcr:blobId", blobId);
                if (blobId != null && (blobId.startsWith("Qm") || blobId.startsWith("baf"))) {
                    return blobId.split("#")[0];
                }
                return blobId;
            }

            Blob blob = nodeStore.createBlob(new ByteArrayInputStream(imageBytes));
            imageContent.setProperty("jcr:data", blob);
            return null;
        } catch (Exception e) {
            log.warn("Failed to store genesis image: {}", e.getMessage());
            try {
                storePlaceholderImage(imageContent, "DO IT LIVE! (image error: " + e.getMessage() + ")");
            } catch (Exception blobError) {
                log.warn("Failed to store genesis placeholder image: {}", blobError.getMessage());
            }
            return null;
        }
    }

    private void storePlaceholderImage(NodeBuilder imageContent, String placeholder) throws Exception {
        byte[] placeholderBytes = placeholder.getBytes(StandardCharsets.UTF_8);
        imageContent.setProperty("jcr:mimeType", "text/plain");
        imageContent.setProperty("size", (long) placeholderBytes.length);
        imageContent.setProperty("jcr:data", nodeStore.createBlob(new ByteArrayInputStream(placeholderBytes)));
    }

    private byte[] loadGenesisImageBytes() throws Exception {
        try (InputStream imageStream = getClass().getClassLoader().getResourceAsStream(GENESIS_IMAGE_RESOURCE)) {
            if (imageStream == null) {
                log.warn("Genesis image resource not found: {}", GENESIS_IMAGE_RESOURCE);
                return null;
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = imageStream.read(buffer)) != -1) {
                baos.write(buffer, 0, read);
            }
            return baos.toByteArray();
        }
    }

    private NodeState getGenesisNode(NodeState root) {
        return root.getChildNode("oak-chain")
            .getChildNode("00")
            .getChildNode("00")
            .getChildNode("00")
            .getChildNode(GENESIS_ADDRESS)
            .getChildNode("content")
            .getChildNode("genesis");
    }

    static final class GenesisProposal {
        private final long timestamp;
        private final String genesisValidatorUrl;

        private GenesisProposal(long timestamp, String genesisValidatorUrl) {
            this.timestamp = timestamp;
            this.genesisValidatorUrl = genesisValidatorUrl;
        }

        static GenesisProposal create(long timestamp, String genesisValidatorUrl) {
            long normalizedTimestamp = timestamp > 0 ? timestamp : 0L;
            String normalizedValidator = genesisValidatorUrl != null && !genesisValidatorUrl.trim().isEmpty()
                ? genesisValidatorUrl.trim()
                : DEFAULT_GENESIS_VALIDATOR_URL;
            return new GenesisProposal(normalizedTimestamp, normalizedValidator);
        }

        static GenesisProposal fromJson(String json) {
            String source = json != null ? json.trim() : "";
            long timestamp = extractLongField(source, "timestamp");
            String genesisValidator = extractJsonField(source, "genesisValidator");
            return create(timestamp, genesisValidator);
        }

        long getTimestamp() {
            return timestamp;
        }

        String getGenesisValidatorUrl() {
            return genesisValidatorUrl;
        }

        String toJson() {
            return "{\"command\":\"CREATE_GENESIS\",\"timestamp\":" + timestamp
                + ",\"genesisValidator\":\"" + escapeJson(genesisValidatorUrl) + "\"}";
        }

        private static long extractLongField(String json, String field) {
            String marker = "\"" + field + "\":";
            int start = json.indexOf(marker);
            if (start < 0) {
                return 0L;
            }
            int valueStart = start + marker.length();
            int valueEnd = valueStart;
            while (valueEnd < json.length() && Character.isWhitespace(json.charAt(valueEnd))) {
                valueEnd++;
            }
            valueStart = valueEnd;
            while (valueEnd < json.length() && (Character.isDigit(json.charAt(valueEnd)) || json.charAt(valueEnd) == '-')) {
                valueEnd++;
            }
            if (valueStart >= valueEnd) {
                return 0L;
            }
            try {
                return Long.parseLong(json.substring(valueStart, valueEnd));
            } catch (NumberFormatException e) {
                return 0L;
            }
        }

        private static String extractJsonField(String json, String field) {
            String pattern = "\"" + field + "\":\"";
            int start = json.indexOf(pattern);
            if (start < 0) {
                return null;
            }
            start += pattern.length();
            int end = json.indexOf('"', start);
            return end >= 0 ? json.substring(start, end).replace("\\\"", "\"").replace("\\\\", "\\") : null;
        }

        private static String escapeJson(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"");
        }
    }
}
