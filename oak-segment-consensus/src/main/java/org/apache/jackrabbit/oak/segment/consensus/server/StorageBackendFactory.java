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

import java.io.File;
import java.io.IOException;

import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder.EndpointConfiguration;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.apache.jackrabbit.oak.segment.SegmentNodeStoreBuilders;
import org.apache.jackrabbit.oak.segment.aws.AwsContext;
import org.apache.jackrabbit.oak.segment.aws.AwsPersistence;
import org.apache.jackrabbit.oak.segment.azure.AzurePersistence;
import org.apache.jackrabbit.oak.segment.consensus.config.StorageBackendConfig;
import org.apache.jackrabbit.oak.segment.consensus.mount.ValidatorReadViewBuilder;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingRuntimeConfig;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.file.FileStoreBuilder;
import org.apache.jackrabbit.oak.segment.file.InvalidFileStoreVersionException;
import org.apache.jackrabbit.oak.segment.spi.persistence.SegmentNodeStorePersistence;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Creates the segment-store {@link FileStore} wired to the backend selected by
 * {@link StorageBackendConfig}: local TAR, Azure Blob, or AWS S3+DynamoDB.
 *
 * <p>The validator JVM never holds binary bytes regardless of which backend is
 * active — that invariant is owned by the blob transport layer (ADR 083).
 * This factory is responsible only for the segment persistence plane.
 */
final class StorageBackendFactory {

    private static final Logger log = LoggerFactory.getLogger(StorageBackendFactory.class);

    private StorageBackendFactory() {
    }

    static ServerStorageRuntime createStorageRuntime(File storeDir,
                                                     BlobStore blobStore,
                                                     StorageBackendConfig config)
            throws IOException, InvalidFileStoreVersionException {

        FileStore fileStore = buildFileStore(storeDir, blobStore, config);
        NodeStore nodeStore = SegmentNodeStoreBuilders.builder(fileStore).build();
        ValidatorReadViewBuilder.BuildResult readView = new ValidatorReadViewBuilder()
            .build(nodeStore, ShardingRuntimeConfig.load());
        return new ServerStorageRuntime(fileStore, nodeStore,
            readView.getReadViewNodeStore(), readView);
    }

    private static FileStore buildFileStore(File storeDir,
                                            BlobStore blobStore,
                                            StorageBackendConfig config)
            throws IOException, InvalidFileStoreVersionException {

        FileStoreBuilder builder = FileStoreBuilder.fileStoreBuilder(storeDir)
            .withMaxFileSize(256)
            .withMemoryMapping(false)
            .withBlobStore(blobStore);

        switch (config.getSegmentBackend()) {
            case AZURE:
                builder.withCustomPersistence(buildAzurePersistence(config));
                log.info("✅ Segment store: Azure Blob (container={})", config.getSegmentAzureContainer());
                break;
            case AWS:
                builder.withCustomPersistence(buildAwsPersistence(config));
                log.info("✅ Segment store: AWS S3 (bucket={}, table={})",
                    config.getSegmentAwsBucket(), config.getSegmentAwsDynamoTable());
                break;
            default:
                log.info("✅ Segment store: local TAR ({})", storeDir.getAbsolutePath());
                break;
        }

        return builder.build();
    }

    // ── Azure ─────────────────────────────────────────────────────────────────

    private static SegmentNodeStorePersistence buildAzurePersistence(StorageBackendConfig config) {
        BlobServiceClient serviceClient = new BlobServiceClientBuilder()
            .connectionString(config.getSegmentAzureConnectionString())
            .buildClient();
        BlobContainerClient containerClient = serviceClient
            .getBlobContainerClient(config.getSegmentAzureContainer());
        if (!containerClient.exists()) {
            containerClient.create();
            log.info("Created Azure Blob container: {}", config.getSegmentAzureContainer());
        }
        return new AzurePersistence(containerClient, config.getSegmentAzureRootPrefix());
    }

    // ── AWS ──────────────────────────────────────────────────────────────────

    private static SegmentNodeStorePersistence buildAwsPersistence(StorageBackendConfig config)
            throws IOException {
        AmazonS3 s3 = buildS3Client(
            config.getSegmentAwsRegion(),
            config.hasSegmentAwsEndpointOverride() ? config.getSegmentAwsEndpoint() : null);
        AmazonDynamoDB ddb = buildDynamoClient(
            config.getSegmentAwsRegion(),
            config.hasSegmentAwsEndpointOverride() ? config.getSegmentAwsEndpoint() : null);

        // Use the same table for both journal and lock (oak-segment-aws convention).
        AwsContext awsContext = AwsContext.create(
            s3,
            config.getSegmentAwsBucket(),
            "segments",
            ddb,
            config.getSegmentAwsDynamoTable(),
            config.getSegmentAwsDynamoTable() + "-lock");

        return new AwsPersistence(awsContext);
    }

    static AmazonS3 buildS3Client(String region, String endpointOverride) {
        AmazonS3ClientBuilder builder = AmazonS3ClientBuilder.standard()
            .withPathStyleAccessEnabled(true); // required for floci and most non-AWS endpoints
        if (endpointOverride != null) {
            builder.withEndpointConfiguration(
                new EndpointConfiguration(endpointOverride, region));
            // floci uses static test credentials
            builder.withCredentials(new AWSStaticCredentialsProvider(
                new BasicAWSCredentials("test", "test")));
        } else {
            builder.withRegion(region);
        }
        return builder.build();
    }

    static AmazonDynamoDB buildDynamoClient(String region, String endpointOverride) {
        AmazonDynamoDBClientBuilder builder = AmazonDynamoDBClientBuilder.standard();
        if (endpointOverride != null) {
            builder.withEndpointConfiguration(
                new EndpointConfiguration(endpointOverride, region));
            builder.withCredentials(new AWSStaticCredentialsProvider(
                new BasicAWSCredentials("test", "test")));
        } else {
            builder.withRegion(region);
        }
        return builder.build();
    }
}
