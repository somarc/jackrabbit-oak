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
package org.apache.jackrabbit.oak.segment.http.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

final class PeerJsonHttpClient {

    interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    static final class PostResult {
        private final int responseCode;
        private final String responseBody;

        PostResult(int responseCode, String responseBody) {
            this.responseCode = responseCode;
            this.responseBody = responseBody;
        }

        int getResponseCode() {
            return responseCode;
        }

        String getResponseBody() {
            return responseBody;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private final ConnectionFactory connectionFactory;

    PeerJsonHttpClient() {
        this(url -> (HttpURLConnection) url.openConnection());
    }

    PeerJsonHttpClient(ConnectionFactory connectionFactory) {
        this.connectionFactory = connectionFactory;
    }

    PostResult postJson(String targetUrl, String jsonPayload) throws IOException {
        URL url = new URL(targetUrl);
        HttpURLConnection conn = connectionFactory.open(url);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);

        try (OutputStream os = conn.getOutputStream()) {
            byte[] input = jsonPayload.getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
        }

        int responseCode = conn.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                return new PostResult(responseCode, reader.lines().collect(Collectors.joining()));
            }
        }

        return new PostResult(responseCode, "");
    }
}
