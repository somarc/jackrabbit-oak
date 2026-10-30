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

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

/**
 * A fixed keyring parsed from a spec string such as
 * {@code current;k1:<base64 32 bytes>;k2:<base64 32 bytes>}. The first entry
 * names the key used for new blobs; every other entry is {@code id:key}.
 * Error messages and {@link #toString()} never contain key material.
 */
final class StaticBlobKeyProvider implements BlobKeyProvider {

    static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private static final int KEY_BYTES = 32;

    private final KeyRef current;
    private final Map<String, SecretKey> keys;

    private StaticBlobKeyProvider(String currentKeyId, Map<String, SecretKey> keys) {
        this.keys = Collections.unmodifiableMap(keys);
        this.current = new KeyRef(currentKeyId, keys.get(currentKeyId));
    }

    /**
     * Parses a keyring spec. Whitespace around entries is ignored.
     *
     * @throws IllegalArgumentException if the spec is malformed, a key id is
     *         invalid or duplicated, a key is not 32 bytes of standard base64,
     *         or the current key id is not defined
     */
    public static StaticBlobKeyProvider fromSpec(String spec) {
        if (spec == null || spec.isBlank()) {
            throw new IllegalArgumentException("Blob key spec is empty");
        }
        String[] parts = spec.split(";", -1);
        String currentKeyId = checkKeyId(parts[0].trim());
        if (parts.length < 2) {
            throw new IllegalArgumentException("Blob key spec defines no keys");
        }
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String entry = parts[i].trim();
            int colon = entry.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("Blob key spec entry " + i + " is not of the form id:base64Key");
            }
            String keyId = checkKeyId(entry.substring(0, colon).trim());
            if (keys.containsKey(keyId)) {
                throw new IllegalArgumentException("Duplicate blob key id: " + keyId);
            }
            keys.put(keyId, decodeKey(keyId, entry.substring(colon + 1).trim()));
        }
        if (!keys.containsKey(currentKeyId)) {
            throw new IllegalArgumentException("Current blob key id is not defined: " + currentKeyId);
        }
        return new StaticBlobKeyProvider(currentKeyId, keys);
    }

    @Override
    public KeyRef current() {
        return current;
    }

    @Override
    public SecretKey resolve(String keyId) throws GeneralSecurityException {
        SecretKey key = keys.get(keyId);
        if (key == null) {
            throw new GeneralSecurityException("Unknown blob key id: " + keyId);
        }
        return key;
    }

    @Override
    public String toString() {
        return "StaticBlobKeyProvider[current=" + current.keyId() + ", keyIds=" + keys.keySet() + "]";
    }

    private static String checkKeyId(String keyId) {
        if (!KEY_ID.matcher(keyId).matches()) {
            // never echo the rejected value: a misplaced key could end up here
            throw new IllegalArgumentException("Invalid blob key id, expected " + KEY_ID.pattern());
        }
        return keyId;
    }

    private static SecretKey decodeKey(String keyId, String base64) {
        byte[] raw;
        try {
            raw = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Blob key " + keyId + " is not valid base64");
        }
        try {
            if (raw.length != KEY_BYTES) {
                throw new IllegalArgumentException("Blob key " + keyId + " must be " + KEY_BYTES + " bytes, was " + raw.length);
            }
            return new SecretKeySpec(raw, "AES");
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }
}
