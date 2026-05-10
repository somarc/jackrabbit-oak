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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class RateLimiterTest {

    @Test
    public void testDisabledLimiterAllowsWithoutTrackingMetrics() {
        RateLimiter limiter = new RateLimiter(false, 1, 1, 1, 1);
        try {
            assertTrue(limiter.allowRequest(request("GET", "/v1/config/osgi", "127.0.0.1"), mock(HttpServletResponse.class)));

            RateLimiter.RateLimitMetrics metrics = limiter.getMetrics();
            assertEquals(0L, metrics.totalRequests);
            assertEquals(0L, metrics.throttledRequests);
            assertEquals(0, metrics.activeClients);
            assertEquals(0, metrics.activeWallets);
            assertEquals(0.0d, metrics.getThrottleRate(), 0.0d);
        } finally {
            limiter.shutdown();
        }
    }

    @Test
    public void testClientBucketThrottlesSecondRequest() {
        RateLimiter limiter = new RateLimiter(true, 1, 1, 100, 10);
        try {
            assertTrue(limiter.allowRequest(request("GET", "/v1/config/osgi", "127.0.0.1"), mock(HttpServletResponse.class)));

            HttpServletResponse throttledResponse = mock(HttpServletResponse.class);
            assertFalse(limiter.allowRequest(request("GET", "/v1/config/osgi", "127.0.0.1"), throttledResponse));

            verify(throttledResponse).setHeader("X-RateLimit-Limit", "1");
            verify(throttledResponse).setHeader("X-RateLimit-Remaining", "0");
            verify(throttledResponse).setHeader("Retry-After", "1");

            RateLimiter.RateLimitMetrics metrics = limiter.getMetrics();
            assertEquals(2L, metrics.totalRequests);
            assertEquals(1L, metrics.throttledRequests);
            assertEquals(1, metrics.activeClients);
            assertEquals(0, metrics.activeWallets);
            assertEquals(0.5d, metrics.getThrottleRate(), 0.0d);
        } finally {
            limiter.shutdown();
        }
    }

    @Test
    public void testForwardedIpControlsClientBucketIdentity() {
        RateLimiter limiter = new RateLimiter(true, 1, 1, 100, 10);
        try {
            HttpServletRequest first = request("GET", "/v1/config/osgi", "10.0.0.1");
            when(first.getHeader("X-Forwarded-For")).thenReturn("203.0.113.8, 10.0.0.1");
            assertTrue(limiter.allowRequest(first, mock(HttpServletResponse.class)));

            HttpServletRequest second = request("GET", "/v1/config/osgi", "10.0.0.2");
            when(second.getHeader("X-Forwarded-For")).thenReturn("203.0.113.8, 10.0.0.2");
            assertFalse(limiter.allowRequest(second, mock(HttpServletResponse.class)));

            assertEquals(1, limiter.getMetrics().activeClients);
        } finally {
            limiter.shutdown();
        }
    }

    @Test
    public void testWalletWriteBucketThrottlesThirdWrite() {
        RateLimiter limiter = new RateLimiter(true, 100, 100, 100, 1);
        try {
            HttpServletRequest first = request("POST", "/v1/write", "127.0.0.1");
            when(first.getParameter("wallet")).thenReturn("0xABC");
            assertTrue(limiter.allowRequest(first, mock(HttpServletResponse.class)));

            HttpServletRequest second = request("POST", "/v1/write", "127.0.0.1");
            when(second.getParameter("wallet")).thenReturn("0xABC");
            assertTrue(limiter.allowRequest(second, mock(HttpServletResponse.class)));

            HttpServletRequest third = request("POST", "/v1/write", "127.0.0.1");
            when(third.getParameter("wallet")).thenReturn("0xABC");
            HttpServletResponse throttledResponse = mock(HttpServletResponse.class);
            assertFalse(limiter.allowRequest(third, throttledResponse));

            verify(throttledResponse).setHeader("X-RateLimit-Limit", "2");
            verify(throttledResponse).setHeader("X-RateLimit-Remaining", "0");
            verify(throttledResponse).setHeader("Retry-After", "1");

            RateLimiter.RateLimitMetrics metrics = limiter.getMetrics();
            assertEquals(3L, metrics.totalRequests);
            assertEquals(1L, metrics.throttledRequests);
            assertEquals(1, metrics.activeClients);
            assertEquals(1, metrics.activeWallets);
        } finally {
            limiter.shutdown();
        }
    }

    @Test
    public void testSendRateLimitResponseWritesJson429() throws Exception {
        RateLimiter limiter = new RateLimiter(true, 1, 1, 1, 1);
        try {
            StringWriter body = new StringWriter();
            HttpServletResponse response = mock(HttpServletResponse.class);
            when(response.getWriter()).thenReturn(new PrintWriter(body));

            limiter.sendRateLimitResponse(response);

            verify(response).setStatus(429);
            assertTrue(body.toString().contains("rate_limit_exceeded"));
            assertTrue(body.toString().contains("Too many requests"));
        } finally {
            limiter.shutdown();
        }
    }

    private HttpServletRequest request(String method, String uri, String remoteAddr) {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn(method);
        when(request.getRequestURI()).thenReturn(uri);
        when(request.getRemoteAddr()).thenReturn(remoteAddr);
        return request;
    }
}
