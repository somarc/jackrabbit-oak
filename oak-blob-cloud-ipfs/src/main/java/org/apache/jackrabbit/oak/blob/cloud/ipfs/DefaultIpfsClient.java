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

import io.ipfs.api.AddArgs;
import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import io.ipfs.api.WriteFilesArgs;
import io.ipfs.cid.Cid;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Production {@link IpfsClient} adapter backed by the Java IPFS HTTP client.
 */
final class DefaultIpfsClient implements IpfsClient {

    /**
     * Kubo's historical defaults, pinned so a node's {@code Import.*} settings
     * cannot silently change the CIDs Oak records.
     */
    static final AddArgs IMPORT_ARGS = AddArgs.Builder.newInstance()
        .setCidVersion(0)
        .setHash("sha2-256")
        .setChunker("size-262144")
        .setPin()
        .build();

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
        return ipfs.add(upload, IMPORT_ARGS);
    }

    @Override
    public void pinRemove(String cid) throws Exception {
        ipfs.pin.rm(Cid.decode(cid));
    }

    @Override
    public InputStream cat(String cid) throws Exception {
        return ipfs.catStream(Cid.decode(cid));
    }

    @Override
    public void ensureDirectory(String path) throws Exception {
        ipfs.files.mkdir(path, true);
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
    public void linkCid(String cid, String path) throws Exception {
        deleteFile(path);
        ipfs.files.cp("/ipfs/" + cid, path, true);
    }

    @Override
    public byte[] readFile(String path) throws Exception {
        try {
            return ipfs.files.read(path);
        } catch (Exception e) {
            if (isNotFound(e)) {
                return null;
            }
            throw e;
        }
    }

    @Override
    public boolean fileExists(String path) throws Exception {
        try {
            ipfs.files.stat(path);
            return true;
        } catch (Exception e) {
            if (isNotFound(e)) {
                return false;
            }
            throw e;
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
        try {
            ipfs.files.rm(path, false, true);
        } catch (Exception e) {
            if (!isNotFound(e)) {
                throw e;
            }
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

    /**
     * Kubo reports a missing MFS path as an error whose message contains
     * "file does not exist"; everything else is a real failure.
     */
    static boolean isNotFound(Throwable e) {
        for (Throwable current = e; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("does not exist")) {
                return true;
            }
        }
        return false;
    }

    private static String extractName(String path) {
        int separator = path.lastIndexOf('/');
        return separator >= 0 ? path.substring(separator + 1) : path;
    }
}
