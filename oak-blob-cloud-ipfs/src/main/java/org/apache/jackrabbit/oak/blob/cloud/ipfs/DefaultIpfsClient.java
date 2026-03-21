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
import io.ipfs.multihash.Multihash;

import java.util.List;
import java.util.Map;

final class DefaultIpfsClient implements IpfsClient {

    private final IPFS ipfs;

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
}
