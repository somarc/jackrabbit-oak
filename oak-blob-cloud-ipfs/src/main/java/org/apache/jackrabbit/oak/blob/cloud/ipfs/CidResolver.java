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

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves an Oak blob identifier that the local IPFS Files index does not
 * know, typically from state replicated by a consensus layer, so that a
 * repository whose binaries were stored through another IPFS node can still
 * read them.
 *
 * <p>A resolved blob counts as existing even if no reachable node still holds
 * its bytes; reading it then fails. Resolver failures reach callers of the
 * backend as errors, but Oak's caching DataStore logs them and reports the
 * record as absent.</p>
 */
@FunctionalInterface
public interface CidResolver {

    /**
     * @param identifier the Oak identifier (SHA-256 of the content, hex)
     * @return the CID and length recorded for it, or {@code null} if unknown
     * @throws Exception if the source of truth cannot be read; this is
     *         reported as an error, not as a missing blob
     */
    @Nullable
    Resolution resolve(@NotNull String identifier) throws Exception;

    /**
     * @param cid    the CID of the content
     * @param length the content length in bytes, or {@code -1} if unknown
     */
    record Resolution(@NotNull String cid, long length) {
    }
}
