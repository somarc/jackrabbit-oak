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
package org.apache.jackrabbit.oak.segment.consensus.osgi;

import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * OSGi service that provides a Composite NodeStore with a global Blockchain AEM mount.
 * <p>
 * This service creates a composite repository where:
 * - The default (local) store is a regular Oak Segment Tar store
 * - The global store at /oak-chain is synchronized from a global consensus network
 */
@Component(
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = false
)
@Designate(ocd = BlockchainNodeStoreService.Config.class)
public class BlockchainNodeStoreService {

    @ObjectClassDefinition(
        name = "Blockchain AEM - NodeStore Configuration",
        description = "Configures the Composite NodeStore with global blockchain mount"
    )
    @interface Config {
        @AttributeDefinition(
            name = "Global Store URL",
            description = "URL of the global blockchain store server (e.g. http://oak-global-store:8090)"
        )
        String globalStoreUrl() default "http://oak-global-store:8090";

        @AttributeDefinition(
            name = "Mount Path",
            description = "Path where the global store should be mounted (default: /oak-chain)"
        )
        String mountPath() default "/oak-chain";

        @AttributeDefinition(
            name = "Local Repository Path",
            description = "Path to the local segment store directory"
        )
        String localRepositoryPath() default "repository/segmentstore";

        @AttributeDefinition(
            name = "Enable Blockchain AEM",
            description = "Enable or disable the Blockchain AEM composite mount"
        )
        boolean enabled() default true;
    }

    private static final Logger LOG = LoggerFactory.getLogger(BlockchainNodeStoreService.class);

    private String globalStoreUrl;
    private String mountPath;
    private String localRepositoryPath;
    private boolean enabled;

    @Activate
    protected void activate(Config config) {
        this.globalStoreUrl = config.globalStoreUrl();
        this.mountPath = config.mountPath();
        this.localRepositoryPath = config.localRepositoryPath();
        this.enabled = config.enabled();

        LOG.info("===========================================");
        LOG.info("  Blockchain AEM - NodeStore Activating");
        LOG.info("===========================================");
        LOG.info("Global Store URL: {}", globalStoreUrl);
        LOG.info("Mount Path:       {}", mountPath);
        LOG.info("Local Store:      {}", localRepositoryPath);
        LOG.info("Enabled:          {}", enabled);

        if (enabled) {
            try {
                initializeCompositeNodeStore();
                LOG.info("Blockchain AEM NodeStore initialized successfully!");
            } catch (Exception e) {
                LOG.error("Failed to initialize Blockchain AEM NodeStore", e);
            }
        } else {
            LOG.info("Blockchain AEM NodeStore is DISABLED");
        }
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("Blockchain AEM NodeStore deactivating...");
        // Cleanup resources if needed
    }

    private void initializeCompositeNodeStore() throws Exception {
        // For POC: Just log the configuration
        // Full implementation would:
        // 1. Create a read-only SegmentNodeStore for the global store
        // 2. Create a MountInfoProvider with the /oak-chain mount
        // 3. Build a CompositeNodeStore
        // 4. Register it as the NodeStore service

        LOG.info("POC: Composite NodeStore configuration ready");
        LOG.info("  - Local store path: {}", new File(localRepositoryPath).getAbsolutePath());
        LOG.info("  - Global store URL: {}", globalStoreUrl);
        LOG.info("  - Mount point: {}", mountPath);
        LOG.info("  ");
        LOG.info("Note: Full Composite NodeStore integration requires coordination with Oak repository initialization.");
        LOG.info("For POC, the mount will be accessible via custom servlets.");
    }
}

