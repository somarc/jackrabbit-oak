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
package org.apache.jackrabbit.oak.segment.consensus.aeron;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AeronIngressEndpointPlanner {

    private static final Logger log = LoggerFactory.getLogger(AeronIngressEndpointPlanner.class);

    private final List<String> clusterHostnames;
    private final String clientHostname;
    private final AeronClusterAddressResolver.HostnameResolver hostnameResolver;
    private final AeronClusterAddressResolver.LocalAddressProvider localAddressProvider;

    static AeronIngressEndpointPlanner system(List<String> clusterHostnames, String clientHostname) {
        return new AeronIngressEndpointPlanner(
            clusterHostnames,
            clientHostname,
            hostname -> InetAddress.getByName(hostname).getHostAddress(),
            AeronClusterAddressResolver.systemLocalAddressProvider()
        );
    }

    AeronIngressEndpointPlanner(List<String> clusterHostnames,
                                String clientHostname,
                                AeronClusterAddressResolver.HostnameResolver hostnameResolver,
                                AeronClusterAddressResolver.LocalAddressProvider localAddressProvider) {
        this.clusterHostnames = clusterHostnames;
        this.clientHostname = clientHostname;
        this.hostnameResolver = hostnameResolver;
        this.localAddressProvider = localAddressProvider;
    }

    Plan plan() {
        String validatorSubnet = detectValidatorSubnet();
        StringBuilder ingressEndpoints = new StringBuilder();

        for (int i = 0; i < clusterHostnames.size(); i++) {
            if (i > 0) {
                ingressEndpoints.append(",");
            }
            String hostname = clusterHostnames.get(i);
            String ip = resolveIngressAddress(hostname, validatorSubnet);
            int clientPort = AeronClusterLauncher.calculatePort(i, AeronClusterLauncher.CLIENT_FACING_PORT_OFFSET);
            ingressEndpoints.append(i).append("=").append(ip).append(":").append(clientPort);
            log.info("   Node {} ingress endpoint: {}:{}", i, ip, clientPort);
        }

        String clientIp = resolveClientAddress(validatorSubnet);
        log.info("   Client egress endpoint: {}:0", clientIp);
        return new Plan(ingressEndpoints.toString(), clientIp);
    }

    private String detectValidatorSubnet() {
        for (String hostname : clusterHostnames) {
            if (hostname.equals(clientHostname)) {
                continue;
            }
            try {
                String peerIp = hostnameResolver.resolve(hostname);
                if (peerIp.startsWith("172.")) {
                    String[] parts = peerIp.split("\\.");
                    if (parts.length >= 3) {
                        String subnet = parts[0] + "." + parts[1] + "." + parts[2];
                        log.info("   Detected validator-network subnet: {}.x (from peer {})", subnet, hostname);
                        return subnet;
                    }
                }
            } catch (Exception e) {
                log.debug("Could not detect validator-network subnet from {}: {}", hostname, e.getMessage());
            }
        }
        return null;
    }

    private String resolveIngressAddress(String hostname, String validatorSubnet) {
        return resolveAddress(hostname, validatorSubnet, hostname.equals(clientHostname));
    }

    private String resolveClientAddress(String validatorSubnet) {
        return resolveAddress(clientHostname, validatorSubnet, true);
    }

    private String resolveAddress(String hostname, String validatorSubnet, boolean allowLocalSubnetFallback) {
        try {
            String ip = hostnameResolver.resolve(hostname);
            if (validatorSubnet != null && ip.startsWith(validatorSubnet + ".")) {
                log.debug("   Resolved {} -> {} (validator-network)", hostname, ip);
                return ip;
            }

            if (allowLocalSubnetFallback && validatorSubnet != null) {
                String localSubnetIp = findLocalSubnetAddress(validatorSubnet);
                if (localSubnetIp != null) {
                    log.info("   Resolved {} -> {} (validator-network local interface)", hostname, localSubnetIp);
                    return localSubnetIp;
                }
            }

            log.debug("   Resolved {} -> {} (may not be validator-network)", hostname, ip);
            return ip;
        } catch (UnknownHostException e) {
            log.warn("Failed to resolve hostname {} to IP: {}", hostname, e.getMessage());
            return hostname;
        }
    }

    private String findLocalSubnetAddress(String validatorSubnet) {
        try {
            for (AeronClusterAddressResolver.CandidateAddress candidate : localAddressProvider.listCandidateAddresses()) {
                String candidateIp = candidate.getIpAddress();
                if (candidateIp.startsWith(validatorSubnet + ".")) {
                    return candidateIp;
                }
            }
        } catch (Exception e) {
            log.debug("Interface enumeration failed: {}", e.getMessage());
        }
        return null;
    }

    static final class Plan {
        final String ingressEndpoints;
        final String clientIp;

        Plan(String ingressEndpoints, String clientIp) {
            this.ingressEndpoints = ingressEndpoints;
            this.clientIp = clientIp;
        }
    }
}
