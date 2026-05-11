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
package org.apache.jackrabbit.oak.segment.consensus.config;

/**
 * Resolves storage backend selection and per-backend connection parameters
 * at validator startup. Extends ADR 082's configuration model with an
 * {@code aws} segment and blob backend alongside the existing {@code local}
 * and {@code azure} options (ADR 089).
 *
 * <p>Config precedence: OSGi override → system property → environment variable → default.
 */
public final class StorageBackendConfig {

    public enum SegmentBackend { LOCAL, AZURE, AWS }

    public enum BlobBackend { IPFS, AZURE, AWS }

    // ── segment backend ──────────────────────────────────────────────────────

    private final SegmentBackend segmentBackend;

    // azure
    private final String segmentAzureConnectionString;
    private final String segmentAzureContainer;
    private final String segmentAzureRootPrefix;

    // aws
    private final String segmentAwsBucket;
    private final String segmentAwsRegion;
    private final String segmentAwsDynamoTable;
    private final String segmentAwsEndpoint; // empty = production

    // ── blob backend ─────────────────────────────────────────────────────────

    private final BlobBackend blobBackend;

    // ipfs (legacy)
    private final String blobIpfsEndpoint;

    // azure
    private final String blobAzureConnectionString;
    private final String blobAzureContainer;

    // aws
    private final String blobAwsBucket;
    private final String blobAwsRegion;
    private final String blobAwsEndpoint; // empty = production

    private StorageBackendConfig(Builder b) {
        this.segmentBackend = b.segmentBackend;
        this.segmentAzureConnectionString = b.segmentAzureConnectionString;
        this.segmentAzureContainer = b.segmentAzureContainer;
        this.segmentAzureRootPrefix = b.segmentAzureRootPrefix;
        this.segmentAwsBucket = b.segmentAwsBucket;
        this.segmentAwsRegion = b.segmentAwsRegion;
        this.segmentAwsDynamoTable = b.segmentAwsDynamoTable;
        this.segmentAwsEndpoint = b.segmentAwsEndpoint;
        this.blobBackend = b.blobBackend;
        this.blobIpfsEndpoint = b.blobIpfsEndpoint;
        this.blobAzureConnectionString = b.blobAzureConnectionString;
        this.blobAzureContainer = b.blobAzureContainer;
        this.blobAwsBucket = b.blobAwsBucket;
        this.blobAwsRegion = b.blobAwsRegion;
        this.blobAwsEndpoint = b.blobAwsEndpoint;
    }

    /**
     * Loads config from environment and system properties, validates coherence,
     * and returns an immutable instance.
     *
     * @throws IllegalStateException if a required parameter for the selected backend
     *                               is absent, or if a disallowed mixed-cloud combination is detected
     */
    public static StorageBackendConfig load() {
        Builder b = new Builder();

        // ── segment backend ──────────────────────────────────────────────────
        String segRaw = RuntimeConfigValueResolver.readString(
            "oak.segment.backend", "OAK_SEGMENT_BACKEND", "local");
        b.segmentBackend = parseSegmentBackend(segRaw);

        if (b.segmentBackend == SegmentBackend.AZURE) {
            b.segmentAzureConnectionString = requireParam(
                "oak.segment.azure.connection-string", "OAK_SEGMENT_AZURE_CONNECTION_STRING",
                "oak.segment.backend=azure requires OAK_SEGMENT_AZURE_CONNECTION_STRING");
            b.segmentAzureContainer = requireParam(
                "oak.segment.azure.container", "OAK_SEGMENT_AZURE_CONTAINER",
                "oak.segment.backend=azure requires OAK_SEGMENT_AZURE_CONTAINER");
            b.segmentAzureRootPrefix = RuntimeConfigValueResolver.readString(
                "oak.segment.azure.root-prefix", "OAK_SEGMENT_AZURE_ROOT_PREFIX", "segments");
        }

        if (b.segmentBackend == SegmentBackend.AWS) {
            b.segmentAwsBucket = requireParam(
                "oak.segment.aws.bucket", "OAK_SEGMENT_AWS_BUCKET",
                "oak.segment.backend=aws requires OAK_SEGMENT_AWS_BUCKET");
            b.segmentAwsRegion = RuntimeConfigValueResolver.readString(
                "oak.segment.aws.region", "OAK_SEGMENT_AWS_REGION", "us-east-1");
            b.segmentAwsDynamoTable = requireParam(
                "oak.segment.aws.dynamodb-table", "OAK_SEGMENT_AWS_DYNAMODB_TABLE",
                "oak.segment.backend=aws requires OAK_SEGMENT_AWS_DYNAMODB_TABLE");
            b.segmentAwsEndpoint = RuntimeConfigValueResolver.readString(
                "oak.segment.aws.endpoint", "OAK_SEGMENT_AWS_ENDPOINT", "");
        }

        // ── blob backend ─────────────────────────────────────────────────────
        // Legacy BLOBSTORE_TYPE / blobstore.type is aliased to oak.blob.backend for compat.
        String blobRaw = RuntimeConfigValueResolver.readString(
            "oak.blob.backend", "OAK_BLOB_BACKEND",
            RuntimeConfigValueResolver.readString("blobstore.type", "BLOBSTORE_TYPE", "ipfs"));
        b.blobBackend = parseBlobBackend(blobRaw);

        if (b.blobBackend == BlobBackend.IPFS) {
            b.blobIpfsEndpoint = RuntimeConfigValueResolver.readString(
                "ipfs.api.endpoint", "IPFS_API_ENDPOINT", "/ip4/127.0.0.1/tcp/5001");
        }

        if (b.blobBackend == BlobBackend.AZURE) {
            b.blobAzureConnectionString = requireParam(
                "oak.blob.azure.connection-string", "OAK_BLOB_AZURE_CONNECTION_STRING",
                "oak.blob.backend=azure requires OAK_BLOB_AZURE_CONNECTION_STRING");
            b.blobAzureContainer = requireParam(
                "oak.blob.azure.container", "OAK_BLOB_AZURE_CONTAINER",
                "oak.blob.backend=azure requires OAK_BLOB_AZURE_CONTAINER");
        }

        if (b.blobBackend == BlobBackend.AWS) {
            b.blobAwsBucket = requireParam(
                "oak.blob.aws.bucket", "OAK_BLOB_AWS_BUCKET",
                "oak.blob.backend=aws requires OAK_BLOB_AWS_BUCKET");
            b.blobAwsRegion = RuntimeConfigValueResolver.readString(
                "oak.blob.aws.region", "OAK_BLOB_AWS_REGION", "us-east-1");
            b.blobAwsEndpoint = RuntimeConfigValueResolver.readString(
                "oak.blob.aws.endpoint", "OAK_BLOB_AWS_ENDPOINT", "");
        }

        // ── cross-backend coherence check ────────────────────────────────────
        // Mixed-cloud (azure segment + aws blob or vice versa) is not supported at v1.
        boolean segAzure = b.segmentBackend == SegmentBackend.AZURE;
        boolean segAws   = b.segmentBackend == SegmentBackend.AWS;
        boolean blobAzure = b.blobBackend == BlobBackend.AZURE;
        boolean blobAws   = b.blobBackend == BlobBackend.AWS;

        if ((segAzure && blobAws) || (segAws && blobAzure)) {
            throw new IllegalStateException(
                "Mixed-cloud storage is not supported: oak.segment.backend=" + segRaw
                + " with oak.blob.backend=" + blobRaw + ". "
                + "Use azure+azure, aws+aws, azure+ipfs, aws+ipfs, or local+ipfs.");
        }

        return new StorageBackendConfig(b);
    }

    // ── accessors ────────────────────────────────────────────────────────────

    public SegmentBackend getSegmentBackend() { return segmentBackend; }

    public String getSegmentAzureConnectionString() { return segmentAzureConnectionString; }
    public String getSegmentAzureContainer() { return segmentAzureContainer; }
    public String getSegmentAzureRootPrefix() { return segmentAzureRootPrefix; }

    public String getSegmentAwsBucket() { return segmentAwsBucket; }
    public String getSegmentAwsRegion() { return segmentAwsRegion; }
    public String getSegmentAwsDynamoTable() { return segmentAwsDynamoTable; }
    public String getSegmentAwsEndpoint() { return segmentAwsEndpoint; }
    public boolean hasSegmentAwsEndpointOverride() {
        return segmentAwsEndpoint != null && !segmentAwsEndpoint.isEmpty();
    }

    public BlobBackend getBlobBackend() { return blobBackend; }

    public String getBlobIpfsEndpoint() { return blobIpfsEndpoint; }

    public String getBlobAzureConnectionString() { return blobAzureConnectionString; }
    public String getBlobAzureContainer() { return blobAzureContainer; }

    public String getBlobAwsBucket() { return blobAwsBucket; }
    public String getBlobAwsRegion() { return blobAwsRegion; }
    public String getBlobAwsEndpoint() { return blobAwsEndpoint; }
    public boolean hasBlobAwsEndpointOverride() {
        return blobAwsEndpoint != null && !blobAwsEndpoint.isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static SegmentBackend parseSegmentBackend(String raw) {
        switch (raw.toLowerCase().trim()) {
            case "local": return SegmentBackend.LOCAL;
            case "azure": return SegmentBackend.AZURE;
            case "aws":   return SegmentBackend.AWS;
            default: throw new IllegalStateException(
                "Unknown oak.segment.backend value: \"" + raw + "\". Use local, azure, or aws.");
        }
    }

    private static BlobBackend parseBlobBackend(String raw) {
        switch (raw.toLowerCase().trim()) {
            case "ipfs":  return BlobBackend.IPFS;
            case "azure": return BlobBackend.AZURE;
            case "aws":   return BlobBackend.AWS;
            default: throw new IllegalStateException(
                "Unknown oak.blob.backend value: \"" + raw + "\". Use ipfs, azure, or aws.");
        }
    }

    private static String requireParam(String sysProp, String envVar, String errorMsg) {
        String value = RuntimeConfigValueResolver.readString(sysProp, envVar, null);
        if (!RuntimeConfigValueResolver.hasText(value)) {
            throw new IllegalStateException(errorMsg);
        }
        return value;
    }

    private static final class Builder {
        SegmentBackend segmentBackend;
        String segmentAzureConnectionString;
        String segmentAzureContainer;
        String segmentAzureRootPrefix;
        String segmentAwsBucket;
        String segmentAwsRegion;
        String segmentAwsDynamoTable;
        String segmentAwsEndpoint;
        BlobBackend blobBackend;
        String blobIpfsEndpoint;
        String blobAzureConnectionString;
        String blobAzureContainer;
        String blobAwsBucket;
        String blobAwsRegion;
        String blobAwsEndpoint;
    }
}
