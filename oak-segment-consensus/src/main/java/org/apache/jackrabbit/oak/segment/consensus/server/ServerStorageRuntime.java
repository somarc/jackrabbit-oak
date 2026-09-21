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
package org.apache.jackrabbit.oak.segment.consensus.server;

import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

import java.io.Closeable;

final class ServerStorageRuntime {
    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final NodeStore readViewNodeStore;
    private final Closeable readViewResources;

    ServerStorageRuntime(FileStore fileStore, NodeStore nodeStore) {
        this(fileStore, nodeStore, nodeStore, null);
    }

    ServerStorageRuntime(FileStore fileStore, NodeStore nodeStore, NodeStore readViewNodeStore) {
        this(fileStore, nodeStore, readViewNodeStore, null);
    }

    ServerStorageRuntime(FileStore fileStore,
                         NodeStore nodeStore,
                         NodeStore readViewNodeStore,
                         Closeable readViewResources) {
        this.fileStore = fileStore;
        this.nodeStore = nodeStore;
        this.readViewNodeStore = readViewNodeStore;
        this.readViewResources = readViewResources;
    }

    FileStore getFileStore() {
        return fileStore;
    }

    NodeStore getNodeStore() {
        return nodeStore;
    }

    NodeStore getAuthoritativeNodeStore() {
        return nodeStore;
    }

    NodeStore getReadViewNodeStore() {
        return readViewNodeStore;
    }

    Closeable getReadViewResources() {
        return readViewResources;
    }
}
