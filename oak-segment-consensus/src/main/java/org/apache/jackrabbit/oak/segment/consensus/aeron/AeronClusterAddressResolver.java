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

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class AeronClusterAddressResolver {

    private static final Logger log = LoggerFactory.getLogger(AeronClusterAddressResolver.class);

    private static final int MAX_RETRIES = 20;
    private static final int INITIAL_RETRY_DELAY_MS = 1000;

    private final int nodeId;
    private final List<String> hostnames;
    private final HostnameResolver hostnameResolver;
    private final LocalAddressProvider localAddressProvider;
    private final Sleeper sleeper;

    static AeronClusterAddressResolver system(int nodeId, List<String> hostnames) {
        return new AeronClusterAddressResolver(
            nodeId,
            hostnames,
            hostname -> InetAddress.getByName(hostname).getHostAddress(),
            systemLocalAddressProvider(),
            Thread::sleep
        );
    }

    static LocalAddressProvider systemLocalAddressProvider() {
        return new SystemLocalAddressProvider();
    }

    AeronClusterAddressResolver(int nodeId,
                                List<String> hostnames,
                                HostnameResolver hostnameResolver,
                                LocalAddressProvider localAddressProvider,
                                Sleeper sleeper) {
        this.nodeId = nodeId;
        this.hostnames = hostnames;
        this.hostnameResolver = hostnameResolver;
        this.localAddressProvider = localAddressProvider;
        this.sleeper = sleeper;
    }

    String resolveRequiredHostname(String hostname) {
        int retryDelayMs = INITIAL_RETRY_DELAY_MS;

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                String ip = hostnameResolver.resolve(hostname);
                if (attempt > 1) {
                    log.info("✅ Resolved hostname {} to IP {} (attempt {}/{})", hostname, ip, attempt, MAX_RETRIES);
                } else {
                    log.debug("✅ Resolved hostname {} to IP {}", hostname, ip);
                }
                return ip;
            } catch (UnknownHostException e) {
                if (attempt < MAX_RETRIES) {
                    if (attempt <= 3 || attempt % 5 == 0) {
                        log.debug(
                            "⚠️  Failed to resolve hostname {} (attempt {}/{}), retrying in {}ms...",
                            hostname,
                            attempt,
                            MAX_RETRIES,
                            retryDelayMs
                        );
                    }
                    try {
                        sleeper.sleep(retryDelayMs);
                        retryDelayMs = Math.min(retryDelayMs * 2, 10000);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("IP resolution interrupted", interrupted);
                    }
                } else {
                    log.warn(
                        "⚠️  Failed to resolve hostname: {} after {} attempts",
                        hostname,
                        MAX_RETRIES
                    );
                    throw new RuntimeException(
                        "Failed to resolve hostname: " + hostname + " after " + MAX_RETRIES + " attempts",
                        e
                    );
                }
            }
        }

        throw new RuntimeException("Should not reach here");
    }

    String resolveLocalNodeAddress(String selfHostname) {
        String peerSubnet = resolvePeerSubnet();

        try {
            List<CandidateAddress> candidates = new ArrayList<>();
            for (CandidateAddress candidate : localAddressProvider.listCandidateAddresses()) {
                String ip = candidate.getIpAddress();
                if (!ip.startsWith("172.")) {
                    continue;
                }
                if (peerSubnet != null && ip.startsWith(peerSubnet + ".")) {
                    log.info(
                        "   ✅ Found validator-network IP: {} (interface: {}, matches peer subnet)",
                        ip,
                        candidate.getInterfaceName()
                    );
                    return ip;
                }
                candidates.add(candidate);
                log.debug("   Found candidate IP: {} (interface: {})", ip, candidate.getInterfaceName());
            }

            if (!candidates.isEmpty()) {
                CandidateAddress selected = candidates.get(0);
                log.info(
                    "   Using network interface IP: {} (interface: {}, {} candidates found)",
                    selected.getIpAddress(),
                    selected.getInterfaceName(),
                    candidates.size()
                );
                return selected.getIpAddress();
            }
        } catch (Exception e) {
            log.warn("Failed to enumerate network interfaces: {}", e.getMessage());
        }

        try {
            return resolveRequiredHostname(selfHostname);
        } catch (Exception e) {
            log.error("❌ CRITICAL: Failed to determine IP address for Aeron Cluster", e);
            throw new RuntimeException("Cannot determine IP address for Aeron Cluster", e);
        }
    }

    List<String> resolveClusterMemberAddresses(String myIPAddress) {
        List<String> addresses = new ArrayList<>();
        log.info("🌐 Resolving {} hostnames to IP addresses (P2P-organic, retry logic)...", hostnames.size());

        int resolved = 0;
        int failed = 0;

        for (int i = 0; i < hostnames.size(); i++) {
            String hostname = hostnames.get(i);
            if (i == nodeId) {
                addresses.add(myIPAddress);
                resolved++;
                log.info("   ✅ Self (node {}): {} → {} (validator-network IP)", i, hostname, myIPAddress);
                continue;
            }

            try {
                String ip = resolveRequiredHostname(hostname);
                addresses.add(ip);
                resolved++;
                log.info("   ✅ Peer (node {}): {} → {}", i, hostname, ip);
            } catch (RuntimeException e) {
                log.warn(
                    "   ⚠️  Peer (node {}) not resolvable yet: {} - using hostname (Aeron will retry DNS)",
                    i,
                    hostname
                );
                log.warn("      This is normal in P2P startup - Aeron Cluster will retry DNS resolution periodically");
                addresses.add(hostname);
                failed++;
            }
        }

        log.info(
            "✅ Resolved {}/{} hostnames to IP addresses ({} pending peer startup)",
            resolved,
            hostnames.size(),
            failed
        );
        if (failed > 0) {
            log.info("🌐 P2P Mode: {} peer(s) will connect when they come online", failed);
        }

        return addresses;
    }

    private String resolvePeerSubnet() {
        if (hostnames.size() <= 1) {
            return null;
        }

        for (int i = 0; i < hostnames.size(); i++) {
            if (i == nodeId) {
                continue;
            }
            try {
                String peerIp = resolveRequiredHostname(hostnames.get(i));
                if (peerIp != null && peerIp.startsWith("172.")) {
                    String[] parts = peerIp.split("\\.");
                    if (parts.length >= 3) {
                        String subnet = parts[0] + "." + parts[1] + "." + parts[2];
                        log.info("   Detected validator-network subnet: {}.x (from peer {})", subnet, hostnames.get(i));
                        return subnet;
                    }
                }
            } catch (Exception e) {
                // Peer not resolvable yet.
            }
        }
        return null;
    }

    interface HostnameResolver {
        String resolve(String hostname) throws UnknownHostException;
    }

    interface LocalAddressProvider {
        List<CandidateAddress> listCandidateAddresses() throws Exception;
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    static final class CandidateAddress {
        private final String interfaceName;
        private final String ipAddress;

        CandidateAddress(String interfaceName, String ipAddress) {
            this.interfaceName = interfaceName;
            this.ipAddress = ipAddress;
        }

        String getInterfaceName() {
            return interfaceName;
        }

        String getIpAddress() {
            return ipAddress;
        }
    }

    private static final class SystemLocalAddressProvider implements LocalAddressProvider {
        @Override
        public List<CandidateAddress> listCandidateAddresses() throws SocketException {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return Collections.emptyList();
            }

            List<CandidateAddress> candidates = new ArrayList<>();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        candidates.add(new CandidateAddress(iface.getName(), address.getHostAddress()));
                    }
                }
            }
            return candidates;
        }
    }
}
