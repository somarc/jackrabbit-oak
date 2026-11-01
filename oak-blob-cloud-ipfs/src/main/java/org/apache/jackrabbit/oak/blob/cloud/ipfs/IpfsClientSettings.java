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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Connection settings for the Kubo RPC API.
 *
 * <p>The endpoint is a multiaddr such as {@code /ip4/127.0.0.1/tcp/5001} or
 * {@code /dns4/ipfs.internal/tcp/443/https}, or an {@code http(s)://} URL. The
 * authorization value is sent unchanged as the {@code Authorization} header,
 * for example {@code Bearer <token>}, and is never included in
 * {@link #toString()}.</p>
 */
record IpfsClientSettings(String endpoint, String authorization, Duration connectTimeout,
                          Duration requestTimeout, int maxAttempts) {

    static final String DEFAULT_ENDPOINT = "/ip4/127.0.0.1/tcp/5001";
    static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    static final int DEFAULT_MAX_ATTEMPTS = 3;
    static final int MAX_MAX_ATTEMPTS = 10;

    static final String ENDPOINT = "ipfsApiEndpoint";
    static final String AUTHORIZATION = "ipfsApiAuthorization";
    static final String CONNECT_TIMEOUT_MILLIS = "ipfsConnectTimeoutMillis";
    static final String REQUEST_TIMEOUT_MILLIS = "ipfsRequestTimeoutMillis";
    static final String MAX_ATTEMPTS = "ipfsMaxAttempts";

    IpfsClientSettings {
        endpoint = blankToNull(endpoint) == null ? DEFAULT_ENDPOINT : endpoint.trim();
        authorization = blankToNull(authorization);
        if (connectTimeout.isNegative() || connectTimeout.isZero()
                || requestTimeout.isNegative() || requestTimeout.isZero()) {
            throw new IllegalArgumentException("IPFS timeouts must be positive");
        }
        if (maxAttempts < 1 || maxAttempts > MAX_MAX_ATTEMPTS) {
            throw new IllegalArgumentException("ipfsMaxAttempts must be between 1 and " + MAX_MAX_ATTEMPTS);
        }
        requireNoControlCharacters(authorization, AUTHORIZATION);
    }

    /**
     * Rejects header values with control characters without echoing them: they
     * are credentials.
     */
    static void requireNoControlCharacters(String value, String name) {
        if (value != null && value.chars().anyMatch(c -> (c < 0x20 && c != '\t') || c == 0x7f)) {
            throw new IllegalArgumentException(name + " contains control characters");
        }
    }

    static IpfsClientSettings of(String endpoint) {
        return new IpfsClientSettings(endpoint, null, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT,
            DEFAULT_MAX_ATTEMPTS);
    }

    /**
     * Reads the settings from DataStore configuration; values may be strings or
     * numbers, as OSGi supplies them.
     */
    static IpfsClientSettings fromProperties(Map<?, ?> properties) {
        return new IpfsClientSettings(
            string(properties, ENDPOINT),
            string(properties, AUTHORIZATION),
            Duration.ofMillis(number(properties, CONNECT_TIMEOUT_MILLIS, DEFAULT_CONNECT_TIMEOUT.toMillis())),
            Duration.ofMillis(number(properties, REQUEST_TIMEOUT_MILLIS, DEFAULT_REQUEST_TIMEOUT.toMillis())),
            attempts(number(properties, MAX_ATTEMPTS, DEFAULT_MAX_ATTEMPTS)));
    }

    IpfsClientSettings withEndpoint(String newEndpoint) {
        return new IpfsClientSettings(newEndpoint, authorization, connectTimeout, requestTimeout, maxAttempts);
    }

    /**
     * Returns the RPC base URI, ending in {@code /api/v0/}; see
     * {@link #serviceBase(String)} for the accepted endpoint forms. A URL that
     * already ends in {@code /api/v0} is accepted as is.
     */
    URI apiBase() {
        String base = serviceBase(endpoint).toString();
        if (base.endsWith("/api/v0/")) {
            return URI.create(base);
        }
        return URI.create(base + "api/v0/");
    }

    /**
     * Returns the base URI of an IPFS service endpoint, ending in {@code /}.
     *
     * <p>Multiaddrs take the forms {@code /<ip4|ip6|dns|dns4|dns6>/<host>/tcp/<port>}
     * followed by nothing or {@code /http} (plain HTTP), or by {@code /https},
     * {@code /tls/http} or {@code /tls/sni/<name>/http} (TLS), optionally ending
     * in {@code /p2p/<peer id>}. URLs must be {@code http} or {@code https}
     * without credentials, query or fragment. Anything else is rejected rather
     * than silently sent over plain HTTP.</p>
     */
    static URI serviceBase(String endpoint) {
        if (endpoint.startsWith("http://") || endpoint.startsWith("https://")) {
            return urlBase(endpoint);
        }
        List<String> parts = new ArrayList<>(List.of(endpoint.split("/", -1)));
        if (parts.size() < 5 || !parts.get(0).isEmpty() || !"tcp".equals(parts.get(3))) {
            throw unsupported(endpoint);
        }
        String protocol = parts.get(1).toLowerCase(Locale.ROOT);
        String host = parts.get(2);
        if (!Set.of("ip4", "ip6", "dns", "dns4", "dns6").contains(protocol) || host.isEmpty()) {
            throw unsupported(endpoint);
        }
        if ("ip6".equals(protocol)) {
            if (host.contains("%")) {
                throw new IllegalArgumentException("IPv6 zone ids are not supported in the IPFS endpoint: " + endpoint);
            }
            host = "[" + host + "]";
        }
        int port = port(endpoint, parts.get(4));
        List<String> rest = parts.subList(5, parts.size());
        if (rest.size() >= 2 && "p2p".equals(rest.get(rest.size() - 2))) {
            rest = rest.subList(0, rest.size() - 2);
        }
        boolean tls;
        if (rest.isEmpty() || rest.equals(List.of("http"))) {
            tls = false;
        } else if (rest.equals(List.of("https")) || rest.equals(List.of("tls", "http"))
                || (rest.size() == 4 && rest.get(0).equals("tls") && rest.get(1).equals("sni")
                    && !rest.get(2).isEmpty() && rest.get(3).equals("http"))) {
            tls = true;
        } else {
            throw unsupported(endpoint);
        }
        return URI.create((tls ? "https" : "http") + "://" + host + ":" + port + "/");
    }

    private static URI urlBase(String endpoint) {
        URI uri = URI.create(endpoint);
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || uri.getHost() == null) {
            throw new IllegalArgumentException(
                "IPFS endpoint URL must not carry credentials, a query or a fragment; configure credentials separately");
        }
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return URI.create(uri.getScheme() + "://" + uri.getRawAuthority() + path + "/");
    }

    private static int port(String endpoint, String value) {
        try {
            int port = Integer.parseInt(value);
            if (port > 0 && port < 65536) {
                return port;
            }
        } catch (NumberFormatException e) {
            // reported below
        }
        throw unsupported(endpoint);
    }

    private static IllegalArgumentException unsupported(String endpoint) {
        return new IllegalArgumentException("Unsupported IPFS endpoint: " + endpoint);
    }

    @Override
    public String toString() {
        return "IpfsClientSettings[endpoint=" + endpoint
            + ", authorization=" + (authorization == null ? "none" : "<redacted>")
            + ", connectTimeout=" + connectTimeout + ", requestTimeout=" + requestTimeout
            + ", maxAttempts=" + maxAttempts + "]";
    }

    private static int attempts(long value) {
        if (value < 1 || value > MAX_MAX_ATTEMPTS) {
            throw new IllegalArgumentException(MAX_ATTEMPTS + " must be between 1 and " + MAX_MAX_ATTEMPTS);
        }
        return (int) value;
    }

    private static String string(Map<?, ?> properties, String key) {
        Object value = properties.get(key);
        return value == null ? null : value.toString();
    }

    private static long number(Map<?, ?> properties, String key, long defaultValue) {
        String value = blankToNull(string(properties, key));
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, not " + value, e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
