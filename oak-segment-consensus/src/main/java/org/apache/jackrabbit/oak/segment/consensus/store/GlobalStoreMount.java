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
package org.apache.jackrabbit.oak.segment.consensus.store;

import org.jetbrains.annotations.NotNull;

/**
 * Configuration for mounting the global Blockchain AEM store.
 * <p>
 * This defines how the global segment store (containing all
 * wallet-owned content at /oak-chain) is mounted into a local
 * AEM instance.
 */
public interface GlobalStoreMount {
    
    /**
     * Get the mount path where the global store will be accessible.
     *
     * @return mount path (e.g., "/oak-chain")
     */
    @NotNull
    String getMountPath();
    
    /**
     * Check if the global store is read-only.
     * <p>
     * For Blockchain AEM, this should always be true - writes
     * go through the consensus protocol, not direct NodeStore access.
     *
     * @return true if read-only
     */
    boolean isReadOnly();
    
    /**
     * Get the directory where global segments are stored locally.
     *
     * @return path to global segment store directory
     */
    @NotNull
    String getGlobalStoreDirectory();
    
    /**
     * Get the name of this mount for identification.
     *
     * @return mount name (e.g., "oak-chain-global")
     */
    @NotNull
    String getMountName();
}

