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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import org.apache.jackrabbit.core.data.DataStore;
import org.apache.jackrabbit.oak.plugins.blob.AbstractSharedCachingDataStore;
import org.apache.jackrabbit.oak.plugins.blob.datastore.AbstractDataStoreService;
import org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreBlobStore;
import org.apache.jackrabbit.oak.stats.StatisticsProvider;
import org.jetbrains.annotations.NotNull;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.osgi.service.component.ComponentContext;

import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Map;
import java.util.Properties;

/**
 * OSGi wrapper that exposes {@link IPFSDataStore} through Oak's standard blob
 * store service registration flow.
 */
@Component(configurationPolicy = ConfigurationPolicy.REQUIRE, name = IPFSDataStoreService.NAME)
@Designate(ocd = IPFSDataStoreService.Configuration.class)
public class IPFSDataStoreService extends AbstractDataStoreService {

    public static final String NAME = "org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore";

    private static final String DESCRIPTION = "oak.datastore.description";

    private ServiceRegistration delegateReg;

    @Reference
    private StatisticsProvider statisticsProvider;

    @ObjectClassDefinition(
        name = "Apache Jackrabbit Oak IPFS Data Store",
        description = "Configures the IPFS-backed Oak BlobStore, including the IPFS API endpoint, durable MFS root, and shared cache settings."
    )
    public @interface Configuration {

        @AttributeDefinition(
            name = "IPFS API Endpoint",
            description = "IPFS HTTP API multiaddr used to upload blobs and query block metadata."
        )
        String ipfsApiEndpoint() default "/ip4/127.0.0.1/tcp/5001";

        @AttributeDefinition(
            name = "IPFS Files Root",
            description = "Root path in the IPFS Files namespace used to persist Oak CID mappings and metadata. Use a unique root per Oak repository when sharing one IPFS repository."
        )
        String ipfsFilesRoot() default "/oak/ipfs";

        @AttributeDefinition(
            name = "Minimum Record Length",
            description = "Minimum blob size in bytes before binaries are delegated to IPFS. Smaller binaries remain inline in Oak segments."
        )
        int minRecordLength() default 16 * 1024;

        @AttributeDefinition(
            name = "Blob ID Cache Size (MB)",
            description = "In-memory DataStoreBlobStore cache size in megabytes."
        )
        int cacheSizeInMB() default DataStoreBlobStore.DEFAULT_CACHE_SIZE;

        @AttributeDefinition(
            name = "Encode Length In Blob ID",
            description = "Whether Oak blob identifiers should include the binary length suffix."
        )
        boolean encodeLengthInId() default true;

        @AttributeDefinition(
            name = "Local Cache Path",
            description = "Filesystem path used by the shared caching datastore for local staging and download cache files."
        )
        String path() default "";

        @AttributeDefinition(
            name = "Local Cache Size",
            description = "Maximum size in bytes of the local shared caching datastore staging and download cache."
        )
        long cacheSize() default 64L * 1024 * 1024 * 1024;

        @AttributeDefinition(
            name = "Staging Split Percentage",
            description = "Percentage of the local cache reserved for the staging area before uploads complete."
        )
        int stagingSplitPercentage() default 10;

        @AttributeDefinition(
            name = "Upload Threads",
            description = "Number of background upload threads used by the shared caching datastore."
        )
        int uploadThreads() default 10;

        @AttributeDefinition(
            name = "Staging Purge Interval",
            description = "How often, in seconds, the staging area is purged."
        )
        int stagingPurgeInterval() default 300;

        @AttributeDefinition(
            name = "Staging Retry Interval",
            description = "How often, in seconds, failed staged uploads are retried."
        )
        int stagingRetryInterval() default 600;
    }

    @Override
    protected DataStore createDataStore(ComponentContext context, Map<String, Object> config) {
        Properties properties = new Properties();
        properties.putAll(config);

        IPFSDataStore dataStore = new IPFSDataStore();
        dataStore.setStatisticsProvider(getStatisticsProvider());
        dataStore.setProperties(properties);

        Dictionary<String, Object> props = new Hashtable<>();
        props.put(Constants.SERVICE_PID, dataStore.getClass().getName());
        props.put(DESCRIPTION, getDescription());

        delegateReg = context.getBundleContext().registerService(new String[] {
            AbstractSharedCachingDataStore.class.getName(),
            IPFSDataStore.class.getName()
        }, dataStore, props);

        return dataStore;
    }

    @Override
    protected void deactivate() throws org.apache.jackrabbit.core.data.DataStoreException {
        if (delegateReg != null) {
            delegateReg.unregister();
        }
        super.deactivate();
    }

    @Override
    protected String[] getDescription() {
        return new String[] {"type=ipfs"};
    }

    @Override
    protected @NotNull StatisticsProvider getStatisticsProvider() {
        return statisticsProvider;
    }

    @Override
    protected void setStatisticsProvider(StatisticsProvider statisticsProvider) {
        this.statisticsProvider = statisticsProvider;
    }
}
