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

import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;

import java.io.InputStream;
import java.util.List;

/**
 * Minimal IPFS client contract used by {@link IPFSBackend}.
 *
 * <p>This indirection keeps the backend testable without depending directly on
 * the concrete Java IPFS HTTP client. Missing paths are reported through return
 * values; every other failure (unreachable daemon, protocol error) is thrown so
 * callers never mistake an outage for absent data.</p>
 */
interface IpfsClient {

    /**
     * Returns version or capability information from the remote IPFS node.
     */
    Object version() throws Exception;

    /**
     * Imports and recursively pins content with a fixed import profile, so the
     * resulting CID does not depend on the node's own import configuration.
     */
    List<MerkleNode> add(NamedStreamable upload) throws Exception;

    /**
     * Removes the local pin for the supplied CID.
     */
    void pinRemove(String cid) throws Exception;

    /**
     * Streams the file content stored at the supplied CID. The caller closes
     * the stream.
     */
    InputStream cat(String cid) throws Exception;

    /**
     * Ensures a directory, and its parents, exist in the IPFS Files namespace.
     */
    void ensureDirectory(String path) throws Exception;

    /**
     * Writes a file into the IPFS Files namespace, creating parents and
     * replacing any previous content at the same path.
     */
    void writeFile(String path, byte[] data) throws Exception;

    /**
     * Creates or replaces an IPFS Files entry that points at the supplied CID.
     */
    void linkCid(String cid, String path) throws Exception;

    /**
     * Reads a file from the IPFS Files namespace.
     *
     * @return the file content, or {@code null} when the path does not exist
     */
    byte[] readFile(String path) throws Exception;

    /**
     * Checks whether a file or directory exists in the IPFS Files namespace.
     */
    boolean fileExists(String path) throws Exception;

    /**
     * Lists the direct child names for a directory in the IPFS Files namespace.
     */
    List<String> listFiles(String path) throws Exception;

    /**
     * Deletes a file from the IPFS Files namespace; a missing path is ignored.
     */
    void deleteFile(String path) throws Exception;

    /**
     * Returns the UnixFS file size of an IPFS Files path or {@code /ipfs/<cid>}
     * path.
     */
    long fileSize(String path) throws Exception;
}
