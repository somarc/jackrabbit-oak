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

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PeerJsonHttpClientTest {

    @Test
    public void testPostJsonWritesPayloadAndReturnsResponseBody() throws Exception {
        FakeHttpURLConnection connection = new FakeHttpURLConnection(new URL("http://validator-2:8090"), 200, "{\"ok\":true}");
        PeerJsonHttpClient client = new PeerJsonHttpClient(url -> connection);

        PeerJsonHttpClient.PostResult result = client.postJson("http://validator-2:8090/v1/register-validator", "{\"validatorId\":\"validator-1\"}");

        assertEquals(200, result.getResponseCode());
        assertEquals("{\"ok\":true}", result.getResponseBody());
        assertEquals("POST", connection.method);
        assertEquals("application/json", connection.requestProperties.get("Content-Type"));
        assertTrue(connection.doOutput);
        assertEquals(5000, connection.connectTimeout);
        assertEquals(10000, connection.readTimeout);
        assertEquals("{\"validatorId\":\"validator-1\"}", connection.getWrittenBody());
    }

    @Test
    public void testPostJsonReturnsEmptyBodyForNonSuccessResponses() throws Exception {
        FakeHttpURLConnection connection = new FakeHttpURLConnection(new URL("http://validator-3:8090"), 503, "ignored");
        PeerJsonHttpClient client = new PeerJsonHttpClient(url -> connection);

        PeerJsonHttpClient.PostResult result = client.postJson("http://validator-3:8090/v1/consensus/peer-joined", "{\"validatorId\":\"validator-3\"}");

        assertEquals(503, result.getResponseCode());
        assertEquals("", result.getResponseBody());
        assertFalse(connection.inputStreamRequested);
    }

    @Test(expected = IOException.class)
    public void testPostJsonPropagatesConnectionFailures() throws Exception {
        PeerJsonHttpClient client = new PeerJsonHttpClient(url -> {
            throw new IOException("boom");
        });

        client.postJson("http://validator-4:8090/v1/register-validator", "{}");
    }

    private static final class FakeHttpURLConnection extends HttpURLConnection {
        private final int responseCode;
        private final byte[] responseBody;
        private final ByteArrayOutputStream writtenBody = new ByteArrayOutputStream();
        private final Map<String, String> requestProperties = new HashMap<>();
        private String method;
        private boolean doOutput;
        private int connectTimeout;
        private int readTimeout;
        private boolean inputStreamRequested;

        private FakeHttpURLConnection(URL url, int responseCode, String responseBody) {
            super(url);
            this.responseCode = responseCode;
            this.responseBody = responseBody.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }

        @Override
        public void connect() {
        }

        @Override
        public void setRequestMethod(String method) {
            this.method = method;
        }

        @Override
        public void setRequestProperty(String key, String value) {
            requestProperties.put(key, value);
        }

        @Override
        public void setDoOutput(boolean doOutput) {
            this.doOutput = doOutput;
        }

        @Override
        public void setConnectTimeout(int timeout) {
            this.connectTimeout = timeout;
        }

        @Override
        public void setReadTimeout(int timeout) {
            this.readTimeout = timeout;
        }

        @Override
        public ByteArrayOutputStream getOutputStream() {
            return writtenBody;
        }

        @Override
        public int getResponseCode() {
            return responseCode;
        }

        @Override
        public ByteArrayInputStream getInputStream() {
            inputStreamRequested = true;
            return new ByteArrayInputStream(responseBody);
        }

        private String getWrittenBody() {
            return writtenBody.toString(StandardCharsets.UTF_8);
        }
    }
}
