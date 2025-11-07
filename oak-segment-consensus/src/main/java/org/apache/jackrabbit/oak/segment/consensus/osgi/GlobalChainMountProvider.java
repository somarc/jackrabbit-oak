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

import org.apache.jackrabbit.oak.spi.mount.Mount;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.mount.Mounts;
import org.jetbrains.annotations.NotNull;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

/**
 * Provides mount information for the global blockchain chain at /oak-chain.
 * This tells Oak's Composite NodeStore that /oak-chain should be mounted from
 * a separate (read-only) segment store.
 * <p>
 * NOTE: For POC, this just registers the mount configuration. The actual composite
 * setup would require deeper Oak repository initialization hooks.
 */
@Component(
    service = MountInfoProvider.class,
    immediate = true,
    property = {
        "service.ranking:Integer=1000" // High ranking to ensure it's used
    }
)
public class GlobalChainMountProvider implements MountInfoProvider {
    
    private static final Logger LOG = LoggerFactory.getLogger(GlobalChainMountProvider.class);
    
    private static final String MOUNT_NAME = "oak-chain-global";
    private static final String MOUNT_PATH = "/oak-chain";
    
    private final MountInfoProvider delegate;
    
    public GlobalChainMountProvider() {
        // Use Oak's Mounts.Builder which handles all the Mount interface methods correctly
        this.delegate = Mounts.newBuilder()
            .readOnlyMount(MOUNT_NAME, MOUNT_PATH)
            .build();
    }
    
    @Activate
    protected void activate() {
        LOG.info("===========================================");
        LOG.info("  Blockchain AEM - Global Mount Provider");
        LOG.info("===========================================");
        LOG.info("Mount Name: {}", MOUNT_NAME);
        LOG.info("Mount Path: {}", MOUNT_PATH);
        LOG.info("Read-Only:  true");
        LOG.info("");
        LOG.info("NOTE: POC registers mount configuration.");
        LOG.info("Full Composite NodeStore requires deeper Oak");
        LOG.info("repository initialization integration.");
        LOG.info("===========================================");
    }
    
    @NotNull
    @Override
    public Mount getMountByPath(@NotNull String path) {
        return delegate.getMountByPath(path);
    }
    
    @NotNull
    @Override
    public Mount getMountByName(@NotNull String name) {
        return delegate.getMountByName(name);
    }
    
    @NotNull
    @Override
    public Collection<Mount> getNonDefaultMounts() {
        return delegate.getNonDefaultMounts();
    }
    
    @NotNull
    @Override
    public Mount getDefaultMount() {
        return delegate.getDefaultMount();
    }
    
    @Override
    public boolean hasNonDefaultMounts() {
        return delegate.hasNonDefaultMounts();
    }
    
    @NotNull
    @Override
    public Collection<Mount> getMountsPlacedDirectlyUnder(@NotNull String path) {
        return delegate.getMountsPlacedDirectlyUnder(path);
    }
    
    @NotNull
    @Override
    public Collection<Mount> getMountsPlacedUnder(@NotNull String path) {
        return delegate.getMountsPlacedUnder(path);
    }
}
