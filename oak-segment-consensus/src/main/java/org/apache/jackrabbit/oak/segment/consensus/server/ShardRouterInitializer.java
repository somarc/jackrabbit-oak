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

import java.util.ArrayList;
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardDirectory;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardRouter;
import org.apache.jackrabbit.oak.segment.consensus.sharding.ShardingStrategy;
import org.apache.jackrabbit.oak.segment.consensus.sharding.WalletShardingStrategy;
import org.apache.jackrabbit.oak.segment.http.server.SegmentHttpServer;

final class ShardRouterInitializer {

    void initialize(SegmentHttpServer httpServer, String selfUrl, List<String> peerUrls, boolean logClusterStateDetails) {
        initialize(
            httpServer,
            selfUrl,
            peerUrls,
            logClusterStateDetails,
            System.getProperty("sharding.numShards", System.getenv("NUM_SHARDS"))
        );
    }

    void initialize(SegmentHttpServer httpServer,
                    String selfUrl,
                    List<String> peerUrls,
                    boolean logClusterStateDetails,
                    String numShardsConfig) {
        int numShards = resolveNumShards(numShardsConfig);

        List<String> allPeerUrls = new ArrayList<>();
        allPeerUrls.add(selfUrl);
        if (peerUrls != null) {
            allPeerUrls.addAll(peerUrls);
        }

        ShardDirectory shardDirectory = new ShardDirectory(allPeerUrls);
        ShardingStrategy shardingStrategy = new WalletShardingStrategy(numShards);
        ShardRouter shardRouter = new ShardRouter(shardDirectory, shardingStrategy);

        httpServer.getContext().setShardRouter(shardRouter);

        System.out.println("✅ Shard Router initialized");
        System.out.println("   - Number of shards: " + numShards);
        System.out.println("   - Shard directory: " + shardDirectory.getNumShards() + " shard(s)");
        System.out.println("   - Sharding strategy: Wallet-based");
        if (shardingStrategy instanceof WalletShardingStrategy
            && ((WalletShardingStrategy) shardingStrategy).isPowerOfTwo()) {
            System.out.println("   - Power-of-2: Yes (optimal)");
        } else if (logClusterStateDetails) {
            System.out.println("   - Power-of-2: No (consider using power-of-2 for optimal performance)");
        }
    }

    int resolveNumShards(String numShardsConfig) {
        int numShards = 1;
        if (numShardsConfig != null && !numShardsConfig.isEmpty()) {
            try {
                numShards = Integer.parseInt(numShardsConfig);
                if (numShards <= 0) {
                    System.err.println("⚠️  Invalid NUM_SHARDS: " + numShardsConfig + ", using default: 1");
                    numShards = 1;
                }
            } catch (NumberFormatException e) {
                System.err.println("⚠️  Invalid NUM_SHARDS format: " + numShardsConfig + ", using default: 1");
                numShards = 1;
            }
        }
        return numShards;
    }
}
