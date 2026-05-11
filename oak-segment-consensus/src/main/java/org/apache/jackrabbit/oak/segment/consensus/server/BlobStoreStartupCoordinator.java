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
import java.util.Properties;

import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.s3.AmazonS3;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import org.apache.jackrabbit.oak.blob.cloud.azure.blobstorage.AzureDataStore;
import org.apache.jackrabbit.oak.blob.cloud.s3.S3DataStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.segment.consensus.config.StorageBackendConfig;
import org.apache.jackrabbit.oak.spi.blob.BlobStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class BlobStoreStartupCoordinator {

    private static final Logger log = LoggerFactory.getLogger(BlobStoreStartupCoordinator.class);

    StartupResult initialize(File storeDir,
                             StorageBackendConfig config,
                             GlobalStoreServerComponentFactory componentFactory) throws IOException {
        switch (config.getBlobBackend()) {
            case IPFS:
                return initializeIpfs(storeDir, config, componentFactory);
            case AZURE:
                return initializeAzure(config);
            case AWS:
                return initializeAws(config);
            default:
                throw new IOException("Unknown blob backend: " + config.getBlobBackend());
        }
    }

    private StartupResult initializeIpfs(File storeDir,
                                          StorageBackendConfig config,
                                          GlobalStoreServerComponentFactory componentFactory)
            throws IOException {
        String endpoint = config.getBlobIpfsEndpoint();
        log.info("📦 Configuring IPFS BlobStore (endpoint={})...", endpoint);
        try {
            BlobStore blobStore = componentFactory.createIpfsBlobStore(endpoint, storeDir);
            log.info("✅ Blob store: IPFS ({})", endpoint);
            return new StartupResult("ipfs", blobStore);
        } catch (Exception e) {
            throw new IOException("IPFS BlobStore initialization failed: " + e.getMessage(), e);
        }
    }

    private StartupResult initializeAzure(StorageBackendConfig config) throws IOException {
        log.info("📦 Configuring Azure BlobStore (container={})...", config.getBlobAzureContainer());
        try {
            BlobServiceClient serviceClient = new BlobServiceClientBuilder()
                .connectionString(config.getBlobAzureConnectionString())
                .buildClient();
            BlobContainerClient containerClient = serviceClient
                .getBlobContainerClient(config.getBlobAzureContainer());
            if (!containerClient.exists()) {
                containerClient.create();
                log.info("Created Azure Blob container: {}", config.getBlobAzureContainer());
            }

            AzureDataStore dataStore = new AzureDataStore();
            Properties props = new Properties();
            props.setProperty("connectionString", config.getBlobAzureConnectionString());
            props.setProperty("containerName", config.getBlobAzureContainer());
            dataStore.setProperties(props);
            dataStore.init(null);

            BlobStore blobStore = new DataStoreBlobStore(dataStore);
            log.info("✅ Blob store: Azure Blob (container={})", config.getBlobAzureContainer());
            return new StartupResult("azure", blobStore);
        } catch (Exception e) {
            throw new IOException("Azure BlobStore initialization failed: " + e.getMessage(), e);
        }
    }

    private StartupResult initializeAws(StorageBackendConfig config) throws IOException {
        log.info("📦 Configuring S3 BlobStore (bucket={})...", config.getBlobAwsBucket());
        try {
            String endpointOverride = config.hasBlobAwsEndpointOverride()
                ? config.getBlobAwsEndpoint() : null;
            AmazonS3 s3 = StorageBackendFactory.buildS3Client(config.getBlobAwsRegion(), endpointOverride);

            S3DataStore dataStore = new S3DataStore();
            Properties props = new Properties();
            props.setProperty("s3Bucket", config.getBlobAwsBucket());
            props.setProperty("s3Region", config.getBlobAwsRegion());
            if (endpointOverride != null) {
                props.setProperty("s3EndPoint", endpointOverride);
                props.setProperty("s3pathStyleAccess", "true");
                props.setProperty("accessKey", "test");
                props.setProperty("secretKey", "test");
            }
            dataStore.setProperties(props);
            dataStore.init(null);

            BlobStore blobStore = new DataStoreBlobStore(dataStore);
            log.info("✅ Blob store: AWS S3 (bucket={})", config.getBlobAwsBucket());
            return new StartupResult("aws", blobStore);
        } catch (Exception e) {
            throw new IOException("S3 BlobStore initialization failed: " + e.getMessage(), e);
        }
    }

    static final class StartupResult {
        private final String blobStoreType;
        private final BlobStore blobStore;

        StartupResult(String blobStoreType, BlobStore blobStore) {
            this.blobStoreType = blobStoreType;
            this.blobStore = blobStore;
        }

        String getBlobStoreType() { return blobStoreType; }
        BlobStore getBlobStore() { return blobStore; }
    }
}
