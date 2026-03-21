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

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

final class AeronClusterBootstrapPlan {

    enum StartupMode {
        EXISTING_CLUSTER,
        FRESH_CONFIGURED,
        FRESH_SELF_ONLY
    }

    final File clusterBaseDir;
    final File clusterDir;
    final boolean clusterDirExists;
    final int clusterDirFileCount;
    final boolean hasExistingCluster;
    final StartupMode startupMode;
    final List<String> hostnames;
    final Map<Integer, String> nodeIdToUrl;
    final String clientHostname;

    private AeronClusterBootstrapPlan(File clusterBaseDir,
                                      File clusterDir,
                                      boolean clusterDirExists,
                                      int clusterDirFileCount,
                                      boolean hasExistingCluster,
                                      StartupMode startupMode,
                                      List<String> hostnames,
                                      Map<Integer, String> nodeIdToUrl,
                                      String clientHostname) {
        this.clusterBaseDir = clusterBaseDir;
        this.clusterDir = clusterDir;
        this.clusterDirExists = clusterDirExists;
        this.clusterDirFileCount = clusterDirFileCount;
        this.hasExistingCluster = hasExistingCluster;
        this.startupMode = startupMode;
        this.hostnames = hostnames;
        this.nodeIdToUrl = nodeIdToUrl;
        this.clientHostname = clientHostname;
    }

    static AeronClusterBootstrapPlan create(int nodeId,
                                            String selfUrl,
                                            List<String> peerUrls,
                                            String storeDirectory,
                                            String hostnamesConfig) {
        File clusterBaseDir = new File(storeDirectory, "aeron-cluster-node-" + nodeId);
        File clusterDir = new File(clusterBaseDir, "cluster");
        boolean clusterDirExists = clusterDir.exists();
        int clusterDirFileCount = clusterDirExists && clusterDir.listFiles() != null ? clusterDir.listFiles().length : 0;
        boolean hasExistingCluster = clusterDirExists && clusterDirFileCount > 0;

        List<String> configuredHostnames = parseConfiguredHostnames(hostnamesConfig);
        StartupMode startupMode;
        List<String> hostnames;
        if (hasExistingCluster) {
            startupMode = StartupMode.EXISTING_CLUSTER;
            hostnames = configuredHostnames.isEmpty()
                ? discoverHostnames(selfUrl, peerUrls)
                : configuredHostnames;
        } else if (!configuredHostnames.isEmpty()) {
            startupMode = StartupMode.FRESH_CONFIGURED;
            hostnames = configuredHostnames;
        } else {
            startupMode = StartupMode.FRESH_SELF_ONLY;
            hostnames = new ArrayList<>();
            hostnames.add(ServerNetworkUtil.extractHostname(selfUrl));
        }

        return new AeronClusterBootstrapPlan(
            clusterBaseDir,
            clusterDir,
            clusterDirExists,
            clusterDirFileCount,
            hasExistingCluster,
            startupMode,
            hostnames,
            buildNodeIdMapping(selfUrl, peerUrls),
            resolveClientHostname(selfUrl)
        );
    }

    private static List<String> parseConfiguredHostnames(String hostnamesConfig) {
        if (hostnamesConfig == null || hostnamesConfig.isEmpty()) {
            return new ArrayList<>();
        }
        return Arrays.stream(hostnamesConfig.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private static List<String> discoverHostnames(String selfUrl, List<String> peerUrls) {
        Set<String> hostnames = new LinkedHashSet<>();
        hostnames.add(ServerNetworkUtil.extractHostname(selfUrl));
        for (String peerUrl : peerUrls) {
            hostnames.add(ServerNetworkUtil.extractHostname(peerUrl));
        }
        return new ArrayList<>(hostnames);
    }

    private static Map<Integer, String> buildNodeIdMapping(String selfUrl, List<String> peerUrls) {
        Map<Integer, String> nodeIdToUrl = new HashMap<>();
        List<String> allUrls = new ArrayList<>();
        allUrls.add(selfUrl);
        allUrls.addAll(peerUrls);
        allUrls.sort((a, b) -> {
            int portA = extractPort(a);
            int portB = extractPort(b);
            if (portA != portB) {
                return Integer.compare(portA, portB);
            }
            return a.compareTo(b);
        });
        for (int i = 0; i < allUrls.size(); i++) {
            nodeIdToUrl.put(i, allUrls.get(i));
        }
        return nodeIdToUrl;
    }

    private static String resolveClientHostname(String selfUrl) {
        try {
            return new URL(selfUrl).getHost();
        } catch (Exception e) {
            return "localhost";
        }
    }

    private static int extractPort(String url) {
        try {
            return new URL(url).getPort();
        } catch (Exception e) {
            return -1;
        }
    }
}
