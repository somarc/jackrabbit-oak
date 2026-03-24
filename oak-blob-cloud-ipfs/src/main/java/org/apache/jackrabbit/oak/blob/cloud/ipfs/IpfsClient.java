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

import java.util.List;
import java.util.Map;

/**
 * Minimal IPFS client contract used by {@link IPFSBackend}.
 *
 * <p>This indirection keeps the backend testable without depending directly on
 * the concrete Java IPFS HTTP client.</p>
 */
interface IpfsClient {

    /**
     * Returns version or capability information from the remote IPFS node.
     */
    Object version() throws Exception;

    /**
     * Uploads content to IPFS and returns the created Merkle nodes.
     */
    List<MerkleNode> add(NamedStreamable upload) throws Exception;

    /**
     * Pins the supplied CID so the node keeps the content locally.
     */
    void pinAdd(String cid) throws Exception;

    /**
     * Removes the local pin for the supplied CID.
     */
    void pinRemove(String cid) throws Exception;

    /**
     * Reads the raw bytes stored at the supplied CID.
     */
    byte[] cat(String cid) throws Exception;

    /**
     * Returns block metadata used for existence and size checks.
     */
    Map<String, Object> blockStat(String cid) throws Exception;

    /**
     * Ensures a directory exists in the IPFS Files namespace.
     */
    void ensureDirectory(String path) throws Exception;

    /**
     * Writes a file into the IPFS Files namespace, replacing any previous
     * content at the same path.
     */
    void writeFile(String path, byte[] data) throws Exception;

    /**
     * Reads a file from the IPFS Files namespace.
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
     * Deletes a file from the IPFS Files namespace.
     */
    void deleteFile(String path) throws Exception;

    /**
     * Returns the size of a file in the IPFS Files namespace.
     */
    long fileSize(String path) throws Exception;
}
