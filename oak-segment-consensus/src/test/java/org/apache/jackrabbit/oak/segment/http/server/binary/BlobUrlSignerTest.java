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
package org.apache.jackrabbit.oak.segment.http.server.binary;

import org.junit.Test;

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.apache.jackrabbit.oak.segment.http.server.binary.BlobUrlSigner.Verdict.ABSENT;
import static org.apache.jackrabbit.oak.segment.http.server.binary.BlobUrlSigner.Verdict.EXPIRED;
import static org.apache.jackrabbit.oak.segment.http.server.binary.BlobUrlSigner.Verdict.INVALID;
import static org.apache.jackrabbit.oak.segment.http.server.binary.BlobUrlSigner.Verdict.VALID;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class BlobUrlSignerTest {

    private static final String ID = "ed06f9cbf0fe878013ccb266170e6b3ba676933a6f065675cc0115c840bf1442";
    private static final long NOW = 1_800_000_000L;
    private static final String K1 = key(1);
    private static final String K2 = key(2);

    @Test
    public void testASignedUrlVerifiesUntilItExpires() {
        BlobUrlSigner signer = signer("k1;k1:" + K1);
        BlobUrlSigner.SignedUrl url = signer.sign(ID + "#22216", null, NOW);

        assertEquals(ID, url.blobId());
        assertEquals(NOW + 300, url.expiresAt());
        assertEquals("k1", url.keyId());
        Map<String, String> query = query(url.url());
        assertTrue(url.url().startsWith("/api/blob/" + ID + "?"));
        assertEquals(43, query.get("sig").length());

        assertEquals(VALID, verify(signer, ID, query, NOW));
        assertEquals(VALID, verify(signer, ID, query, NOW + 300 + 60));
        assertEquals(EXPIRED, verify(signer, ID, query, NOW + 300 + 61));
    }

    @Test
    public void testEveryTamperedPartIsInvalid() {
        BlobUrlSigner signer = signer("k1;k1:" + K1);
        Map<String, String> query = query(signer.sign(ID, 60L, NOW).url());

        assertEquals(INVALID, verify(signer, ID.replace('e', 'f'), query, NOW));
        Map<String, String> later = new TreeMap<>(query);
        later.put("exp", String.valueOf(NOW + 61));
        assertEquals(INVALID, verify(signer, ID, later, NOW));
        Map<String, String> otherKey = new TreeMap<>(query);
        otherKey.put("kid", "k2");
        assertEquals(INVALID, verify(signer, ID, otherKey, NOW));
        String sig0 = query.get("sig");
        String flipped = sig0.substring(0, 10) + (sig0.charAt(10) == 'A' ? 'B' : 'A') + sig0.substring(11);
        for (String sig : List.of("", "AAAA", sig0 + "A", flipped, "not base64!")) {
            Map<String, String> tampered = new TreeMap<>(query);
            tampered.put("sig", sig);
            assertEquals(sig, INVALID, verify(signer, ID, tampered, NOW));
        }
        Map<String, String> missing = new TreeMap<>(query);
        missing.remove("exp");
        assertEquals(INVALID, verify(signer, ID, missing, NOW));
        Map<String, String> nonNumeric = new TreeMap<>(query);
        nonNumeric.put("exp", "soon");
        assertEquals(INVALID, verify(signer, ID, nonNumeric, NOW));
        assertEquals(INVALID, verify(signer, ID + "%23123", query, NOW));
    }

    @Test
    public void testRotatedKeysStillVerifyAndUnknownKeysDoNot() {
        Map<String, String> oldUrl = query(signer("k1;k1:" + K1).sign(ID, null, NOW).url());
        BlobUrlSigner rotated = signer("k2;k1:" + K1 + ";k2:" + K2);

        assertEquals(VALID, verify(rotated, ID, oldUrl, NOW));
        assertEquals("k2", rotated.sign(ID, null, NOW).keyId());
        assertEquals(INVALID, verify(signer("k2;k2:" + K2), ID, oldUrl, NOW));
        assertEquals(INVALID, verify(signer("k1;k1:" + K2), ID, oldUrl, NOW));
    }

    @Test
    public void testExpiryBeyondTheMaximumLifetimeIsInvalid() {
        BlobUrlSigner longLived = new BlobUrlSigner("k1;k1:" + K1, 300, 100_000, 60, false);
        Map<String, String> query = query(longLived.sign(ID, 100_000L, NOW).url());

        assertEquals(INVALID, verify(signer("k1;k1:" + K1), ID, query, NOW));
        assertEquals(VALID, verify(longLived, ID, query, NOW));
    }

    @Test
    public void testSigningRejectsBadIdsAndLifetimes() {
        BlobUrlSigner signer = signer("k1;k1:" + K1);
        for (String id : new String[] {"", "../etc", "a b", null}) {
            try {
                signer.sign(id, null, NOW);
                fail("Expected " + id + " to be rejected");
            } catch (IllegalArgumentException expected) {
                assertEquals("Invalid blob id", expected.getMessage());
            }
        }
        for (long ttl : new long[] {0, 3601}) {
            try {
                signer.sign(ID, ttl, NOW);
                fail("Expected ttl " + ttl + " to be rejected");
            } catch (IllegalArgumentException expected) {
                assertTrue(expected.getMessage().contains("3600"));
            }
        }
    }

    @Test
    public void testWithoutKeysSigningIsOffAndRequestsAreUnsigned() {
        BlobUrlSigner off = new BlobUrlSigner(null, 300, 3600, 60, false);

        assertFalse(off.enabled());
        assertEquals(ABSENT, off.verify(ID, String.valueOf(NOW), "k1", "x", NOW));
        assertEquals(ABSENT, signer("k1;k1:" + K1).verify(ID, null, null, null, NOW));
        try {
            off.sign(ID, null, NOW);
            fail("Expected signing to be unavailable");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("not configured"));
        }
    }

    @Test
    public void testMalformedKeyringsAreRejectedWithoutEchoingKeys() {
        String secret = Base64.getEncoder().encodeToString(new byte[31]);
        for (String spec : new String[] {"k1", "k1;k1", "k1;k2:" + K1, "k1;k1:" + secret, "k1;k1:" + K1 + ";k1:" + K2,
                "bad id;bad id:" + K1, "k1;k1:%%%"}) {
            try {
                signer(spec);
                fail("Expected " + spec + " to be rejected");
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().contains(secret));
                assertFalse(expected.getMessage().contains(K1));
            }
        }
        try {
            new BlobUrlSigner(null, 300, 3600, 60, true);
            fail("Expected required without keys to be rejected");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains(BlobUrlSigner.KEYS_PROPERTY));
        }
        assertFalse(signer("k1;k1:" + K1).toString().contains(K1));
    }

    @Test
    public void testNormalizeBlobId() {
        assertEquals(ID, BlobUrlSigner.normalizeBlobId(ID + "#22216"));
        assertEquals(ID, BlobUrlSigner.normalizeBlobId(ID + "%2322216"));
        assertEquals(ID, BlobUrlSigner.normalizeBlobId(ID));
        assertNull(BlobUrlSigner.normalizeBlobId("a/b"));
        assertNull(BlobUrlSigner.normalizeBlobId(null));
    }

    private static BlobUrlSigner signer(String spec) {
        return new BlobUrlSigner(spec, 300, 3600, 60, false);
    }

    private static BlobUrlSigner.Verdict verify(BlobUrlSigner signer, String id, Map<String, String> query, long now) {
        return signer.verify(id, query.get("exp"), query.get("kid"), query.get("sig"), now);
    }

    private static Map<String, String> query(String url) {
        Map<String, String> values = new TreeMap<>();
        for (String pair : url.substring(url.indexOf('?') + 1).split("&")) {
            int eq = pair.indexOf('=');
            values.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return values;
    }

    private static String key(int seed) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) seed);
        return Base64.getEncoder().encodeToString(key);
    }
}
