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

import org.junit.After;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class StorageBackendConfigTest {

    private static final String[] ALL_PROPS = {
        "oak.segment.backend",
        "oak.segment.azure.connection-string",
        "oak.segment.azure.container",
        "oak.segment.azure.root-prefix",
        "oak.segment.aws.bucket",
        "oak.segment.aws.region",
        "oak.segment.aws.dynamodb-table",
        "oak.segment.aws.endpoint",
        "oak.blob.backend",
        "blobstore.type",
        "ipfs.api.endpoint",
        "oak.blob.azure.connection-string",
        "oak.blob.azure.container",
        "oak.blob.aws.bucket",
        "oak.blob.aws.region",
        "oak.blob.aws.endpoint",
    };

    @After
    public void clearProps() {
        for (String prop : ALL_PROPS) {
            System.clearProperty(prop);
        }
    }

    // ── defaults ─────────────────────────────────────────────────────────────

    @Test
    public void testDefaultsToLocalSegmentAndIpfsBlob() {
        StorageBackendConfig cfg = StorageBackendConfig.load();
        assertEquals(StorageBackendConfig.SegmentBackend.LOCAL, cfg.getSegmentBackend());
        assertEquals(StorageBackendConfig.BlobBackend.IPFS, cfg.getBlobBackend());
        assertEquals("/ip4/127.0.0.1/tcp/5001", cfg.getBlobIpfsEndpoint());
    }

    // ── segment backend: azure ───────────────────────────────────────────────

    @Test
    public void testAzureSegmentRequiresConnectionString() {
        System.setProperty("oak.segment.backend", "azure");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("OAK_SEGMENT_AZURE_CONNECTION_STRING"));
        }
    }

    @Test
    public void testAzureSegmentRequiresContainer() {
        System.setProperty("oak.segment.backend", "azure");
        System.setProperty("oak.segment.azure.connection-string", "DefaultEndpointsProtocol=https;...");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("OAK_SEGMENT_AZURE_CONTAINER"));
        }
    }

    @Test
    public void testAzureSegmentFullConfig() {
        System.setProperty("oak.segment.backend", "azure");
        System.setProperty("oak.segment.azure.connection-string", "cs-value");
        System.setProperty("oak.segment.azure.container", "my-container");
        System.setProperty("oak.segment.azure.root-prefix", "prod");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.SegmentBackend.AZURE, cfg.getSegmentBackend());
        assertEquals("cs-value", cfg.getSegmentAzureConnectionString());
        assertEquals("my-container", cfg.getSegmentAzureContainer());
        assertEquals("prod", cfg.getSegmentAzureRootPrefix());
    }

    @Test
    public void testAzureSegmentRootPrefixDefaultsToSegments() {
        System.setProperty("oak.segment.backend", "azure");
        System.setProperty("oak.segment.azure.connection-string", "cs");
        System.setProperty("oak.segment.azure.container", "c");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals("segments", cfg.getSegmentAzureRootPrefix());
    }

    // ── segment backend: aws ─────────────────────────────────────────────────

    @Test
    public void testAwsSegmentRequiresBucket() {
        System.setProperty("oak.segment.backend", "aws");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("OAK_SEGMENT_AWS_BUCKET"));
        }
    }

    @Test
    public void testAwsSegmentRequiresDynamoTable() {
        System.setProperty("oak.segment.backend", "aws");
        System.setProperty("oak.segment.aws.bucket", "my-bucket");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("OAK_SEGMENT_AWS_DYNAMODB_TABLE"));
        }
    }

    @Test
    public void testAwsSegmentFullConfig() {
        System.setProperty("oak.segment.backend", "aws");
        System.setProperty("oak.segment.aws.bucket", "seg-bucket");
        System.setProperty("oak.segment.aws.region", "eu-west-1");
        System.setProperty("oak.segment.aws.dynamodb-table", "oak-journal");
        System.setProperty("oak.segment.aws.endpoint", "http://localhost:4566");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.SegmentBackend.AWS, cfg.getSegmentBackend());
        assertEquals("seg-bucket", cfg.getSegmentAwsBucket());
        assertEquals("eu-west-1", cfg.getSegmentAwsRegion());
        assertEquals("oak-journal", cfg.getSegmentAwsDynamoTable());
        assertEquals("http://localhost:4566", cfg.getSegmentAwsEndpoint());
        assertTrue(cfg.hasSegmentAwsEndpointOverride());
    }

    @Test
    public void testAwsSegmentRegionDefaultsToUsEast1() {
        System.setProperty("oak.segment.backend", "aws");
        System.setProperty("oak.segment.aws.bucket", "b");
        System.setProperty("oak.segment.aws.dynamodb-table", "t");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals("us-east-1", cfg.getSegmentAwsRegion());
    }

    @Test
    public void testAwsSegmentNoEndpointOverrideByDefault() {
        System.setProperty("oak.segment.backend", "aws");
        System.setProperty("oak.segment.aws.bucket", "b");
        System.setProperty("oak.segment.aws.dynamodb-table", "t");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertFalse(cfg.hasSegmentAwsEndpointOverride());
    }

    @Test
    public void testInvalidSegmentBackendRejected() {
        System.setProperty("oak.segment.backend", "gcs");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("gcs"));
        }
    }

    // ── blob backend: ipfs ───────────────────────────────────────────────────

    @Test
    public void testIpfsBlobCustomEndpoint() {
        System.setProperty("ipfs.api.endpoint", "/ip4/10.0.0.5/tcp/5001");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.BlobBackend.IPFS, cfg.getBlobBackend());
        assertEquals("/ip4/10.0.0.5/tcp/5001", cfg.getBlobIpfsEndpoint());
    }

    @Test
    public void testLegacyBlobstoreTypeAliasToIpfs() {
        System.setProperty("blobstore.type", "ipfs");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.BlobBackend.IPFS, cfg.getBlobBackend());
    }

    @Test
    public void testOakBlobBackendTakesPrecedenceOverLegacyAlias() {
        System.setProperty("blobstore.type", "ipfs");
        System.setProperty("oak.blob.backend", "ipfs");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.BlobBackend.IPFS, cfg.getBlobBackend());
    }

    // ── blob backend: azure ──────────────────────────────────────────────────

    @Test
    public void testAzureBlobRequiresConnectionString() {
        System.setProperty("oak.blob.backend", "azure");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("OAK_BLOB_AZURE_CONNECTION_STRING"));
        }
    }

    @Test
    public void testAzureBlobRequiresContainer() {
        System.setProperty("oak.blob.backend", "azure");
        System.setProperty("oak.blob.azure.connection-string", "cs");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("OAK_BLOB_AZURE_CONTAINER"));
        }
    }

    @Test
    public void testAzureBlobFullConfig() {
        System.setProperty("oak.blob.backend", "azure");
        System.setProperty("oak.blob.azure.connection-string", "blob-cs");
        System.setProperty("oak.blob.azure.container", "blobs");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.BlobBackend.AZURE, cfg.getBlobBackend());
        assertEquals("blob-cs", cfg.getBlobAzureConnectionString());
        assertEquals("blobs", cfg.getBlobAzureContainer());
    }

    // ── blob backend: aws ────────────────────────────────────────────────────

    @Test
    public void testAwsBlobRequiresBucket() {
        System.setProperty("oak.blob.backend", "aws");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("OAK_BLOB_AWS_BUCKET"));
        }
    }

    @Test
    public void testAwsBlobFullConfig() {
        System.setProperty("oak.blob.backend", "aws");
        System.setProperty("oak.blob.aws.bucket", "blob-bucket");
        System.setProperty("oak.blob.aws.region", "ap-southeast-1");
        System.setProperty("oak.blob.aws.endpoint", "http://localhost:4566");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.BlobBackend.AWS, cfg.getBlobBackend());
        assertEquals("blob-bucket", cfg.getBlobAwsBucket());
        assertEquals("ap-southeast-1", cfg.getBlobAwsRegion());
        assertEquals("http://localhost:4566", cfg.getBlobAwsEndpoint());
        assertTrue(cfg.hasBlobAwsEndpointOverride());
    }

    @Test
    public void testAwsBlobRegionDefaultsToUsEast1() {
        System.setProperty("oak.blob.backend", "aws");
        System.setProperty("oak.blob.aws.bucket", "b");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals("us-east-1", cfg.getBlobAwsRegion());
    }

    @Test
    public void testAwsBlobNoEndpointOverrideByDefault() {
        System.setProperty("oak.blob.backend", "aws");
        System.setProperty("oak.blob.aws.bucket", "b");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertFalse(cfg.hasBlobAwsEndpointOverride());
    }

    @Test
    public void testInvalidBlobBackendRejected() {
        System.setProperty("oak.blob.backend", "gcs");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("gcs"));
        }
    }

    // ── mixed-cloud coherence ────────────────────────────────────────────────

    @Test
    public void testMixedCloudAzureSegmentAwsBlobRejected() {
        System.setProperty("oak.segment.backend", "azure");
        System.setProperty("oak.segment.azure.connection-string", "cs");
        System.setProperty("oak.segment.azure.container", "seg");
        System.setProperty("oak.blob.backend", "aws");
        System.setProperty("oak.blob.aws.bucket", "blobs");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException for mixed-cloud");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Mixed-cloud"));
        }
    }

    @Test
    public void testMixedCloudAwsSegmentAzureBlobRejected() {
        System.setProperty("oak.segment.backend", "aws");
        System.setProperty("oak.segment.aws.bucket", "segs");
        System.setProperty("oak.segment.aws.dynamodb-table", "t");
        System.setProperty("oak.blob.backend", "azure");
        System.setProperty("oak.blob.azure.connection-string", "cs");
        System.setProperty("oak.blob.azure.container", "blobs");
        try {
            StorageBackendConfig.load();
            fail("Expected IllegalStateException for mixed-cloud");
        } catch (IllegalStateException e) {
            assertTrue(e.getMessage().contains("Mixed-cloud"));
        }
    }

    @Test
    public void testValidAzureSegmentWithIpfsBlobAllowed() {
        System.setProperty("oak.segment.backend", "azure");
        System.setProperty("oak.segment.azure.connection-string", "cs");
        System.setProperty("oak.segment.azure.container", "seg");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.SegmentBackend.AZURE, cfg.getSegmentBackend());
        assertEquals(StorageBackendConfig.BlobBackend.IPFS, cfg.getBlobBackend());
    }

    @Test
    public void testValidAwsSegmentWithIpfsBlobAllowed() {
        System.setProperty("oak.segment.backend", "aws");
        System.setProperty("oak.segment.aws.bucket", "b");
        System.setProperty("oak.segment.aws.dynamodb-table", "t");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.SegmentBackend.AWS, cfg.getSegmentBackend());
        assertEquals(StorageBackendConfig.BlobBackend.IPFS, cfg.getBlobBackend());
    }

    @Test
    public void testValidAzureAzureCombinationAllowed() {
        System.setProperty("oak.segment.backend", "azure");
        System.setProperty("oak.segment.azure.connection-string", "cs");
        System.setProperty("oak.segment.azure.container", "seg");
        System.setProperty("oak.blob.backend", "azure");
        System.setProperty("oak.blob.azure.connection-string", "cs");
        System.setProperty("oak.blob.azure.container", "blobs");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.SegmentBackend.AZURE, cfg.getSegmentBackend());
        assertEquals(StorageBackendConfig.BlobBackend.AZURE, cfg.getBlobBackend());
    }

    @Test
    public void testValidAwsAwsCombinationAllowed() {
        System.setProperty("oak.segment.backend", "aws");
        System.setProperty("oak.segment.aws.bucket", "segs");
        System.setProperty("oak.segment.aws.dynamodb-table", "t");
        System.setProperty("oak.blob.backend", "aws");
        System.setProperty("oak.blob.aws.bucket", "blobs");

        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertEquals(StorageBackendConfig.SegmentBackend.AWS, cfg.getSegmentBackend());
        assertEquals(StorageBackendConfig.BlobBackend.AWS, cfg.getBlobBackend());
    }

    // ── null safety on unset params ───────────────────────────────────────────

    @Test
    public void testUnusedBackendParamsAreNull() {
        // LOCAL segment + IPFS blob — azure/aws params should all be null
        StorageBackendConfig cfg = StorageBackendConfig.load();

        assertNull(cfg.getSegmentAzureConnectionString());
        assertNull(cfg.getSegmentAzureContainer());
        assertNull(cfg.getSegmentAzureRootPrefix());
        assertNull(cfg.getSegmentAwsBucket());
        assertNull(cfg.getSegmentAwsRegion());
        assertNull(cfg.getSegmentAwsDynamoTable());
        assertNull(cfg.getSegmentAwsEndpoint());
        assertNull(cfg.getBlobAzureConnectionString());
        assertNull(cfg.getBlobAzureContainer());
        assertNull(cfg.getBlobAwsBucket());
        assertNull(cfg.getBlobAwsRegion());
        assertNull(cfg.getBlobAwsEndpoint());
    }
}
