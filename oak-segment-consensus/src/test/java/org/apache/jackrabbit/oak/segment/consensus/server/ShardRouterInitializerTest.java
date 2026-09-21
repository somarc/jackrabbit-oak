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

import java.nio.file.Paths;
import java.util.Arrays;

import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardRouter;
import org.apache.jackrabbit.oak.segment.consensus.sharding.WalletShardingStrategy;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class ShardRouterInitializerTest {

    @Test
    public void initializeDefaultsToSingleShardWhenConfigMissing() {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("target"),
            "http://node-a:8080"
        );
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        when(httpServer.getContext()).thenReturn(context);

        new ShardRouterInitializer().initialize(
            httpServer,
            "http://node-a:8080",
            Arrays.asList("http://node-b:8081"),
            false,
            null
        );

        ShardRouter shardRouter = context.shardRouter;
        assertNotNull(shardRouter);
        assertEquals(1, ((WalletShardingStrategy) shardRouter.getStrategy()).getNumShards());
        assertEquals(1, shardRouter.getShardDirectory().getNumShards());
        assertEquals(
            Arrays.asList("http://node-a:8080", "http://node-b:8081"),
            shardRouter.getShardDirectory().getShard(0).getPeerUrls()
        );
    }

    @Test
    public void initializeUsesConfiguredShardCount() {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("target"),
            "http://node-a:8080"
        );
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        when(httpServer.getContext()).thenReturn(context);

        new ShardRouterInitializer().initialize(
            httpServer,
            "http://node-a:8080",
            Arrays.asList("http://node-b:8081", "http://node-c:8082"),
            true,
            "4"
        );

        ShardRouter shardRouter = context.shardRouter;
        assertNotNull(shardRouter);
        assertEquals(4, ((WalletShardingStrategy) shardRouter.getStrategy()).getNumShards());
        assertTrue(((WalletShardingStrategy) shardRouter.getStrategy()).isPowerOfTwo());
        assertEquals(1, shardRouter.getShardDirectory().getNumShards());
    }

    @Test
    public void initializeFallsBackToSingleShardForInvalidConfig() {
        ServerContext context = new ServerContext(
            mock(FileStore.class),
            mock(NodeStore.class),
            Paths.get("target"),
            "http://node-a:8080"
        );
        SegmentHttpServer httpServer = mock(SegmentHttpServer.class);
        when(httpServer.getContext()).thenReturn(context);

        ShardRouterInitializer initializer = new ShardRouterInitializer();
        assertEquals(1, initializer.resolveNumShards("not-a-number"));
        assertEquals(1, initializer.resolveNumShards("0"));
        assertEquals(1, initializer.resolveNumShards("-3"));

        initializer.initialize(
            httpServer,
            "http://node-a:8080",
            Arrays.asList("http://node-b:8081"),
            false,
            "not-a-number"
        );

        assertEquals(1, ((WalletShardingStrategy) context.shardRouter.getStrategy()).getNumShards());
    }
}
