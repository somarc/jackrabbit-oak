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

import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class AeronClusterAddressResolverTest {

    @Test
    public void resolveRequiredHostnameRetriesBeforeSuccess() {
        AtomicInteger attempts = new AtomicInteger();
        List<Long> sleeps = new ArrayList<>();
        AeronClusterAddressResolver resolver = new AeronClusterAddressResolver(
            0,
            Collections.singletonList("self"),
            hostname -> {
                if (attempts.incrementAndGet() < 3) {
                    throw new UnknownHostException(hostname);
                }
                return "172.18.0.10";
            },
            Collections::emptyList,
            sleeps::add
        );

        assertEquals("172.18.0.10", resolver.resolveRequiredHostname("peer-1"));
        assertEquals(Arrays.asList(1000L, 2000L), sleeps);
    }

    @Test
    public void resolveLocalNodeAddressPrefersPeerSubnetMatch() {
        AeronClusterAddressResolver resolver = new AeronClusterAddressResolver(
            0,
            Arrays.asList("self", "peer-1"),
            hostname -> "peer-1".equals(hostname) ? "172.20.1.8" : "192.168.1.5",
            () -> Arrays.asList(
                new AeronClusterAddressResolver.CandidateAddress("en1", "172.21.0.4"),
                new AeronClusterAddressResolver.CandidateAddress("en0", "172.20.1.7")
            ),
            millis -> { }
        );

        assertEquals("172.20.1.7", resolver.resolveLocalNodeAddress("self"));
    }

    @Test
    public void resolveLocalNodeAddressFallsBackToFirstCandidateWhenNoSubnetMatch() {
        AeronClusterAddressResolver resolver = new AeronClusterAddressResolver(
            0,
            Arrays.asList("self", "peer-1"),
            hostname -> "10.0.0.8",
            () -> Arrays.asList(
                new AeronClusterAddressResolver.CandidateAddress("en1", "172.30.0.4"),
                new AeronClusterAddressResolver.CandidateAddress("en2", "172.31.0.5")
            ),
            millis -> { }
        );

        assertEquals("172.30.0.4", resolver.resolveLocalNodeAddress("self"));
    }

    @Test
    public void resolveLocalNodeAddressFallsBackToSelfHostnameResolution() {
        AeronClusterAddressResolver resolver = new AeronClusterAddressResolver(
            0,
            Arrays.asList("self", "peer-1"),
            hostname -> "self".equals(hostname) ? "192.168.10.9" : "10.0.0.8",
            Collections::emptyList,
            millis -> { }
        );

        assertEquals("192.168.10.9", resolver.resolveLocalNodeAddress("self"));
    }

    @Test
    public void resolveClusterMemberAddressesUsesSelfIpAndKeepsUnresolvedPeersAsHostnames() {
        AeronClusterAddressResolver resolver = new AeronClusterAddressResolver(
            0,
            Arrays.asList("self", "peer-1", "peer-2"),
            hostname -> {
                if ("peer-1".equals(hostname)) {
                    return "172.20.1.11";
                }
                if ("peer-2".equals(hostname)) {
                    throw new UnknownHostException(hostname);
                }
                return "192.168.10.9";
            },
            Collections::emptyList,
            millis -> { }
        );

        assertEquals(
            Arrays.asList("172.20.1.7", "172.20.1.11", "peer-2"),
            resolver.resolveClusterMemberAddresses("172.20.1.7")
        );
    }
}
