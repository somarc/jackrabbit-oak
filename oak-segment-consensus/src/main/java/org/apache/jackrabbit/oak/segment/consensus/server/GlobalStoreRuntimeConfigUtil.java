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

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;

import java.util.ArrayList;
import java.util.List;

final class GlobalStoreRuntimeConfigUtil {

    private GlobalStoreRuntimeConfigUtil() {
    }

    static String resolveNodeKeystorePath(String storeDirectory) {
        return RuntimeConfigValueResolver.readString(
            "wallet.keystore.path",
            storeDirectory + "/validator-keystore.properties"
        );
    }

    static boolean isConfiguredSelfUrl(AeronClusterConfig config) {
        if (config != null && config.selfUrl() != null && !config.selfUrl().trim().isEmpty()) {
            return true;
        }
        return RuntimeConfigValueResolver.hasConfiguredValue("consensus.self.url");
    }

    static String resolveSelfUrl(int port, AeronClusterConfig config) {
        if (config != null && config.selfUrl() != null && !config.selfUrl().trim().isEmpty()) {
            return config.selfUrl().trim();
        }
        String configured = RuntimeConfigValueResolver.readString("consensus.self.url", null);
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return ServerNetworkUtil.resolveUrlToIP("http://localhost:" + port);
    }

    static List<String> resolvePeerUrls(AeronClusterConfig config) {
        if (config != null && config.peerUrls() != null && config.peerUrls().length > 0) {
            List<String> peerUrls = new ArrayList<>();
            for (String peerUrl : config.peerUrls()) {
                if (peerUrl != null && !peerUrl.trim().isEmpty()) {
                    peerUrls.add(peerUrl.trim());
                }
            }
            if (!peerUrls.isEmpty()) {
                return peerUrls;
            }
        }
        return ServerNetworkUtil.parsePeerUrls(RuntimeConfigValueResolver.readString("consensus.peers", ""));
    }

    static String resolveBeaconApiUrl(AeronClusterConfig config) {
        if (config != null && config.beaconApiUrl() != null && !config.beaconApiUrl().trim().isEmpty()) {
            return config.beaconApiUrl().trim();
        }
        return RuntimeConfigValueResolver.readString("ethereum.beacon.api.url", "https://beaconcha.in/api");
    }
}
