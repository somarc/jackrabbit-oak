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
package org.apache.jackrabbit.oak.segment.consensus.store.impl;

import org.apache.jackrabbit.oak.segment.consensus.store.CompositeStoreBuilder;
import org.apache.jackrabbit.oak.segment.consensus.store.GlobalStoreMount;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * Simple implementation of the Composite Store builder.
 * <p>
 * This is a POC implementation that validates configuration
 * and returns a descriptor of the composite store structure.
 * <p>
 * A full implementation would:
 * - Create actual FileStore instances for local and global stores
 * - Create SegmentNodeStore instances
 * - Use Oak's Mounts API to configure the composite mount
 * - Return a CompositeNodeStore instance
 */
public class SimpleCompositeStoreBuilder implements CompositeStoreBuilder {
    
    private String localStoreDirectory;
    private String globalStoreDirectory;
    private String mountPath = "/oak-chain";
    private String mountName = "oak-chain-global";
    
    @Override
    @NotNull
    public CompositeStoreBuilder withLocalStoreDirectory(@NotNull String directory) {
        this.localStoreDirectory = directory;
        return this;
    }
    
    @Override
    @NotNull
    public CompositeStoreBuilder withGlobalStoreDirectory(@NotNull String directory) {
        this.globalStoreDirectory = directory;
        return this;
    }
    
    @Override
    @NotNull
    public CompositeStoreBuilder withMountPath(@NotNull String mountPath) {
        this.mountPath = mountPath;
        return this;
    }
    
    @Override
    @NotNull
    public CompositeStoreBuilder withMountName(@NotNull String mountName) {
        this.mountName = mountName;
        return this;
    }
    
    @Override
    @NotNull
    public Object build() {
        validate();
        
        GlobalStoreMount mount = new SimpleGlobalStoreMount(
                globalStoreDirectory,
                mountPath,
                mountName
        );
        
        // For POC, return a descriptor map instead of actual NodeStore
        // A full implementation would create actual Oak NodeStore instances
        Map<String, Object> descriptor = new HashMap<>();
        descriptor.put("type", "composite");
        descriptor.put("localStore", localStoreDirectory);
        descriptor.put("globalStore", globalStoreDirectory);
        descriptor.put("mount", mount);
        descriptor.put("mountPath", mountPath);
        descriptor.put("mountName", mountName);
        descriptor.put("mountReadOnly", true);
        
        return descriptor;
    }
    
    private void validate() {
        if (localStoreDirectory == null || localStoreDirectory.isEmpty()) {
            throw new IllegalStateException("Local store directory must be set");
        }
        if (globalStoreDirectory == null || globalStoreDirectory.isEmpty()) {
            throw new IllegalStateException("Global store directory must be set");
        }
        if (mountPath == null || mountPath.isEmpty()) {
            throw new IllegalStateException("Mount path must be set");
        }
        if (mountName == null || mountName.isEmpty()) {
            throw new IllegalStateException("Mount name must be set");
        }
        if (!mountPath.startsWith("/")) {
            throw new IllegalStateException("Mount path must start with /");
        }
    }
}

