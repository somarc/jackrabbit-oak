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
package org.apache.jackrabbit.oak.segment.consensus.genesis;

import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.jackrabbit.oak.api.Blob;
import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Type;
import org.apache.jackrabbit.oak.commons.StringUtils;
import org.apache.jackrabbit.oak.spi.state.NodeState;

/** Length-framed, typed logical content; never Segment IDs or BlobStore references. */
final class GenesisDigest {
    static final String FORMAT = "oak-chain-genesis-v1";
    static final String PROPERTY = "integrityDigest";

    private GenesisDigest() {
    }

    static String of(NodeState wallet) throws IOException {
        MessageDigest digest = sha256();
        try (DataOutputStream out = new DataOutputStream(new DigestOutputStream(OutputStream.nullOutputStream(), digest))) {
            text(out, FORMAT);
            node(out, wallet, "");
        }
        return StringUtils.convertBytesToHex(digest.digest());
    }

    static String ofBytes(byte[] bytes) {
        return StringUtils.convertBytesToHex(sha256().digest(bytes));
    }

    private static void node(DataOutputStream out, NodeState node, String path) throws IOException {
        text(out, path);
        List<PropertyState> properties = new ArrayList<>();
        for (PropertyState property : node.getProperties()) {
            if (!("/content/genesis".equals(path) && PROPERTY.equals(property.getName()))) {
                properties.add(property);
            }
        }
        properties.sort(Comparator.comparing(PropertyState::getName));
        out.writeInt(properties.size());
        for (PropertyState property : properties) {
            text(out, property.getName());
            out.writeInt(property.getType().tag());
            out.writeBoolean(property.isArray());
            out.writeInt(property.count());
            for (int i = 0; i < property.count(); i++) {
                if (property.getType().tag() == Type.BINARY.tag()) {
                    Blob blob = property.isArray() ? property.getValue(Type.BINARY, i) : property.getValue(Type.BINARY);
                    MessageDigest binaryDigest = sha256();
                    long size = 0;
                    try (InputStream stream = blob.getNewStream()) {
                        byte[] buffer = new byte[8192];
                        int read;
                        while ((read = stream.read(buffer)) != -1) {
                            binaryDigest.update(buffer, 0, read);
                            size += read;
                        }
                    }
                    out.writeLong(size);
                    out.write(binaryDigest.digest());
                } else {
                    text(out, property.isArray() ? property.getValue(Type.STRING, i) : property.getValue(Type.STRING));
                }
            }
        }
        List<String> children = new ArrayList<>();
        node.getChildNodeNames().forEach(children::add);
        children.sort(String::compareTo);
        out.writeInt(children.size());
        for (String child : children) {
            node(out, node.getChildNode(child), path + "/" + child);
        }
    }

    private static void text(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
