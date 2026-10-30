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
package org.apache.jackrabbit.oak.segment.consensus.genesis;

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.plugins.memory.ArrayBasedBlob;
import org.apache.jackrabbit.oak.plugins.memory.EmptyNodeState;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Canonical authoring surface for OakChain genesis content.
 *
 * <p>This class is the single source of truth for:
 * <ul>
 *   <li>the canonical wallet-scoped genesis path</li>
 *   <li>the authored bootstrap content published under genesis</li>
 *   <li>the minimal integrity markers used by startup verification</li>
 * </ul>
 */
public final class CanonicalGenesisContent {

    public static final String GENESIS_ADDRESS = "0x0000000000000000000000000000000000000000";
    public static final String DEFAULT_GENESIS_VALIDATOR_URL = "http://localhost:8090";

    private static final String GENESIS_IMAGE_RESOURCE = "genesis-assets/do-it-live.jpeg";
    private static final String GENESIS_MESSAGE = "DO IT LIVE!";
    private static final String CHAIN_ID = "oak-blockchain-aem";
    private static final String VERSION = "2.1.0";
    private static final String IMAGE_SHA256 = "ed06f9cbf0fe878013ccb266170e6b3ba676933a6f065675cc0115c840bf1442";
    private static final String GENESIS_WALLET_PATH = WalletPathUtil.getShardRoot(GENESIS_ADDRESS);
    private static final String CANONICAL_GENESIS_PATH = WalletPathUtil.getContentPath(GENESIS_ADDRESS) + "/genesis";
    private static final String SHAPES_PATH = CANONICAL_GENESIS_PATH + "/shapes";

    // Illustrative wallets for the shape examples: a brand and the counsel that reviews its claims.
    private static final String EXAMPLE_BRAND_CONTENT =
        WalletPathUtil.getContentPath("0x742d35cc6634c0532925a3b844bc9e7595f1b3e8", "acme");
    private static final String EXAMPLE_COUNSEL_CONTENT =
        WalletPathUtil.getContentPath("0x9b0e41c7d2a85f3e6b1940ad27c58e03f6d1b24a", "counsel");
    private static final String EXAMPLE_CLAIM_PATH = EXAMPLE_BRAND_CONTENT + "/claims/recycled-bottle";
    private static final String EXAMPLE_ASSET_PATH = EXAMPLE_BRAND_CONTENT + "/assets/bottle-hero";
    private static final String EXAMPLE_REPORT_PATH = EXAMPLE_BRAND_CONTENT + "/assets/lca-report-2025";

    private final NodeStore nodeStore;

    public CanonicalGenesisContent(NodeStore nodeStore, BlobStore blobStore) {
        this.nodeStore = nodeStore;
    }

    public static String getGenesisPath() {
        return CANONICAL_GENESIS_PATH;
    }

    public static NodeState getGenesisNode(NodeState root) {
        String[] levels = WalletPathUtil.getShardLevels(GENESIS_ADDRESS);
        return root.getChildNode("oak-chain")
            .getChildNode(levels[0])
            .getChildNode(levels[1])
            .getChildNode(levels[2])
            .getChildNode(GENESIS_ADDRESS)
            .getChildNode("content")
            .getChildNode("genesis");
    }

    public boolean exists() {
        return getGenesisNode(nodeStore.getRoot()).exists();
    }

    public static boolean isReservedMutation(String wallet, String path) {
        if (wallet != null && GENESIS_ADDRESS.equalsIgnoreCase(wallet.trim())) {
            return true;
        }
        if (path == null) {
            return false;
        }
        StringBuilder normalized = new StringBuilder();
        for (String part : path.split("/")) {
            if (!part.isEmpty()) {
                normalized.append('/').append(part.toLowerCase(Locale.ROOT));
            }
        }
        String target = normalized.toString();
        return GENESIS_WALLET_PATH.equals(target) || target.startsWith(GENESIS_WALLET_PATH + "/")
            || GENESIS_WALLET_PATH.startsWith(target + "/");
    }

    public static void requireMutable(String wallet, String path) {
        if (isReservedMutation(wallet, path)) {
            throw new MutationRejectedException("GENESIS_NAMESPACE_RESERVED: ordinary mutations cannot change genesis");
        }
    }

    public void verifyExisting() {
        NodeState root = nodeStore.getRoot();
        NodeState genesis = getGenesisNode(root);
        if (!genesis.exists()) {
            throw new IllegalStateException("Canonical genesis is missing at " + CANONICAL_GENESIS_PATH);
        }
        verifyString(genesis, "message", GENESIS_MESSAGE);
        verifyString(genesis, "chainId", CHAIN_ID);
        if (!genesis.getChildNode("content-contract").exists()) {
            throw new IllegalStateException("Canonical genesis is missing content-contract guidance");
        }
        verifyString(genesis, "version", VERSION);
        verifyString(genesis, "integrityFormat", GenesisDigest.FORMAT);
        verifyString(genesis, "integrityAlgorithm", "SHA-256");
        if (!genesis.hasProperty("genesisTimestamp") || genesis.getProperty("genesisTimestamp").getType() != Type.LONG
            || !genesis.hasProperty("genesisValidator") || genesis.getProperty("genesisValidator").getType() != Type.STRING) {
            throw new IllegalStateException("Invalid canonical genesis proposal metadata");
        }
        try {
            NodeBuilder expected = EmptyNodeState.EMPTY_NODE.builder();
            author(expected, genesis.getProperty("genesisTimestamp").getValue(Type.LONG),
                genesis.getProperty("genesisValidator").getValue(Type.STRING), new ArrayBasedBlob(loadGenesisImageBytes()));
            String expectedDigest = GenesisDigest.of(genesisWallet(expected.getNodeState()));
            String actualDigest = GenesisDigest.of(genesisWallet(root));
            verifyString(genesis, GenesisDigest.PROPERTY, expectedDigest);
            if (!expectedDigest.equals(actualDigest)) {
                throw new IllegalStateException("Canonical genesis content does not match the code-authored contract");
            }
            NodeState ancestor = root;
            for (String part : new String[] {"oak-chain", "00", "00", "00"}) {
                ancestor = ancestor.getChildNode(part);
                if (!ancestor.hasProperty("jcr:primaryType")
                    || ancestor.getProperty("jcr:primaryType").getType() != Type.NAME
                    || !"nt:unstructured".equals(ancestor.getProperty("jcr:primaryType").getValue(Type.NAME))) {
                    throw new IllegalStateException("Invalid canonical genesis ancestor: " + part);
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot verify canonical genesis content", e);
        }
    }

    public void populate(NodeBuilder rootBuilder, long timestamp, String genesisValidatorUrl) throws Exception {
        byte[] imageBytes = loadGenesisImageBytes();
        Blob image = nodeStore.createBlob(new ByteArrayInputStream(imageBytes));
        author(rootBuilder, timestamp, genesisValidatorUrl, image);
        NodeBuilder genesis = rootBuilder;
        for (String part : CANONICAL_GENESIS_PATH.substring(1).split("/")) {
            genesis = genesis.getChildNode(part);
        }
        genesis.setProperty(GenesisDigest.PROPERTY, GenesisDigest.of(genesisWallet(rootBuilder.getNodeState())));
    }

    private static NodeState genesisWallet(NodeState root) {
        NodeState wallet = root;
        for (String part : GENESIS_WALLET_PATH.substring(1).split("/")) {
            wallet = wallet.getChildNode(part);
        }
        return wallet;
    }

    private void author(NodeBuilder rootBuilder, long timestamp, String genesisValidatorUrl, Blob image) {
        long normalizedTimestamp = Math.max(0L, timestamp);
        String genesisDate = Instant.ofEpochMilli(normalizedTimestamp).toString();
        String genesisValidator = normalizeGenesisValidatorUrl(genesisValidatorUrl);
        String genesisHost = extractHost(genesisValidator);

        NodeBuilder oakChain = rootBuilder.child("oak-chain");
        oakChain.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);

        String[] levels = WalletPathUtil.getShardLevels(GENESIS_ADDRESS);

        NodeBuilder level1 = oakChain.child(levels[0]);
        level1.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);

        NodeBuilder level2 = level1.child(levels[1]);
        level2.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);

        NodeBuilder level3 = level2.child(levels[2]);
        level3.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);

        NodeBuilder genesisWallet = level3.child(GENESIS_ADDRESS);
        genesisWallet.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        genesisWallet.setProperty("wallet", GENESIS_ADDRESS);
        genesisWallet.setProperty("role", "genesis");
        genesisWallet.setProperty("walletCreated", normalizedTimestamp);
        genesisWallet.setProperty("nodeType", "wallet-root");
        genesisWallet.setProperty("description", "Genesis wallet - canonical bootstrap knowledge capsule for the OakChain fabric");
        genesisWallet.setProperty("contentCount", 1L);
        genesisWallet.setProperty("totalWrites", 1L);
        genesisWallet.setProperty("lastWrite", normalizedTimestamp);
        genesisWallet.setProperty("owner", "OakChain Network");
        genesisWallet.setProperty("verified", true);

        NodeBuilder content = genesisWallet.child("content");
        content.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);

        NodeBuilder genesis = content.child("genesis");
        genesis.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        genesis.setProperty("jcr:created", genesisDate, Type.DATE);
        genesis.setProperty("jcr:title", "Oak Chain - Shared Content, Verifiable Commitment");
        genesis.setProperty("jcr:description",
            "Oak Chain turns enterprise content into shared, verifiable state. Aeron orders commands; Oak stores their meaning. "
                + "Every validator must produce the same typed logical state. Acceptance is not commitment: commitment requires "
                + "a verified durable quorum. Genesis is reserved, versioned, and verified; failures must remain explicit rather "
                + "than being reported as success.");
        genesis.setProperty("message", GENESIS_MESSAGE);
        genesis.setProperty("tagline", "Billions of enterprise content rides these rails. We're making it decentralized.");
        genesis.setProperty("ethos", "Trust the data, not the operator. Determinism first. Availability without ambiguity.");
        genesis.setProperty("northStar", "Make content verifiable, portable, and durable at global enterprise scale.");
        genesis.setProperty("version", VERSION);
        genesis.setProperty("integrityFormat", GenesisDigest.FORMAT);
        genesis.setProperty("integrityAlgorithm", "SHA-256");
        genesis.setProperty("chainId", CHAIN_ID);
        genesis.setProperty("consensusModel", "aeron-raft");
        genesis.setProperty("genesisTimestamp", normalizedTimestamp);
        genesis.setProperty("genesisDate", genesisDate);
        genesis.setProperty("genesisValidator", genesisValidator);
        genesis.setProperty("genesisValidatorRole", "Lexicographically first configured HTTP endpoint, not an Aeron member-ID or ingress-caller claim.");
        genesis.setProperty("canonicalGenesisPath", CANONICAL_GENESIS_PATH);
        genesis.setProperty("walletNamespaceContract", "The wallet namespace boundary is the only enforced content contract today.");
        genesis.setProperty("contentShapePolicy", "Records below /content follow the shapes published at " + SHAPES_PATH
            + ". Pages stay at the edge and cite records.");
        genesis.setProperty("startHere", "Read protocol, content-contract, shapes, architecture, api, and getting-started before writing clients.");
        genesis.setProperty("governedSurface", "/v1/index");

        populateProtocol(genesis, normalizedTimestamp, genesisDate, genesisValidator, genesisHost);
        populateContentContract(genesis);
        populateShapes(genesis);
        populateGettingStarted(genesis, genesisHost);
        populateApi(genesis);
        populateExamples(genesis);
        populateArchitecture(genesis);
        populateEconomics(genesis);
        populateTroubleshooting(genesis);
        populateAbout(genesis);

        populateGenesisImage(genesis, genesisDate, image);

        NodeBuilder ipfsInfo = genesis.child("ipfs");
        ipfsInfo.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        ipfsInfo.setProperty("genesisImageSha256", IMAGE_SHA256);
        ipfsInfo.setProperty("description", "Binary identity is its content hash; storage IDs and retrieval gateways are local diagnostics.");
        ipfsInfo.setProperty("availability", "Independent binary availability requires separate operational evidence.");
    }

    private void populateProtocol(NodeBuilder genesis, long timestamp, String genesisDate, String genesisValidator, String genesisHost) {
        NodeBuilder protocol = genesis.child("protocol");
        protocol.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        protocol.setProperty("message", GENESIS_MESSAGE);
        protocol.setProperty("version", VERSION);
        protocol.setProperty("chainId", CHAIN_ID);
        protocol.setProperty("consensusModel", "aeron-raft");
        protocol.setProperty("genesisTimestamp", timestamp);
        protocol.setProperty("genesisDate", genesisDate);
        protocol.setProperty("genesisValidator", genesisValidator);
        protocol.setProperty("genesisHost", genesisHost);
        protocol.setProperty("canonicalGenesisPath", CANONICAL_GENESIS_PATH);
        protocol.setProperty("sourceOfTruth", "This node and /v1/index are the canonical bootstrap references for OakChain.");
        protocol.setProperty("description",
            "Genesis is a code-authored briefing for the network fabric. Update the authoring code, not live instances.");
    }

    private void populateContentContract(NodeBuilder genesis) {
        NodeBuilder contract = genesis.child("content-contract");
        contract.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        contract.setProperty("jcr:title", "Wallet Namespace Contract");
        contract.setProperty("enforcedBoundary",
            "Only the wallet namespace /oak-chain/{L1}/{L2}/{L3}/{wallet} is structurally enforced.");
        contract.setProperty("contentRootPattern", "/oak-chain/{L1}/{L2}/{L3}/{wallet}/content");
        contract.setProperty("contentShapeStatus",
            "Below /content, records follow the shapes published at genesis/shapes. The validator enforces only the wallet namespace today.");
        contract.setProperty("guidance", "Shapes are published by code in genesis and change only with a new genesis version.");
        contract.setProperty("shapes", SHAPES_PATH);
        contract.setProperty("genesisRole", "Genesis is the starting node for cluster joiners, operators, and the future CLI.");
        contract.setProperty("genesisWallet", WalletPathUtil.getShardRoot(GENESIS_ADDRESS));
        contract.setProperty("genesisReservation", "The zero-wallet subtree and its ancestors cannot be changed by ordinary mutations.");
    }

    /**
     * The content shapes, written as data. Each shape declares its fields and carries an
     * {@code example} child that is itself a well-formed record of that shape.
     */
    private void populateShapes(NodeBuilder genesis) {
        NodeBuilder shapes = node(genesis, "shapes");
        shapes.setProperty("jcr:title", "Content Shapes");
        shapes.setProperty("jcr:description",
            "What wallets write below /content. Oak Chain holds records: small, typed statements that someone may have to prove later. "
                + "Pages, layout, and drafts stay at the edge; pages cite records.");
        shapes.setProperty("pathPattern", "/oak-chain/{L1}/{L2}/{L3}/{wallet}[/{organization}]/content/{collection}/{name}");
        shapes.setProperty("fieldTypes", "String, String[], Long, Boolean, Date (ISO-8601, UTC)");
        shapes.setProperty("howToRead",
            "Every shape lists its fields and carries an example that is itself a well-formed record. The example is the specification.");
        shapes.setProperty("test-1", "Will someone later ask who said this, when, and whether this is the exact version?");
        shapes.setProperty("test-2", "Does more than one party, system, or agent rely on it?");
        shapes.setProperty("test-3", "Is it referred to by identity rather than by where it sits on a page?");
        shapes.setProperty("test-4", "Is it small and structured, or the hash of something large?");
        shapes.setProperty("verdict", "Yes to these makes a record. Anything else belongs at the edge.");

        populateRecordContract(shapes);
        populateClaimShape(shapes);
        populateAttestationShape(shapes);
        populateAssetShape(shapes);
        populateReceiptShape(shapes);

        NodeBuilder notRecords = node(shapes, "not-records");
        notRecords.setProperty("jcr:title", "Not Records");
        notRecords.setProperty("pages", "Pages, navigation, layout, and styling belong to the edge. A page cites records; a receipt proves what it showed.");
        notRecords.setProperty("drafts", "Work in progress stays in authoring. Write a record when the organization is ready to stand behind it.");
        notRecords.setProperty("personalData", "Personal data stays off chain: erasing it would mean deleting the trail.");
        notRecords.setProperty("telemetry", "Analytics, events, and logs are high-volume and nobody signs them; they are not records.");

        NodeBuilder today = node(shapes, "today");
        today.setProperty("jcr:title", "What the Validator Does Today");
        today.setProperty("jcr:description",
            "The shapes bind clients and readers now. These are the places where the validator has not caught up.");
        today.setProperty("write",
            "Every write creates one flat node named {contentType}-{millis}-{proposalId prefix} directly under content. "
                + "Collections and client-chosen names are not accepted yet.");
        today.setProperty("fields",
            "Send fields in message as JSON {\"title\": ..., \"payload\": {...}}. The title is stored as oak:title and the payload "
                + "as the JSON string oak:payloadJson, not as typed properties.");
        today.setProperty("signature",
            "The write signature covers message only: it covers the fields but not contentType or organization. "
                + "A shaped write must be signed over wallet, path, contentType, and fields.");
        today.setProperty("validation", "contentType and fields are not validated. Clients validate before writing; readers validate before trusting.");
        today.setProperty("reads",
            "There is no content query, and /v1/wallets/content omits organization content. Follow /v1/events/stream?organizations=... "
                + "and build indexes outside the validator.");
    }

    private static void populateRecordContract(NodeBuilder shapes) {
        NodeBuilder record = node(shapes, "record");
        record.setProperty("jcr:title", "Every Record");
        record.setProperty("jcr:description", "Fields, names, references, and rules shared by every shape.");
        record.setProperty("immutable", "Records are never edited in place. A revision is a new record that names its predecessor in supersedes.");
        record.setProperty("withdrawal", "A record is withdrawn when its own wallet attests verdict=withdrawn about it.");
        record.setProperty("current",
            "A record is current when no record supersedes it and it has not been withdrawn. Readers derive this; nothing stores it.");
        record.setProperty("names", "Collections and names use lowercase letters, digits, and hyphens: ^[a-z0-9][a-z0-9-]{0,63}$");
        record.setProperty("references", "A reference is an absolute record path. Pin a version by also storing the oak:proposalId that wrote it.");
        record.setProperty("crossWallet", "Records may reference any wallet. Writes never leave the writer's own namespace.");
        record.setProperty("binaries", "Bytes are identified by SHA-256. Storage locations and gateway URLs are not identity.");
        record.setProperty("deletion",
            "Delete is hard removal and erases the trail. Reserve it for content that must not exist; withdraw everything else.");
        record.setProperty("reservedNames",
            List.of("wallet", "organization", "signature", "timestamp", "source", "message", "oak:proposalId"), Type.STRINGS);
        record.setProperty("reservedPrefixes", List.of("oak:", "jcr:"), Type.STRINGS);
        record.setProperty("reservedNote",
            "The validator stamps the reserved names on every write. Shape fields never reuse them or the reserved prefixes, except jcr:title.");

        NodeBuilder fields = node(record, "fields");
        field(fields, "contentType", "String", true, "The shape name, sent as the write's contentType.",
            List.of("claim", "attestation", "asset", "receipt"));
        field(fields, "shapeVersion", "Long", true, "Version of the shape the record follows. Currently 1.");
        field(fields, "jcr:title", "String", false, "Human label.");
        field(fields, "supersedes", "String", false, "Path of the record this one replaces.");
    }

    private static void populateClaimShape(NodeBuilder shapes) {
        NodeBuilder claim = shape(shapes, "claim", "Claim", "claims",
            "A statement an organization stands behind and may have to prove: product claims, regulated disclosures, "
                + "safety information, pricing terms, policy and AI-use statements.",
            EXAMPLE_CLAIM_PATH);
        claim.setProperty("edge", "Pages show the statement verbatim with its as-of date. A superseded, withdrawn, or expired claim never renders as current.");

        NodeBuilder fields = node(claim, "fields");
        field(fields, "statement", "String", true, "Exact wording, as published.");
        field(fields, "locale", "String", true, "BCP 47 language tag, for example en-US.");
        field(fields, "effectiveFrom", "Date", true, "When the statement starts to apply.");
        field(fields, "effectiveUntil", "Date", false, "When it stops applying.");
        field(fields, "jurisdictions", "String[]", false, "ISO 3166 codes where the statement is made.");
        field(fields, "about", "String", false, "What the statement is about: a product identifier, a URL, or a record path.");
        field(fields, "evidence", "String[]", false, "Paths of records or assets that support the statement.");

        NodeBuilder example = example(claim, "claim", "Recycled bottle");
        example.setProperty("statement", "The bottle is made from 100% recycled plastic, excluding the cap and label.");
        example.setProperty("locale", "en-US");
        example.setProperty("effectiveFrom", "2026-01-01T00:00:00.000Z", Type.DATE);
        example.setProperty("jurisdictions", List.of("US", "CA"), Type.STRINGS);
        example.setProperty("about", "sku:ACME-WB-750");
        example.setProperty("evidence", List.of(EXAMPLE_REPORT_PATH), Type.STRINGS);
    }

    private static void populateAttestationShape(NodeBuilder shapes) {
        NodeBuilder attestation = shape(shapes, "attestation", "Attestation", "attestations",
            "One wallet's verdict on a specific version of another record: legal approval, regulatory review, delivery, acceptance, "
                + "audit. It lives in the attester's namespace and points at its subject.",
            EXAMPLE_COUNSEL_CONTENT + "/attestations/acme-recycled-bottle");
        attestation.setProperty("pinning", "An attestation applies only while its subject still carries subjectProposalId.");

        NodeBuilder fields = node(attestation, "fields");
        field(fields, "subject", "String", true, "Path of the record attested.");
        field(fields, "subjectProposalId", "String", true, "oak:proposalId of the subject version being attested.");
        field(fields, "verdict", "String", true, "The decision. Only the subject's own wallet may withdraw.",
            List.of("approved", "rejected", "withdrawn"));
        field(fields, "role", "String", true, "Capacity of the attester, for example legal-review, regulatory-review, delivery, acceptance.");
        field(fields, "note", "String", false, "Reasoning or scope, in a sentence or two.");
        field(fields, "validUntil", "Date", false, "When the verdict lapses.");

        NodeBuilder example = example(attestation, "attestation", "Legal review: recycled bottle");
        example.setProperty("subject", EXAMPLE_CLAIM_PATH);
        example.setProperty("subjectProposalId", "0x5e4a1c0b9d8f7e6a5b4c3d2e1f0a9b8c7d6e5f4a3b2c1d0e9f8a7b6c5d4e3f2a");
        example.setProperty("verdict", "approved");
        example.setProperty("role", "legal-review");
        example.setProperty("note", "Substantiated by the 2025 life-cycle assessment.");
        example.setProperty("validUntil", "2026-12-31T23:59:59.000Z", Type.DATE);
    }

    private static void populateAssetShape(NodeBuilder shapes) {
        NodeBuilder asset = shape(shapes, "asset", "Asset", "assets",
            "Identity and usage rights for a binary. The edge delivers the bytes; the record says what they are and who may use them, "
                + "where, and until when.",
            EXAMPLE_ASSET_PATH);
        asset.setProperty("bytes",
            "A write may carry the bytes or an IPFS CID. The validator then also stamps jcr:data, jcr:mimeType, and ipfsCid; sha256 must match.");

        NodeBuilder fields = node(asset, "fields");
        field(fields, "sha256", "String", true, "SHA-256 of the bytes, lowercase hex.");
        field(fields, "mimeType", "String", true, "Media type, for example image/jpeg.");
        field(fields, "byteSize", "Long", true, "Size in bytes.");
        field(fields, "origin", "String", true, "How the bytes were made.",
            List.of("captured", "designed", "ai-generated", "ai-edited"));
        field(fields, "license", "String", true, "SPDX license identifier, or a LicenseRef- identifier the owner defines.");
        field(fields, "territories", "String[]", false, "ISO 3166 codes where use is allowed. Absent means unrestricted.");
        field(fields, "channels", "String[]", false, "Where use is allowed, for example web, social, print, broadcast.");
        field(fields, "usageUntil", "Date", false, "When the right to use ends.");
        field(fields, "credit", "String", false, "Attribution line, exactly as it must appear.");

        NodeBuilder example = example(asset, "asset", "Bottle hero image");
        example.setProperty("sha256", "c3a17be2904d5f6e18a3b7c9d0e1f2a3b4c5d6e7f8091a2b3c4d5e6f7a8b9c0d");
        example.setProperty("mimeType", "image/jpeg");
        example.setProperty("byteSize", 482113L);
        example.setProperty("origin", "ai-edited");
        example.setProperty("license", "LicenseRef-acme-brand");
        example.setProperty("territories", List.of("US", "CA"), Type.STRINGS);
        example.setProperty("channels", List.of("web", "social"), Type.STRINGS);
        example.setProperty("usageUntil", "2027-12-31T23:59:59.000Z", Type.DATE);
        example.setProperty("credit", "Acme Studio");
    }

    private static void populateReceiptShape(NodeBuilder shapes) {
        NodeBuilder receipt = shape(shapes, "receipt", "Publication Receipt", "receipts",
            "Proof that a URL served exact content at a time, and which records it showed. The page stays at the edge; "
                + "the receipt is the record.",
            EXAMPLE_BRAND_CONTENT + "/receipts/2026-03-02-3f1c9a7e52b0");
        receipt.setProperty("naming", "Name receipts {yyyy-mm-dd}-{first 12 hex of contentSha256}.");
        receipt.setProperty("writer", "A publish step writes the receipt after the edge serves the new version.");

        NodeBuilder fields = node(receipt, "fields");
        field(fields, "url", "String", true, "The published URL.");
        field(fields, "representation", "String", true, "Which bytes were hashed.",
            List.of("plain-html", "markdown", "pdf", "json"));
        field(fields, "contentSha256", "String", true, "SHA-256 of that representation, lowercase hex.");
        field(fields, "publishedAt", "Date", true, "When the edge started serving it.");
        field(fields, "cites", "String[]", false, "Paths of the records the page displayed.");
        field(fields, "sourceRef", "String", false, "Authoring revision, for example a git commit or document version.");

        NodeBuilder example = example(receipt, "receipt", "Published: water bottle page");
        example.setProperty("url", "https://www.acme.example/products/water-bottle");
        example.setProperty("representation", "plain-html");
        example.setProperty("contentSha256", "3f1c9a7e52b0d4e6f8a1b3c5d7e9f0a2b4c6d8e0f1a3b5c7d9e2f4a6b8c0d1e3");
        example.setProperty("publishedAt", "2026-03-02T15:04:05.000Z", Type.DATE);
        example.setProperty("cites", List.of(EXAMPLE_CLAIM_PATH, EXAMPLE_ASSET_PATH), Type.STRINGS);
        example.setProperty("sourceRef", "git:5c1e9a2");
    }

    private static NodeBuilder shape(NodeBuilder shapes, String name, String title, String collection, String purpose, String examplePath) {
        NodeBuilder shape = node(shapes, name);
        shape.setProperty("jcr:title", title);
        shape.setProperty("purpose", purpose);
        shape.setProperty("collection", collection);
        shape.setProperty("shapeVersion", 1L);
        shape.setProperty("examplePath", examplePath);
        return shape;
    }

    private static NodeBuilder example(NodeBuilder shape, String contentType, String title) {
        NodeBuilder example = node(shape, "example");
        example.setProperty("contentType", contentType);
        example.setProperty("shapeVersion", 1L);
        example.setProperty("jcr:title", title);
        return example;
    }

    private static void field(NodeBuilder fields, String name, String type, boolean required, String description) {
        NodeBuilder field = node(fields, name);
        field.setProperty("type", type);
        field.setProperty("required", required);
        field.setProperty("description", description);
    }

    private static void field(NodeBuilder fields, String name, String type, boolean required, String description, List<String> allowedValues) {
        field(fields, name, type, required, description);
        fields.getChildNode(name).setProperty("allowedValues", allowedValues, Type.STRINGS);
    }

    private static NodeBuilder node(NodeBuilder parent, String name) {
        NodeBuilder child = parent.child(name);
        child.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        return child;
    }

    private void populateGettingStarted(NodeBuilder genesis, String genesisHost) {
        NodeBuilder gettingStarted = genesis.child("getting-started");
        gettingStarted.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        gettingStarted.setProperty("jcr:title", "Getting Started with OakChain");
        gettingStarted.setProperty("jcr:description",
            "The shortest path to understanding the fabric, joining a cluster, and reading canonical chain state.");

        NodeBuilder prereqs = gettingStarted.child("1-prerequisites");
        prereqs.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        prereqs.setProperty("title", "Prerequisites");
        prereqs.setProperty("item-1", "A validator URL");
        prereqs.setProperty("item-2", "curl, jq, or another HTTP client");
        prereqs.setProperty("item-3", "An Ethereum wallet for write/payment flows");
        prereqs.setProperty("item-4", "The discipline to trust the live contract surface, not stale examples");

        NodeBuilder discover = gettingStarted.child("2-discover-surface");
        discover.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        discover.setProperty("title", "Discover the Live Surface");
        discover.setProperty("curl-health", "curl http://VALIDATOR:8090/health");
        discover.setProperty("curl-manifest", "curl http://VALIDATOR:8090/v1/index | jq .");
        discover.setProperty("curl-consensus", "curl http://VALIDATOR:8090/v1/consensus/status | jq .");
        discover.setProperty("note", "Use /v1/index as route truth. Local HTML routes are diagnostic veneers.");

        NodeBuilder browseGenesis = gettingStarted.child("3-browse-genesis");
        browseGenesis.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        browseGenesis.setProperty("title", "Open the Canonical Genesis Node");
        browseGenesis.setProperty("step-1", "GET /v1/explorer/content/nav and pick a clusterId.");
        browseGenesis.setProperty("step-2", "Use the selected clusterId with the node endpoint.");
        browseGenesis.setProperty("step-3",
            "GET /v1/explorer/content/clusters/{clusterId}/node?path=" + CANONICAL_GENESIS_PATH);
        browseGenesis.setProperty("step-4",
            "GET /v1/explorer/content/clusters/{clusterId}/provenance?path=" + CANONICAL_GENESIS_PATH);
        browseGenesis.setProperty("note", "CRX/OC should open here by default; future CLI bootstrap should read this same node.");

        NodeBuilder joinCluster = gettingStarted.child("4-join-cluster");
        joinCluster.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        joinCluster.setProperty("title", "Join the Cluster");
        joinCluster.setProperty("env-1", "BOOTSTRAP_PRIMARY_HOST=" + genesisHost);
        joinCluster.setProperty("env-2", "BOOTSTRAP_PRIMARY_PORT=8091");
        joinCluster.setProperty("env-3", "CONSENSUS_ENABLED=true");
        joinCluster.setProperty("env-4", "CONSENSUS_MODE=aeron");
        joinCluster.setProperty("env-5", "CONSENSUS_SELF_URL=http://your-validator:8090");
        joinCluster.setProperty("env-6", "AERON_CLUSTER_NODE_ID=<unique-id>");
        joinCluster.setProperty("note", "A joining validator should sync the same genesis and then follow Aeron cluster authority.");

        NodeBuilder writeFlow = gettingStarted.child("5-write-flow");
        writeFlow.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        writeFlow.setProperty("title", "Submit a Write");
        writeFlow.setProperty("step-1", "Obtain a chain-backed proposalId from the authorize/payment contract flow.");
        writeFlow.setProperty("step-2",
            "POST walletAddress, signature, message, contentType, and proposalId to /v1/propose-write.");
        writeFlow.setProperty("step-3", "202 means accepted, not committed. Poll /v1/ops/operations/{proposalId} until state=COMMITTED.");
        writeFlow.setProperty("step-4", "Inspect queue and adaptive release state via /v1/proposals/queue/stats and /v1/proposals/release-flow.");
        writeFlow.setProperty("note", "The validator allocates the resulting storage path under your wallet namespace.");
        writeFlow.setProperty("shape", "Set contentType to a shape from " + SHAPES_PATH + " and send its fields as shapes/today describes.");

        NodeBuilder readWallet = gettingStarted.child("6-read-wallet");
        readWallet.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        readWallet.setProperty("title", "Read Wallet-Centric State");
        readWallet.setProperty("curl-wallet-stats",
            "curl --get http://VALIDATOR:8090/v1/wallets/stats --data-urlencode 'wallet=0xYOUR_WALLET' | jq .");
        readWallet.setProperty("curl-wallet-content",
            "curl --get http://VALIDATOR:8090/v1/wallets/content --data-urlencode 'wallet=0xYOUR_WALLET' | jq .");
        readWallet.setProperty("note", "Use the explorer content contract for tree/node/provenance and wallet queries for wallet-centric listings.");
    }

    private void populateApi(NodeBuilder genesis) {
        NodeBuilder api = genesis.child("api");
        api.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        api.setProperty("jcr:title", "API Reference");
        api.setProperty("jcr:description", "Live validator contracts that matter for bootstrap, operations, and content.");
        api.setProperty("baseUrl", "http://VALIDATOR:8090");
        api.setProperty("manifest", "/v1/index");

        NodeBuilder discovery = api.child("discovery");
        discovery.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        discovery.setProperty("GET_health", "Shallow health");
        discovery.setProperty("GET_health_deep", "Deep dependency health");
        discovery.setProperty("GET_v1_index", "Live validator surface manifest");
        discovery.setProperty("GET_v1_head", "Head status");

        NodeBuilder consensus = api.child("consensus");
        consensus.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        consensus.setProperty("GET_v1_consensus_status", "Consensus status and cluster health");
        consensus.setProperty("GET_v1_consensus_leader", "Canonical leader resolution");
        consensus.setProperty("POST_v1_propose_write", "Submit signed write proposal");
        consensus.setProperty("POST_v1_propose_delete", "Submit signed delete proposal");
        consensus.setProperty("GET_v1_proposals_id_status", "Proposal status by id");
        consensus.setProperty("GET_v1_ops_operations_id", "Operation outcome: only COMMITTED includes the durability acknowledgement.");
        consensus.setProperty("GET_v1_proposals_queue_stats", "Queue, backpressure, and finality counters");
        consensus.setProperty("GET_v1_proposals_release_flow", "Adaptive verified-release state");

        NodeBuilder explorer = api.child("explorer");
        explorer.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        explorer.setProperty("GET_v1_explorer_summary", "Explorer summary contract");
        explorer.setProperty("GET_v1_explorer_content_nav", "Cluster-aware content navigation");
        explorer.setProperty("GET_v1_explorer_content_tree", "Cluster-scoped content tree browse");
        explorer.setProperty("GET_v1_explorer_content_node", "Cluster-scoped node detail");
        explorer.setProperty("GET_v1_explorer_content_provenance", "Cluster-scoped provenance and authority facts");

        NodeBuilder wallets = api.child("wallets");
        wallets.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        wallets.setProperty("GET_v1_wallets_stats", "Wallet usage and counts");
        wallets.setProperty("GET_v1_wallets_content", "Wallet content query");

        NodeBuilder binary = api.child("binary");
        binary.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        binary.setProperty("POST_v1_binary_declare_intent", "Declare binary upload intent");
        binary.setProperty("GET_v1_binary_check_intent_token", "Check binary upload intent");
        binary.setProperty("POST_v1_binary_complete_upload", "Complete binary upload");
        binary.setProperty("note", "Binaries resolve through BlobStore/IPFS while consensus stores the references.");

        NodeBuilder config = api.child("configuration");
        config.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        config.setProperty("GET_v1_config_osgi", "Effective OSGi config values");
        config.setProperty("GET_v1_config_osgi_schema", "OSGi config metadata schema");
        config.setProperty("GET_v1_config_osgi_sources", "OSGi config source map");
        config.setProperty("GET_v1_config_osgi_delta", "Current values vs defaults");

        NodeBuilder replication = api.child("replication");
        replication.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        replication.setProperty("GET_journal_log", "Journal file for segment sync");
        replication.setProperty("GET_manifest", "Segment manifest");
        replication.setProperty("GET_segments_segmentId", "Fetch specific segment by id");
    }

    private void populateExamples(NodeBuilder genesis) {
        NodeBuilder examples = genesis.child("examples");
        examples.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        examples.setProperty("jcr:title", "Working Examples");
        examples.setProperty("jcr:description", "Copy-paste discovery flow for operators, explorers, and future CLI tooling.");

        NodeBuilder curlDiscovery = examples.child("curl-discovery");
        curlDiscovery.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        curlDiscovery.setProperty("title", "Validator Discovery");
        curlDiscovery.setProperty("health", "curl http://localhost:8090/health");
        curlDiscovery.setProperty("manifest", "curl http://localhost:8090/v1/index | jq .");
        curlDiscovery.setProperty("consensus", "curl http://localhost:8090/v1/consensus/status | jq .");

        NodeBuilder curlGenesis = examples.child("curl-genesis");
        curlGenesis.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        curlGenesis.setProperty("title", "Read Genesis Through Explorer");
        curlGenesis.setProperty("discover-nav", "curl http://localhost:8090/v1/explorer/content/nav | jq .");
        curlGenesis.setProperty("read-node",
            "curl --get http://localhost:8090/v1/explorer/content/clusters/<clusterId>/node "
                + "--data-urlencode 'path=" + CANONICAL_GENESIS_PATH + "' | jq .");
        curlGenesis.setProperty("read-provenance",
            "curl --get http://localhost:8090/v1/explorer/content/clusters/<clusterId>/provenance "
                + "--data-urlencode 'path=" + CANONICAL_GENESIS_PATH + "' | jq .");

        NodeBuilder curlWallet = examples.child("curl-wallet");
        curlWallet.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        curlWallet.setProperty("title", "Inspect Wallet State");
        curlWallet.setProperty("wallet-stats",
            "curl --get http://localhost:8090/v1/wallets/stats --data-urlencode 'wallet=0xYOUR_WALLET' | jq .");
        curlWallet.setProperty("wallet-content",
            "curl --get http://localhost:8090/v1/wallets/content --data-urlencode 'wallet=0xYOUR_WALLET' | jq .");

        NodeBuilder readOrder = examples.child("read-order");
        readOrder.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        readOrder.setProperty("step-1", "protocol");
        readOrder.setProperty("step-2", "content-contract");
        readOrder.setProperty("step-3", "shapes");
        readOrder.setProperty("step-4", "architecture");
        readOrder.setProperty("step-5", "api");
        readOrder.setProperty("step-6", "getting-started");
    }

    private void populateArchitecture(NodeBuilder genesis) {
        NodeBuilder architecture = genesis.child("architecture");
        architecture.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        architecture.setProperty("jcr:title", "System Architecture");
        architecture.setProperty("jcr:description", "How OakChain composes storage, consensus, and contract surfaces.");

        NodeBuilder components = architecture.child("components");
        components.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        components.setProperty("oak-segment-store", "Apache Oak TarMK - proven content storage from Adobe AEM");
        components.setProperty("aeron-cluster", "High-performance Raft consensus (io.aeron.cluster)");
        components.setProperty("ethereum", "Settlement, authorization, and external proof surfaces");
        components.setProperty("ipfs", "Content-addressed binary storage");
        components.setProperty("http-api", "Validator-native HTTP contracts");

        NodeBuilder paths = architecture.child("wallet-scoped-paths");
        paths.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        paths.setProperty("pattern",
            "/oak-chain/{shard-level-1}/{shard-level-2}/{shard-level-3}/{wallet}[/{organization}]/content/{collection}/{name}");
        paths.setProperty("example", EXAMPLE_CLAIM_PATH);
        paths.setProperty("genesis", CANONICAL_GENESIS_PATH);
        paths.setProperty("sharding", "First 3 bytes of wallet address create the 3-level directory structure");
        paths.setProperty("contract", "The wallet namespace is enforced; records below /content follow genesis/shapes.");

        NodeBuilder consensus = architecture.child("consensus");
        consensus.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        consensus.setProperty("algorithm", "Raft (via Aeron Cluster)");
        consensus.setProperty("quorum", "Majority of validators must agree");
        consensus.setProperty("leader-election", "Automatic - leader handles write origination");
        consensus.setProperty("replication", "Aeron orders committed commands; every member must reproduce the same typed logical state.");
        consensus.setProperty("finality", "202 is admission only. COMMITTED requires PROCESSED plus a verified flush-backed quorum acknowledgement.");
        consensus.setProperty("launchProfile", "One fixed three-member cluster; quorum two; tolerate one unavailable member, not Byzantine behavior.");
        consensus.setProperty("failurePolicy", "A member that cannot apply a committed mutation must stop serving until repaired.");

        NodeBuilder bootstrapAuthority = architecture.child("bootstrap-authority");
        bootstrapAuthority.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        bootstrapAuthority.setProperty("genesis-node", CANONICAL_GENESIS_PATH);
        bootstrapAuthority.setProperty("route-manifest", "/v1/index");
        bootstrapAuthority.setProperty("explorer-entry", "/v1/explorer/content/nav");
    }

    private void populateEconomics(NodeBuilder genesis) {
        NodeBuilder economics = genesis.child("economics");
        economics.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        economics.setProperty("jcr:title", "Settlement and Resource Model");
        economics.setProperty("jcr:description", "What OakChain currently enforces around authorization, settlement, and resource accountability.");
        economics.setProperty("philosophy", "The validator should describe enforced economics, not speculative product packaging.");

        NodeBuilder model = economics.child("settlement-model");
        model.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        model.setProperty("authority", "The contract/payment flow authorizes writes and anchors settlement facts.");
        model.setProperty("runtime", "The validator verifies chain-backed proposal intent, stages work, and applies deterministic consensus.");
        model.setProperty("positioning", "Oak Segment Consensus publishes runtime economics as settlement and accountability facts, not commercial packaging.");
        model.setProperty("current-truth", "Treat economics here as protocol/accountability guidance, not SKU language.");

        NodeBuilder resourceSignals = economics.child("resource-signals");
        resourceSignals.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        resourceSignals.setProperty("fragmentation", "Fragmentation and batching still matter operationally because they shape storage pressure and release behavior.");
        resourceSignals.setProperty("queue", "Queue depth, backpressure, and release flow are the observable runtime signals.");
        resourceSignals.setProperty("routes", "/v1/proposals/queue/stats, /v1/proposals/release-flow, /v1/fragmentation/*");
        resourceSignals.setProperty("note", "Use metrics and settlement evidence to reason about cost, not marketing tier names.");

        NodeBuilder paymentFlow = economics.child("payment-flow");
        paymentFlow.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        paymentFlow.setProperty("step-1", "Client obtains or derives a chain-backed proposalId.");
        paymentFlow.setProperty("step-2", "Validator verifies signature and stages the proposal.");
        paymentFlow.setProperty("step-3", "Consensus and release governance determine when work is applied.");
        paymentFlow.setProperty("step-4", "Settlement and authorization facts remain auditable.");
        paymentFlow.setProperty("step-5", "Committed content becomes durable chain state.");
    }

    private void populateTroubleshooting(NodeBuilder genesis) {
        NodeBuilder troubleshooting = genesis.child("troubleshooting");
        troubleshooting.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        troubleshooting.setProperty("jcr:title", "Troubleshooting Guide");
        troubleshooting.setProperty("jcr:description", "Common issues and how to close the loop quickly");

        NodeBuilder issues = troubleshooting.child("common-issues");
        issues.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        issues.setProperty("issue-signature-invalid", "SIGNATURE_INVALID: Ensure the wallet signature matches the exact payload submitted to the validator.");
        issues.setProperty("issue-not-leader", "NOT_LEADER: Resolve the leader via /v1/consensus/leader and retry against that validator.");
        issues.setProperty("issue-path-forbidden", "PATH_FORBIDDEN: Operate only inside your wallet namespace.");
        issues.setProperty("issue-epoch-stale", "EPOCH_STALE: Refresh your local state and resubmit with current chain facts.");
        issues.setProperty("issue-cluster-id", "Unknown clusterId: refresh /v1/explorer/content/nav before using tree, node, or provenance endpoints.");

        NodeBuilder healthChecks = troubleshooting.child("health-checks");
        healthChecks.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        healthChecks.setProperty("check-1", "curl /health - should return a healthy local response");
        healthChecks.setProperty("check-2", "curl /v1/consensus/status - verify cluster role and health");
        healthChecks.setProperty("check-3", "curl /v1/consensus/leader - verify canonical leader resolution");
        healthChecks.setProperty("check-4", "curl /v1/index - verify expected contracts are present");
    }

    private void populateAbout(NodeBuilder genesis) {
        NodeBuilder about = genesis.child("about");
        about.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        about.setProperty("jcr:title", "About OakChain");
        about.setProperty("mission", "Decentralizing enterprise content management");
        about.setProperty("foundation", "Built on Apache Oak - the proven content repository behind Adobe AEM");
        about.setProperty("value-proposition", "Billions of dollars of enterprise content already runs on Oak. We're adding decentralization, cryptographic ownership, and blockchain finality.");
        about.setProperty("philosophy", "Bitcoin-tight reliability meets enterprise content management");
        about.setProperty("principles", "Fail Loud, Fail Fast, Never Silently Corrupt");
        about.setProperty("team", "somarc + AI collaborators - distributed intelligence building distributed systems");
        about.setProperty("license", "Apache 2.0");

        NodeBuilder thesis = genesis.child("thesis");
        thesis.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        thesis.setProperty("jcr:title", "The Thesis");
        thesis.setProperty("premise-1", "Enterprise content is the most valuable data no one can prove.");
        thesis.setProperty("premise-2", "Audit trails should be data, not policy.");
        thesis.setProperty("premise-3", "Determinism is the only safe way to scale trust.");
        thesis.setProperty("result", "OakChain makes content provable, portable, and economically secure.");
        thesis.setProperty("audience", "Builders who want boring reliability and bold guarantees.");

        NodeBuilder boldBets = genesis.child("bold-bets");
        boldBets.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        boldBets.setProperty("bet-1", "Every serious enterprise will demand verifiable content history.");
        boldBets.setProperty("bet-2", "AEM-scale systems can be decentralized without losing performance.");
        boldBets.setProperty("bet-3", "Proof of custody will become the default compliance standard.");
        boldBets.setProperty("bet-4", "Developers will choose APIs over platforms if guarantees are stronger.");
        boldBets.setProperty("bet-5", "Developers will choose systems with stronger guarantees over familiar platforms.");

        NodeBuilder guarantees = genesis.child("guarantees");
        guarantees.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        guarantees.setProperty("scope", "Required invariants, not a substitute for release tests or recovery evidence.");
        guarantees.setProperty("determinism", "Same ordered commands, same typed logical state; physical Segment IDs may differ.");
        guarantees.setProperty("auditability", "Every change is traceable by ID, time, and signer.");
        guarantees.setProperty("durability", "COMMITTED must survive the declared failure envelope; restart and replacement drills are release gates.");
        guarantees.setProperty("portability", "Content is readable without privileged infrastructure.");
        guarantees.setProperty("integrity", "The genesis digest verifies code-authored content. Production signed-intent and settlement binding require separate validation.");

        NodeBuilder nonGoals = genesis.child("non-goals");
        nonGoals.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        nonGoals.setProperty("non-goal-1", "We do not chase maximal throughput at the expense of determinism.");
        nonGoals.setProperty("non-goal-2", "We do not require custodial identity or closed networks.");
        nonGoals.setProperty("non-goal-3", "We do not hide failures; we surface them early and loudly.");

        NodeBuilder oath = genesis.child("operator-oath");
        oath.setProperty("jcr:primaryType", "nt:unstructured", Type.NAME);
        oath.setProperty("oath-1", "Run the node as if the audit depends on you.");
        oath.setProperty("oath-2", "Do not change history. Fix the system.");
        oath.setProperty("oath-3", "Measure everything; guess nothing.");
        oath.setProperty("oath-4", "If it fails, document the failure in the chain.");
    }

    private void populateGenesisImage(NodeBuilder genesis, String genesisDate, Blob image) {
        NodeBuilder genesisImage = genesis.child("do-it-live.jpeg");
        genesisImage.setProperty("jcr:primaryType", "nt:file", Type.NAME);
        genesisImage.setProperty("jcr:created", genesisDate, Type.DATE);

        NodeBuilder imageContent = genesisImage.child("jcr:content");
        imageContent.setProperty("jcr:primaryType", "nt:resource", Type.NAME);
        imageContent.setProperty("jcr:mimeType", "image/jpeg");
        imageContent.setProperty("jcr:lastModified", genesisDate, Type.DATE);
        imageContent.setProperty("size", image.length());
        imageContent.setProperty("sha256", IMAGE_SHA256);
        imageContent.setProperty("jcr:data", image, Type.BINARY);
    }

    private byte[] loadGenesisImageBytes() throws IOException {
        try (InputStream imageStream = getClass().getClassLoader().getResourceAsStream(GENESIS_IMAGE_RESOURCE)) {
            if (imageStream == null) {
                throw new IOException("Required genesis asset is missing: " + GENESIS_IMAGE_RESOURCE);
            }
            byte[] imageBytes = imageStream.readAllBytes();
            if (!IMAGE_SHA256.equals(GenesisDigest.ofBytes(imageBytes))) {
                throw new IOException("Bundled genesis asset checksum mismatch");
            }
            return imageBytes;
        }
    }

    private static void verifyString(NodeState node, String propertyName, String expected) {
        if (!node.hasProperty(propertyName) || node.getProperty(propertyName).getType() != Type.STRING) {
            throw new IllegalStateException("Canonical genesis is missing property " + propertyName);
        }
        String actual = node.getProperty(propertyName).getValue(Type.STRING);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("Canonical genesis property " + propertyName + " expected "
                + expected + " but found " + actual);
        }
    }

    private static String normalizeGenesisValidatorUrl(String genesisValidatorUrl) {
        if (genesisValidatorUrl == null || genesisValidatorUrl.trim().isEmpty()) {
            return DEFAULT_GENESIS_VALIDATOR_URL;
        }
        return genesisValidatorUrl.trim();
    }

    private static String extractHost(String genesisValidatorUrl) {
        try {
            URI uri = URI.create(genesisValidatorUrl);
            if (uri.getHost() != null && !uri.getHost().isEmpty()) {
                return uri.getHost();
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to simple parsing below
        }

        String sanitized = genesisValidatorUrl.replace("http://", "").replace("https://", "");
        int slashIndex = sanitized.indexOf('/');
        if (slashIndex >= 0) {
            sanitized = sanitized.substring(0, slashIndex);
        }
        int colonIndex = sanitized.indexOf(':');
        return colonIndex >= 0 ? sanitized.substring(0, colonIndex) : sanitized;
    }
}
