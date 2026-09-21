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
package org.apache.jackrabbit.oak.segment.consensus.eth;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class LocalBeaconNodeProviderTest {

    private static final String NODE_URL = "http://localhost:5052";

    private static final String FINALITY_RESPONSE =
        "{\"data\":{" +
        "\"previous_justified\":{\"epoch\":\"1232\",\"root\":\"0xabc\"}," +
        "\"current_justified\":{\"epoch\":\"1233\",\"root\":\"0xdef\"}," +
        "\"finalized\":{\"epoch\":\"1231\",\"root\":\"0x123\"}" +
        "}}";

    @Test
    public void testFetchesFinalizedEpoch() throws Exception {
        LocalBeaconNodeProvider p = new LocalBeaconNodeProvider(NODE_URL, url -> {
            assertTrue(url.contains("/eth/v1/beacon/states/head/finality_checkpoints"));
            return FINALITY_RESPONSE;
        });

        assertEquals(1231L, p.fetchFinalizedEpoch());
    }

    @Test
    public void testStripsTrailingSlashFromNodeUrl() throws Exception {
        LocalBeaconNodeProvider p = new LocalBeaconNodeProvider(NODE_URL + "/", url -> {
            assertTrue("URL must not have double slash", !url.contains("//eth"));
            return FINALITY_RESPONSE;
        });
        assertEquals(1231L, p.fetchFinalizedEpoch());
    }

    @Test
    public void testThrowsOnHttpError() {
        LocalBeaconNodeProvider p = new LocalBeaconNodeProvider(NODE_URL, url -> {
            throw new RuntimeException("Connection refused");
        });

        try {
            p.fetchFinalizedEpoch();
            fail("Expected Exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Connection refused"));
        }
    }

    @Test
    public void testThrowsWhenFinalizedEpochNotParseable() {
        LocalBeaconNodeProvider p = new LocalBeaconNodeProvider(NODE_URL, url -> "{\"data\":{}}");

        try {
            p.fetchFinalizedEpoch();
            fail("Expected Exception");
        } catch (Exception e) {
            assertTrue(e.getMessage().contains("Could not parse"));
        }
    }

    @Test
    public void testNameIncludesNodeUrl() {
        LocalBeaconNodeProvider p = new LocalBeaconNodeProvider(NODE_URL, url -> "");
        assertTrue(p.name().contains(NODE_URL));
    }

    // ── parseFinalizedEpoch ───────────────────────────────────────────────────

    @Test
    public void testParseQuotedEpoch() {
        LocalBeaconNodeProvider p = provider();
        assertEquals(1231L, p.parseFinalizedEpoch(FINALITY_RESPONSE));
    }

    @Test
    public void testParseUnquotedEpoch() {
        LocalBeaconNodeProvider p = provider();
        String json = "{\"data\":{\"finalized\":{\"epoch\":999,\"root\":\"0x\"}}}";
        assertEquals(999L, p.parseFinalizedEpoch(json));
    }

    @Test
    public void testParseReturnsMinusOneWhenFinalizedMissing() {
        LocalBeaconNodeProvider p = provider();
        assertEquals(-1L, p.parseFinalizedEpoch("{\"data\":{\"current_justified\":{\"epoch\":\"5\"}}}"));
    }

    @Test
    public void testParseReturnsMinusOneOnNullAndEmpty() {
        LocalBeaconNodeProvider p = provider();
        assertEquals(-1L, p.parseFinalizedEpoch(null));
        assertEquals(-1L, p.parseFinalizedEpoch(""));
    }

    @Test
    public void testParseReturnsMinusOneOnGarbage() {
        LocalBeaconNodeProvider p = provider();
        assertEquals(-1L, p.parseFinalizedEpoch("{\"data\":{\"finalized\":{\"epoch\":\"notanumber\"}}}"));
    }

    private static LocalBeaconNodeProvider provider() {
        return new LocalBeaconNodeProvider(NODE_URL, url -> "");
    }
}
