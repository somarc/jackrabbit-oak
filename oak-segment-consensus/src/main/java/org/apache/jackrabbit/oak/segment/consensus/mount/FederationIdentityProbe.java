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
package org.apache.jackrabbit.oak.segment.consensus.mount;

import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

/**
 * Confirms that an endpoint serves the cluster the directory lists for it: {@code GET {endpoint}/v1/federation}
 * must report the entry's enterprise as its committed charter and, when the entry pins one, its cluster id.
 */
final class FederationIdentityProbe implements FederatedMountStore.IdentityProbe {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    private final HttpClient client;
    private final Duration requestTimeout;

    FederationIdentityProbe() {
        this(CONNECT_TIMEOUT, REQUEST_TIMEOUT);
    }

    FederationIdentityProbe(Duration connectTimeout, Duration requestTimeout) {
        this.client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        this.requestTimeout = requestTimeout;
    }

    @Override
    public void verify(@NotNull String endpoint, @NotNull String enterpriseId, @NotNull String clusterId)
            throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint + "/v1/federation"))
            .timeout(requestTimeout)
            .GET()
            .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("identity check interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new IOException("identity check returned HTTP " + response.statusCode());
        }
        Map<String, Object> body;
        try {
            body = JsonParser.parseObject(response.body());
        } catch (RuntimeException e) {
            throw new IOException("identity check returned malformed JSON", e);
        }
        check(body, enterpriseId, clusterId);
    }

    static void check(Map<String, Object> body, String enterpriseId, String clusterId) throws IOException {
        Object charter = body.get("charter");
        Object served = charter instanceof Map ? ((Map<?, ?>) charter).get("enterpriseId") : null;
        if (!(served instanceof String)) {
            throw new IOException("identity mismatch: endpoint has no committed charter");
        }
        if (!enterpriseId.equals(((String) served).toLowerCase(Locale.ROOT))) {
            throw new IOException("identity mismatch: endpoint serves enterprise " + served
                + ", directory lists " + enterpriseId);
        }
        Object servedCluster = body.get("clusterId");
        if (!clusterId.isEmpty() && !(servedCluster instanceof String
            && clusterId.equals(((String) servedCluster).toLowerCase(Locale.ROOT)))) {
            throw new IOException("identity mismatch: endpoint reports cluster " + servedCluster
                + ", directory lists " + clusterId);
        }
    }
}
