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
 * Builder for creating a Composite NodeStore that mounts the global
 * Blockchain AEM store alongside a local store.
 * <p>
 * The resulting NodeStore will have:
 * - Local store (read-write): Complete standalone AEM repository
 * - Global store (read-only): Mounted at /oak-chain with all wallet content
 * <p>
 * Example usage:
 * <pre>
 * NodeStore composite = new CompositeStoreBuilder()
 *     .withLocalStoreDirectory("/opt/aem/segmentstore")
 *     .withGlobalStoreDirectory("/opt/aem/segmentstore-global")
 *     .withMountPath("/oak-chain")
 *     .build();
 * </pre>
 */
public interface CompositeStoreBuilder {
    
    /**
     * Set the directory for the local (read-write) segment store.
     *
     * @param directory path to local store directory
     * @return this builder
     */
    @NotNull
    CompositeStoreBuilder withLocalStoreDirectory(@NotNull String directory);
    
    /**
     * Set the directory for the global (read-only) segment store.
     *
     * @param directory path to global store directory
     * @return this builder
     */
    @NotNull
    CompositeStoreBuilder withGlobalStoreDirectory(@NotNull String directory);
    
    /**
     * Set the mount path for the global store.
     *
     * @param mountPath the path (e.g., "/oak-chain")
     * @return this builder
     */
    @NotNull
    CompositeStoreBuilder withMountPath(@NotNull String mountPath);
    
    /**
     * Set the mount name for the global store.
     *
     * @param mountName the name (e.g., "oak-chain-global")
     * @return this builder
     */
    @NotNull
    CompositeStoreBuilder withMountName(@NotNull String mountName);
    
    /**
     * Build the composite NodeStore.
     * <p>
     * This creates the local and global segment stores and
     * composes them into a single NodeStore.
     *
     * @return the composite NodeStore
     * @throws IllegalStateException if required configuration is missing
     */
    @NotNull
    Object build();  // Returns NodeStore, but we avoid the dependency here
}

