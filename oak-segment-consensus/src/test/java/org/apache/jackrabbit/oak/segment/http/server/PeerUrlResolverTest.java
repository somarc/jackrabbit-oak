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

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PeerUrlResolverTest {

    @Test
    public void testResolveReturnsIpUrlForHostname() {
        PeerUrlResolver resolver = new PeerUrlResolver(hostname -> "172.18.0.3", millis -> {
        });

        String resolved = resolver.resolve("http://validator-2:8090/path");

        assertEquals("http://172.18.0.3:8090/path", resolved);
    }

    @Test
    public void testResolveLeavesIpAddressUntouched() {
        AtomicInteger resolverCalls = new AtomicInteger();
        PeerUrlResolver resolver = new PeerUrlResolver(hostname -> {
            resolverCalls.incrementAndGet();
            return "should-not-be-used";
        }, millis -> {
        });

        String url = "http://127.0.0.1:8090";
        String resolved = resolver.resolve(url);

        assertSame(url, resolved);
        assertEquals(0, resolverCalls.get());
    }

    @Test
    public void testResolveRetriesWithBackoffBeforeSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        PeerUrlResolver resolver = new PeerUrlResolver(hostname -> {
            if (attempts.incrementAndGet() < 3) {
                throw new UnknownHostException(hostname);
            }
            return "172.18.0.4";
        }, sleeps::add);

        String resolved = resolver.resolve("http://validator-3:8090");

        assertEquals("http://172.18.0.4:8090", resolved);
        assertEquals(Arrays.asList(1000L, 2000L), sleeps);
        assertEquals(3, attempts.get());
    }

    @Test
    public void testResolveReturnsOriginalUrlWhenSleepInterrupted() {
        PeerUrlResolver resolver = new PeerUrlResolver(hostname -> {
            throw new UnknownHostException(hostname);
        }, millis -> {
            throw new InterruptedException("stop");
        });

        try {
            String url = "http://validator-4:8090";
            String resolved = resolver.resolve(url);

            assertEquals(url, resolved);
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void testResolveReturnsOriginalUrlForInvalidInput() {
        PeerUrlResolver resolver = new PeerUrlResolver(hostname -> "172.18.0.5", millis -> {
        });

        String url = "not-a-url";
        String resolved = resolver.resolve(url);

        assertEquals(url, resolved);
    }

    @Test
    public void testResolveReturnsOriginalUrlAfterMaxRetries() {
        List<Long> sleeps = new ArrayList<>();
        PeerUrlResolver resolver = new PeerUrlResolver(hostname -> {
            throw new UnknownHostException(hostname);
        }, sleeps::add);

        String url = "http://validator-5:8090";
        String resolved = resolver.resolve(url);

        assertEquals(url, resolved);
        assertEquals(9, sleeps.size());
        assertEquals(Arrays.asList(1000L, 2000L, 4000L, 5000L, 5000L, 5000L, 5000L, 5000L, 5000L), sleeps);
    }
}
