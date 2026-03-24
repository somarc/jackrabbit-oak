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
package org.apache.jackrabbit.oak.segment.http;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * Default {@link ValidatorHealthProbe} that checks validator reachability by
 * issuing a short-lived HTTP request to {@code /journal.log}.
 *
 * <p>A validator is considered available only when that endpoint returns
 * HTTP 200. Any transport error or non-success status is treated as
 * unavailable.</p>
 */
class HttpValidatorHealthProbe implements ValidatorHealthProbe {

    private static final Logger log = LoggerFactory.getLogger(HttpValidatorHealthProbe.class);

    /**
     * Factory used to create the short-lived client for a single probe cycle.
     */
    @FunctionalInterface
    interface HttpClientFactory {
        CloseableHttpClient create(RequestConfig requestConfig);
    }

    private final HttpClientFactory clientFactory;

    /**
     * Creates the probe with the default Apache {@link CloseableHttpClient}
     * factory.
     */
    HttpValidatorHealthProbe() {
        this(requestConfig -> HttpClients.custom()
            .setDefaultRequestConfig(requestConfig)
            .build());
    }

    /**
     * Testing seam that injects a custom client factory.
     */
    HttpValidatorHealthProbe(HttpClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    @Override
    public boolean isAvailable(String baseUrl, int timeoutMs) {
        RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(timeoutMs)
            .setSocketTimeout(timeoutMs)
            .setConnectionRequestTimeout(timeoutMs)
            .build();

        try (CloseableHttpClient client = clientFactory.create(requestConfig)) {

            // Probe the same endpoint the mount needs for normal operation.
            String url = baseUrl + "/journal.log";
            HttpGet request = new HttpGet(url);

            try (CloseableHttpResponse response = client.execute(request)) {
                int status = response.getStatusLine().getStatusCode();
                if (status == 200) {
                    return true;
                }
                log.debug("Health check failed: HTTP {} from {}", status, url);
                return false;
            }
        } catch (IOException e) {
            log.debug("Health check failed: {} - {}", baseUrl, e.getMessage());
            return false;
        }
    }
}
