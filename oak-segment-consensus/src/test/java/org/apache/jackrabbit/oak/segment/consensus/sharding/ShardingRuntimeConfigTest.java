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
package org.apache.jackrabbit.oak.segment.consensus.sharding;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class ShardingRuntimeConfigTest {

    @Test
    public void testResolveWalletOwnershipFromStaticPrefixSpecs() {
        ShardingRuntimeConfig config = ShardingRuntimeConfig.fromSpecs(
            true,
            "00-1f,80",
            "20-2f=http://cluster-b:8090;aa=http://cluster-c:8090"
        );

        ShardingRuntimeConfig.ResolvedWallet local = config.resolveWallet("0x1100000000000000000000000000000000000000");
        assertEquals(ShardingRuntimeConfig.Ownership.LOCAL, local.getOwnership());
        assertEquals("11", local.getL1Prefix());
        assertNull(local.getRedirectBaseUrl());

        ShardingRuntimeConfig.ResolvedWallet remote = config.resolveWallet("0x2500000000000000000000000000000000000000");
        assertEquals(ShardingRuntimeConfig.Ownership.REMOTE, remote.getOwnership());
        assertEquals("25", remote.getL1Prefix());
        assertEquals("http://cluster-b:8090", remote.getRedirectBaseUrl());
        assertEquals("http://cluster-b:8090/v1/propose-write", remote.buildRedirectUrl("/v1/propose-write"));

        ShardingRuntimeConfig.ResolvedWallet unclaimed = config.resolveWallet("0x9900000000000000000000000000000000000000");
        assertEquals(ShardingRuntimeConfig.Ownership.UNCLAIMED, unclaimed.getOwnership());
        assertEquals("99", unclaimed.getL1Prefix());
        assertNull(unclaimed.getRedirectBaseUrl());
    }
}
