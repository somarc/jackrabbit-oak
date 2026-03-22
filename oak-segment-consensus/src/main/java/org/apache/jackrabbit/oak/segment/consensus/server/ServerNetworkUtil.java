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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class ServerNetworkUtil {

    private static final Logger log = LoggerFactory.getLogger(ServerNetworkUtil.class);

    private ServerNetworkUtil() {
    }

    /**
     * Resolve hostname-based URL to IP-based URL for reliable networking.
     *
     * <p>If the URL contains a hostname (not an IP), resolves it to an IP address.
     * This ensures reliable networking in Docker environments where DNS can be unreliable.
     *
     * <p>For production deployments (ngrok, cloud environments), set `consensus.self.url`
     * system property to override this behavior.
     *
     * @param url URL with hostname (e.g., "http://localhost:8090" or "http://validator-1:8090")
     * @return URL with IP address (e.g., "http://127.0.0.1:8090" or "http://172.18.0.2:8090")
     */
    static String resolveUrlToIP(String url) {
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            String hostname = parsedUrl.getHost();
            int port = parsedUrl.getPort();
            String protocol = parsedUrl.getProtocol();
            String path = parsedUrl.getPath();

            // If already an IP address, return as-is
            if (hostname.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                return url;
            }

            // Resolve hostname to IP
            try {
                String ip = java.net.InetAddress.getByName(hostname).getHostAddress();
                return String.format("%s://%s%s%s",
                    protocol,
                    ip,
                    port != -1 ? ":" + port : "",
                    path != null ? path : "");
            } catch (java.net.UnknownHostException e) {
                // If resolution fails, return original URL (may be ngrok/Ethos URL)
                log.warn("⚠️  Could not resolve hostname {} to IP, using original URL", hostname);
                return url;
            }
        } catch (Exception e) {
            log.warn("⚠️  Failed to parse URL {}: {}, using original", url, e.getMessage());
            return url;
        }
    }

    /**
     * Parse comma-separated peer URLs from configuration string.
     *
     * <p>Peer URLs are resolved to IPs for reliable networking, unless they're
     * explicitly configured as public URLs (ngrok/Ethos).
     */
    static List<String> parsePeerUrls(String peersConfig) {
        List<String> peers = new ArrayList<>();
        if (peersConfig != null && !peersConfig.trim().isEmpty()) {
            String[] urls = peersConfig.split(",");
            for (String url : urls) {
                String trimmed = url.trim();
                if (!trimmed.isEmpty()) {
                    peers.add(resolveUrlToIP(trimmed));
                }
            }
        }
        return peers;
    }

    /**
     * Extract hostname from URL (e.g., "http://validator-1:8090" -> "validator-1").
     */
    static String extractHostname(String url) {
        try {
            java.net.URL parsedUrl = new java.net.URL(url);
            return parsedUrl.getHost();
        } catch (Exception e) {
            // Fallback: try to extract from URL string
            if (url.contains("://")) {
                String withoutProtocol = url.substring(url.indexOf("://") + 3);
                if (withoutProtocol.contains(":")) {
                    return withoutProtocol.substring(0, withoutProtocol.indexOf(":"));
                }
                return withoutProtocol;
            }
            return "localhost";
        }
    }
}
