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
package org.apache.jackrabbit.oak.blob.cloud.ipfs;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.security.GeneralSecurityException;
import java.util.Base64;

import org.junit.Test;

public class StaticBlobKeyProviderTest {

    private static final String K1 = BlobEncryptionTest.randomKey();
    private static final String K2 = BlobEncryptionTest.randomKey();

    @Test
    public void parsesKeyringAndResolvesEveryKey() throws Exception {
        StaticBlobKeyProvider keys = StaticBlobKeyProvider.fromSpec(" key-2 ;\n key.1 : " + K1 + " ;\n key-2:" + K2 + "\n");
        assertEquals("key-2", keys.current().keyId());
        assertSame(keys.resolve("key-2"), keys.current().key());
        assertArrayEquals(Base64.getDecoder().decode(K1), keys.resolve("key.1").getEncoded());
        assertArrayEquals(Base64.getDecoder().decode(K2), keys.resolve("key-2").getEncoded());
        assertEquals("AES", keys.resolve("key.1").getAlgorithm());
        GeneralSecurityException e = assertThrows(GeneralSecurityException.class, () -> keys.resolve("nope"));
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    public void toStringListsIdsButNoKeyMaterial() {
        String s = StaticBlobKeyProvider.fromSpec("a;a:" + K1 + ";b:" + K2).toString();
        assertEquals("StaticBlobKeyProvider[current=a, keyIds=[a, b]]", s);
    }

    @Test
    public void rejectsMalformedSpecs() {
        assertRejected(null, "empty");
        assertRejected("", "empty");
        assertRejected("  \n", "empty");
        assertRejected("k1", "no keys");
        assertRejected(";k1:" + K1, "Invalid blob key id");
        assertRejected("k 1;k 1:" + K1, "Invalid blob key id");
        assertRejected("x".repeat(65) + ";k1:" + K1, "Invalid blob key id");
        assertRejected("k1;k1:" + K1 + ";", "not of the form");
        assertRejected("k1;k1" + K1, "not of the form");
        assertRejected("k1;:" + K1, "Invalid blob key id");
        assertRejected("k1;k1:" + K1 + ";k1:" + K2, "Duplicate blob key id: k1");
        assertRejected("k1;k1:not*base64", "not valid base64");
        assertRejected("k1;k1:" + Base64.getEncoder().encodeToString(new byte[16]), "must be 32 bytes, was 16");
        assertRejected("k1;k1:" + Base64.getEncoder().encodeToString(new byte[33]), "must be 32 bytes, was 33");
        assertRejected("k1;k1:", "must be 32 bytes, was 0");
        assertRejected("k3;k1:" + K1 + ";k2:" + K2, "not defined: k3");
    }

    @Test
    public void errorsNeverEchoKeyMaterial() {
        // forgetting the current key id makes the first key the "id"
        assertFalse(rejection("k1:" + K1 + ";k2:" + K2).contains(K1));
        assertFalse(rejection("k1;k1:" + K1.substring(1)).contains(K1.substring(1)));
        assertFalse(rejection("k1;k1:" + K1 + "AAAA").contains(K1));
    }

    private static void assertRejected(String spec, String message) {
        String actual = rejection(spec);
        assertTrue(actual, actual.contains(message));
    }

    private static String rejection(String spec) {
        return assertThrows(IllegalArgumentException.class, () -> StaticBlobKeyProvider.fromSpec(spec)).getMessage();
    }
}
