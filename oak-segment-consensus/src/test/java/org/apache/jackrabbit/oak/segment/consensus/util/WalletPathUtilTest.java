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
package org.apache.jackrabbit.oak.segment.consensus.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class WalletPathUtilTest {

    private static final String WALLET = "0x742d35cc6634c0532925a3b844bc9e7595f0beb";

    @Test
    public void testShardLevelsAndRoot() {
        String[] levels = WalletPathUtil.getShardLevels(WALLET);
        assertArrayEquals(new String[] { "74", "2d", "35" }, levels);

        String root = WalletPathUtil.getShardRoot(WALLET);
        assertEquals("/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb", root);
    }

    @Test
    public void testContentPathWithOrganization() {
        String path = WalletPathUtil.getContentPath(WALLET, "MyBrand");
        assertEquals("/oak-chain/74/2d/35/0x742d35cc6634c0532925a3b844bc9e7595f0beb/MyBrand/content", path);
    }

    @Test
    public void testValidateOrganization() {
        assertNull(WalletPathUtil.validateOrganization(null));
        assertNull(WalletPathUtil.validateOrganization("Brand_1"));
        assertNotNull(WalletPathUtil.validateOrganization("bad/name"));
        assertNotNull(WalletPathUtil.validateOrganization("bad name"));
    }

    @Test
    public void testExtractShardIdAndIsShardedWalletPath() {
        String path = "/oak-chain/74-2d-35/content";
        assertEquals("74-2d-35", WalletPathUtil.extractShardId(path));
        assertTrue(WalletPathUtil.isShardedWalletPath(path));
        assertFalse(WalletPathUtil.isShardedWalletPath("/content/site"));
    }

    @Test
    public void testEstimateWalletsPerBucket() {
        int estimate = WalletPathUtil.estimateWalletsPerBucket(100_000_000L);
        assertTrue(estimate >= 0);
    }
}
