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

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

final class BootstrapPreflightPlanner {

    private static final int DEFAULT_BOOTSTRAP_HTTP_PORT = 8090;

    private final PeerHealthProbe peerHealthProbe;

    BootstrapPreflightPlanner() {
        this(BootstrapPreflightPlanner::isHealthEndpointReachable);
    }

    BootstrapPreflightPlanner(PeerHealthProbe peerHealthProbe) {
        this.peerHealthProbe = peerHealthProbe;
    }

    Decision plan(boolean isAeronMode,
                  boolean directoryIsEmpty,
                  List<String> aeronPeers,
                  String bootstrapPrimaryHost,
                  String bootstrapPrimaryPort,
                  boolean standbyBootstrapEnabled,
                  int fallbackStandbyPort) {
        if (!isAeronMode || !directoryIsEmpty) {
            return Decision.notRequired();
        }

        String verifiedHost = "";
        int verifiedPort = 0;
        boolean hasVerifiedReachablePeers = false;

        if (aeronPeers != null) {
            for (String peerUrl : aeronPeers) {
                try {
                    URL url = new URL(peerUrl);
                    if (peerHealthProbe.isReachable(peerUrl + "/health")) {
                        hasVerifiedReachablePeers = true;
                        verifiedHost = url.getHost();
                        verifiedPort = url.getPort() > 0 ? url.getPort() + 1 : fallbackStandbyPort;
                        break;
                    }
                } catch (Exception e) {
                    // Ignore malformed peer URLs during reachability preflight.
                }
            }
        }

        if (!hasVerifiedReachablePeers && bootstrapPrimaryHost != null && !bootstrapPrimaryHost.isEmpty()) {
            int standbyPort = parsePortOrDefault(bootstrapPrimaryPort, DEFAULT_BOOTSTRAP_HTTP_PORT + 1);
            int httpPort = standbyPort - 1;
            String healthUrl = "http://" + bootstrapPrimaryHost + ":" + httpPort + "/health";
            if (peerHealthProbe.isReachable(healthUrl)) {
                hasVerifiedReachablePeers = true;
                verifiedHost = bootstrapPrimaryHost;
                verifiedPort = standbyPort;
            }
        }

        return new Decision(
            hasVerifiedReachablePeers && standbyBootstrapEnabled,
            hasVerifiedReachablePeers,
            verifiedHost,
            verifiedPort
        );
    }

    private static int parsePortOrDefault(String value, int defaultValue) {
        if (value == null || value.trim().isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private static boolean isHealthEndpointReachable(String healthUrl) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(healthUrl).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            return connection.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    interface PeerHealthProbe {
        boolean isReachable(String healthUrl);
    }

    static final class Decision {
        private final boolean needsBootstrapBeforeBuild;
        private final boolean hasVerifiedReachablePeers;
        private final String verifiedBootstrapPrimaryHost;
        private final int verifiedBootstrapPrimaryPort;

        private Decision(boolean needsBootstrapBeforeBuild,
                         boolean hasVerifiedReachablePeers,
                         String verifiedBootstrapPrimaryHost,
                         int verifiedBootstrapPrimaryPort) {
            this.needsBootstrapBeforeBuild = needsBootstrapBeforeBuild;
            this.hasVerifiedReachablePeers = hasVerifiedReachablePeers;
            this.verifiedBootstrapPrimaryHost = verifiedBootstrapPrimaryHost;
            this.verifiedBootstrapPrimaryPort = verifiedBootstrapPrimaryPort;
        }

        static Decision notRequired() {
            return new Decision(false, false, "", 0);
        }

        boolean needsBootstrapBeforeBuild() {
            return needsBootstrapBeforeBuild;
        }

        boolean hasVerifiedReachablePeers() {
            return hasVerifiedReachablePeers;
        }

        String verifiedBootstrapPrimaryHost() {
            return verifiedBootstrapPrimaryHost;
        }

        int verifiedBootstrapPrimaryPort() {
            return verifiedBootstrapPrimaryPort;
        }
    }
}
