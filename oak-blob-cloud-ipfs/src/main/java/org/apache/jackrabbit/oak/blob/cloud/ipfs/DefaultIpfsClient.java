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

import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import io.ipfs.api.WriteFilesArgs;
import io.ipfs.multihash.Multihash;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Production {@link IpfsClient} adapter backed by the Java IPFS HTTP client.
 */
final class DefaultIpfsClient implements IpfsClient {

    private final IPFS ipfs;

    /**
     * Creates a client that talks to the configured IPFS HTTP API endpoint.
     *
     * @param endpoint IPFS multiaddr-style endpoint understood by
     *                 {@link IPFS#IPFS(String)}
     */
    DefaultIpfsClient(String endpoint) {
        this.ipfs = new IPFS(endpoint);
    }

    @Override
    public Object version() throws Exception {
        return ipfs.version();
    }

    @Override
    public List<MerkleNode> add(NamedStreamable upload) throws Exception {
        return ipfs.add(upload);
    }

    @Override
    public void pinAdd(String cid) throws Exception {
        ipfs.pin.add(Multihash.fromBase58(cid));
    }

    @Override
    public void pinRemove(String cid) throws Exception {
        ipfs.pin.rm(Multihash.fromBase58(cid));
    }

    @Override
    public byte[] cat(String cid) throws Exception {
        return ipfs.cat(Multihash.fromBase58(cid));
    }

    @Override
    public Map<String, Object> blockStat(String cid) throws Exception {
        return ipfs.block.stat(Multihash.fromBase58(cid));
    }

    @Override
    public void ensureDirectory(String path) throws Exception {
        if (!fileExists(path)) {
            ipfs.files.mkdir(path, true);
        }
    }

    @Override
    public void writeFile(String path, byte[] data) throws Exception {
        WriteFilesArgs args = WriteFilesArgs.Builder.newInstance()
            .setCreate()
            .setParents()
            .setTruncate()
            .build();
        ipfs.files.write(path, new NamedStreamable.ByteArrayWrapper(extractName(path), data), args);
    }

    @Override
    public byte[] readFile(String path) throws Exception {
        return ipfs.files.read(path);
    }

    @Override
    public boolean fileExists(String path) throws Exception {
        try {
            ipfs.files.stat(path);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public List<String> listFiles(String path) throws Exception {
        List<Map> entries = ipfs.files.ls(path);
        List<String> names = new ArrayList<>();
        if (entries == null) {
            return names;
        }
        for (Map entry : entries) {
            Object name = entry.get("Name");
            if (name instanceof String) {
                names.add((String) name);
            }
        }
        return names;
    }

    @Override
    public void deleteFile(String path) throws Exception {
        if (fileExists(path)) {
            ipfs.files.rm(path, false, true);
        }
    }

    @Override
    public long fileSize(String path) throws Exception {
        Object size = ipfs.files.stat(path).get("Size");
        if (size instanceof Number) {
            return ((Number) size).longValue();
        }
        return Long.parseLong(String.valueOf(size));
    }

    private static String extractName(String path) {
        int separator = path.lastIndexOf('/');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }
}
