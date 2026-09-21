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
package org.apache.jackrabbit.oak.segment.consensus.mount;

import org.apache.jackrabbit.oak.plugins.memory.MemoryNodeStore;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingRuntimeConfig;
import org.apache.jackrabbit.oak.spi.commit.CommitInfo;
import org.apache.jackrabbit.oak.spi.commit.EmptyHook;
import org.apache.jackrabbit.oak.spi.state.NodeBuilder;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import java.io.Closeable;
import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ValidatorReadViewBuilderTest {

    @Test
    public void testBuildComposesLocalAuthoritativeAndRemoteReadOnlyPrefixes() throws Exception {
        MemoryNodeStore localStore = new MemoryNodeStore();
        seedPath(localStore, "/oak-chain/aa/local-wallet/content/doc-1");

        CloseableMemoryNodeStore remoteStore = new CloseableMemoryNodeStore();
        seedPath(remoteStore, "/oak-chain/10/remote-wallet/content/doc-2");
        seedPath(remoteStore, "/oak-chain/11/remote-wallet/content/doc-3");

        ValidatorReadViewBuilder builder = new ValidatorReadViewBuilder(
            (endpoint, mountName) -> remoteStore
        );

        ValidatorReadViewBuilder.BuildResult result = builder.build(
            localStore,
            ShardingRuntimeConfig.fromSpecs(true, "80-ff", "10-11=http://cluster-a:8090")
        );

        NodeState root = result.getReadViewNodeStore().getRoot();
        assertTrue(exists(root, "/oak-chain/aa/local-wallet/content/doc-1"));
        assertTrue(exists(root, "/oak-chain/10/remote-wallet/content/doc-2"));
        assertTrue(exists(root, "/oak-chain/11/remote-wallet/content/doc-3"));
        assertEquals(2, result.getRemoteMountCount());

        result.close();
        assertTrue(remoteStore.closed);
    }

    @Test
    public void testBuildReturnsLocalStoreWhenShardingDisabled() {
        MemoryNodeStore localStore = new MemoryNodeStore();
        ValidatorReadViewBuilder builder = new ValidatorReadViewBuilder();

        ValidatorReadViewBuilder.BuildResult result = builder.build(localStore, ShardingRuntimeConfig.disabled());

        assertTrue(result.getReadViewNodeStore() == localStore);
        assertEquals(0, result.getRemoteMountCount());
    }

    private static void seedPath(NodeStore nodeStore, String path) throws Exception {
        String[] parts = path.substring(1).split("/");
        NodeBuilder rootBuilder = nodeStore.getRoot().builder();
        NodeBuilder current = rootBuilder;
        for (String part : parts) {
            current = current.child(part);
        }
        current.setProperty("path", path);
        nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, CommitInfo.EMPTY);
    }

    private static boolean exists(NodeState root, String path) {
        NodeState current = root;
        for (String part : path.substring(1).split("/")) {
            current = current.getChildNode(part);
            if (!current.exists()) {
                return false;
            }
        }
        return current.exists();
    }

    private static final class CloseableMemoryNodeStore extends MemoryNodeStore implements Closeable {
        private boolean closed;

        @Override
        public void close() throws IOException {
            closed = true;
        }
    }
}
