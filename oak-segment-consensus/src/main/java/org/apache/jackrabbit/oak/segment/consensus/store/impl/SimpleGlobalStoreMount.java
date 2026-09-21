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

import org.apache.jackrabbit.oak.segment.consensus.store.GlobalStoreMount;
import org.jetbrains.annotations.NotNull;

/**
 * Simple implementation of global store mount configuration.
 */
public class SimpleGlobalStoreMount implements GlobalStoreMount {
    
    private static final String DEFAULT_MOUNT_PATH = "/oak-chain";
    private static final String DEFAULT_MOUNT_NAME = "oak-chain-global";
    
    private final String mountPath;
    private final String mountName;
    private final String globalStoreDirectory;
    
    /**
     * Create a new global store mount configuration.
     *
     * @param globalStoreDirectory path to global segment store
     * @param mountPath where to mount the global store
     * @param mountName name for the mount
     */
    public SimpleGlobalStoreMount(
            @NotNull String globalStoreDirectory,
            @NotNull String mountPath,
            @NotNull String mountName) {
        this.globalStoreDirectory = globalStoreDirectory;
        this.mountPath = mountPath;
        this.mountName = mountName;
    }
    
    /**
     * Create a global store mount with default path and name.
     *
     * @param globalStoreDirectory path to global segment store
     */
    public SimpleGlobalStoreMount(@NotNull String globalStoreDirectory) {
        this(globalStoreDirectory, DEFAULT_MOUNT_PATH, DEFAULT_MOUNT_NAME);
    }
    
    @Override
    @NotNull
    public String getMountPath() {
        return mountPath;
    }
    
    @Override
    public boolean isReadOnly() {
        // Global store is ALWAYS read-only
        // Writes go through consensus protocol
        return true;
    }
    
    @Override
    @NotNull
    public String getGlobalStoreDirectory() {
        return globalStoreDirectory;
    }
    
    @Override
    @NotNull
    public String getMountName() {
        return mountName;
    }
    
    @Override
    public String toString() {
        return "GlobalStoreMount{" +
                "mountPath='" + mountPath + '\'' +
                ", mountName='" + mountName + '\'' +
                ", readOnly=" + isReadOnly() +
                ", directory='" + globalStoreDirectory + '\'' +
                '}';
    }
}

