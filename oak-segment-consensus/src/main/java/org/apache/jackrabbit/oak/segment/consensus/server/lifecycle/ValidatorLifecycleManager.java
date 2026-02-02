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
package org.apache.jackrabbit.oak.segment.consensus.server.lifecycle;

import org.apache.jackrabbit.oak.segment.consensus.server.GlobalStoreServer;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterService;
import org.apache.jackrabbit.oak.segment.consensus.server.GlobalStoreServerComponentFactory;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicyOption;
import org.osgi.service.metatype.annotations.Designate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OSGi lifecycle manager for validator startup/shutdown.
 */
@Component(service = ValidatorLifecycleManager.class, configurationPolicy = ConfigurationPolicy.REQUIRE, immediate = true)
@Designate(ocd = ValidatorConfig.class)
public class ValidatorLifecycleManager {

    private static final Logger LOG = LoggerFactory.getLogger(ValidatorLifecycleManager.class);

    private final ValidatorFactory factory = new ValidatorFactory();
    private final ComponentRegistry registry = new ComponentRegistry();
    private final ShutdownHookHandler shutdownHookHandler = new ShutdownHookHandler();
    private GlobalStoreServer server;
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private volatile AeronClusterService aeronClusterService;
    @Reference(cardinality = ReferenceCardinality.OPTIONAL, policyOption = ReferencePolicyOption.GREEDY)
    private volatile GlobalStoreServerComponentFactory componentFactory;

    @Activate
    protected void activate(ValidatorConfig config) {
        LOG.info("Starting ValidatorLifecycleManager (port={}, store={})",
            config.http_port(), config.store_directory());
        server = factory.create(config);
        if (aeronClusterService != null) {
            server.setAeronClusterService(aeronClusterService);
            LOG.info("AeronClusterService injected into GlobalStoreServer");
        }
        if (componentFactory != null) {
            server.setComponentFactory(componentFactory);
            LOG.info("GlobalStoreServerComponentFactory injected");
        }
        registry.register("GlobalStoreServer", server);
        shutdownHookHandler.register(server);

        // Start asynchronously to avoid blocking OSGi activate thread.
        new Thread(() -> {
            try {
                server.start();
                LOG.info("✅ Validator server started");
            } catch (Exception e) {
                LOG.error("❌ Validator server failed to start", e);
            }
        }, "validator-lifecycle-start").start();
    }

    @Deactivate
    protected void deactivate() {
        LOG.info("Stopping ValidatorLifecycleManager");
        if (server != null) {
            server.stop();
        }
    }
}
