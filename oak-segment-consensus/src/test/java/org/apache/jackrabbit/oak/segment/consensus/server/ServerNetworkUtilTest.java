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

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class ServerNetworkUtilTest {

    @Test
    public void testResolveUrlToIpReturnsSameForIp() {
        String input = "http://127.0.0.1:8090";
        String resolved = ServerNetworkUtil.resolveUrlToIP(input);

        assertEquals(input, resolved);
    }

    @Test
    public void testResolveUrlToIpWithInvalidUrlReturnsOriginal() {
        String input = "not-a-url";
        String resolved = ServerNetworkUtil.resolveUrlToIP(input);

        assertEquals(input, resolved);
    }

    @Test
    public void testParsePeerUrlsTrimsAndSkipsEmpty() {
        String peers = " http://127.0.0.1:8090, ,http://10.0.0.2:8090 ";

        List<String> parsed = ServerNetworkUtil.parsePeerUrls(peers);

        assertEquals(2, parsed.size());
        assertEquals("http://127.0.0.1:8090", parsed.get(0));
        assertEquals("http://10.0.0.2:8090", parsed.get(1));
    }

    @Test
    public void testParsePeerUrlsEmptyReturnsEmptyList() {
        List<String> parsed = ServerNetworkUtil.parsePeerUrls("  ");

        assertTrue(parsed.isEmpty());
    }

    @Test
    public void testExtractHostnameFromUrl() {
        String host = ServerNetworkUtil.extractHostname("http://validator-1:8090");

        assertEquals("validator-1", host);
    }

    @Test
    public void testExtractHostnameFallback() {
        String host = ServerNetworkUtil.extractHostname("validator-1:8090");

        assertEquals("localhost", host);
    }
}
