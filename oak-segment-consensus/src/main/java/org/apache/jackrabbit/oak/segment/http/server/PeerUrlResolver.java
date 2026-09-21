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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.URL;
import java.net.UnknownHostException;

final class PeerUrlResolver {

    interface HostResolver {
        String resolve(String hostname) throws UnknownHostException;
    }

    interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    private static final Logger log = LoggerFactory.getLogger(PeerUrlResolver.class);
    private static final int MAX_RETRIES = 10;
    private static final int INITIAL_DELAY_MS = 1000;
    private static final int MAX_DELAY_MS = 5000;

    private final HostResolver hostResolver;
    private final Sleeper sleeper;

    PeerUrlResolver() {
        this(hostname -> InetAddress.getByName(hostname).getHostAddress(), Thread::sleep);
    }

    PeerUrlResolver(HostResolver hostResolver, Sleeper sleeper) {
        this.hostResolver = hostResolver;
        this.sleeper = sleeper;
    }

    String resolve(String url) {
        try {
            URL parsedUrl = new URL(url);
            String hostname = parsedUrl.getHost();
            int port = parsedUrl.getPort();
            String protocol = parsedUrl.getProtocol();
            String path = parsedUrl.getPath();

            if (hostname.matches("^\\d+\\.\\d+\\.\\d+\\.\\d+$")) {
                return url;
            }

            int retryDelayMs = INITIAL_DELAY_MS;
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    String ip = hostResolver.resolve(hostname);
                    String ipUrl = String.format("%s://%s%s%s",
                        protocol,
                        ip,
                        port != -1 ? ":" + port : "",
                        path != null ? path : "");
                    if (attempt > 1) {
                        log.debug("✅ Resolved {} → {} (attempt {})", url, ipUrl, attempt);
                    }
                    return ipUrl;
                } catch (UnknownHostException e) {
                    if (attempt < MAX_RETRIES) {
                        if (attempt <= 3 || attempt % 5 == 0) {
                            log.debug("⚠️  DNS resolution failed for {} (attempt {}/{}), retrying...",
                                hostname, attempt, MAX_RETRIES);
                        }
                        try {
                            sleeper.sleep(retryDelayMs);
                            retryDelayMs = Math.min(retryDelayMs * 2, MAX_DELAY_MS);
                        } catch (InterruptedException interruptedException) {
                            Thread.currentThread().interrupt();
                            log.warn("⚠️  DNS resolution interrupted for {}", hostname);
                            return url;
                        }
                    } else {
                        log.warn("⚠️  Failed to resolve {} after {} attempts, using hostname", hostname, MAX_RETRIES);
                        return url;
                    }
                }
            }
            return url;
        } catch (Exception e) {
            log.warn("⚠️  Failed to parse URL {}: {}, using original", url, e.getMessage());
            return url;
        }
    }
}
