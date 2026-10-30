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

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Signs and verifies expiring download URLs for binaries, the counterpart of
 * S3 presigned and Azure SAS URLs: {@code /api/blob/{id}?exp=&kid=&sig=}.
 *
 * <p>The signature is HMAC-SHA256 over the method, path, expiry and key id
 * with a key from a keyring that every validator shares, so a URL issued by
 * one validator is accepted by all. Signing is off until a keyring is
 * configured; key material is never logged or reported.</p>
 */
public final class BlobUrlSigner {

    public static final String KEYS_PROPERTY = "oak.blob.url.keys";
    public static final String KEYS_ENV = "OAK_BLOB_URL_KEYS";
    public static final String TTL_PROPERTY = "oak.blob.url.ttl-seconds";
    public static final String TTL_ENV = "OAK_BLOB_URL_TTL_SECONDS";
    public static final String MAX_TTL_PROPERTY = "oak.blob.url.max-ttl-seconds";
    public static final String MAX_TTL_ENV = "OAK_BLOB_URL_MAX_TTL_SECONDS";
    public static final String SKEW_PROPERTY = "oak.blob.url.clock-skew-seconds";
    public static final String SKEW_ENV = "OAK_BLOB_URL_CLOCK_SKEW_SECONDS";
    public static final String REQUIRED_PROPERTY = "oak.blob.url.required";
    public static final String REQUIRED_ENV = "OAK_BLOB_URL_REQUIRED";

    public static final String PATH_PREFIX = "/api/blob/";

    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern BLOB_ID = Pattern.compile("(?!\\.+$)[A-Za-z0-9._-]{1,256}");
    private static final int KEY_BYTES = 32;
    private static final int SIGNATURE_BYTES = 32;

    /**
     * The outcome of checking a request's signature.
     */
    public enum Verdict {
        /** The request carries no signature, or signing is not configured. */
        ABSENT,
        /** The signature is valid and has not expired. */
        VALID,
        /** The signature, key id or expiry is malformed or does not match. */
        INVALID,
        /** The signature was valid but its expiry has passed. */
        EXPIRED
    }

    /**
     * A signed URL relative to any validator that shares the keyring.
     */
    public record SignedUrl(String blobId, String url, long expiresAt, String keyId) {
    }

    private final String currentKeyId;
    private final Map<String, byte[]> keys;
    private final long defaultTtl;
    private final long maxTtl;
    private final long skew;
    private final boolean required;

    BlobUrlSigner(String keySpec, long defaultTtl, long maxTtl, long skew, boolean required) {
        if (defaultTtl < 1 || maxTtl < defaultTtl || skew < 0) {
            throw new IllegalArgumentException(
                "Signed blob URLs need 1 <= ttl-seconds <= max-ttl-seconds and clock-skew-seconds >= 0");
        }
        Map<String, byte[]> parsed = new LinkedHashMap<>();
        String current = null;
        if (RuntimeConfigValueResolver.hasText(keySpec)) {
            String[] parts = keySpec.trim().split(";", -1);
            current = checkKeyId(parts[0].trim());
            for (int i = 1; i < parts.length; i++) {
                String entry = parts[i].trim();
                int colon = entry.indexOf(':');
                if (colon < 0) {
                    throw new IllegalArgumentException(KEYS_PROPERTY + " entry " + i + " is not of the form id:base64Key");
                }
                String keyId = checkKeyId(entry.substring(0, colon).trim());
                if (parsed.put(keyId, decodeKey(keyId, entry.substring(colon + 1).trim())) != null) {
                    throw new IllegalArgumentException("Duplicate blob URL key id: " + keyId);
                }
            }
            if (!parsed.containsKey(current)) {
                throw new IllegalArgumentException("Current blob URL key id is not defined: " + current);
            }
        }
        if (required && current == null) {
            throw new IllegalArgumentException(REQUIRED_PROPERTY + "=true needs " + KEYS_PROPERTY);
        }
        this.currentKeyId = current;
        this.keys = Collections.unmodifiableMap(parsed);
        this.defaultTtl = defaultTtl;
        this.maxTtl = maxTtl;
        this.skew = skew;
        this.required = required;
    }

    /**
     * Reads the keyring and limits from system properties or environment
     * variables.
     *
     * @throws IllegalArgumentException if the configuration is malformed
     */
    public static BlobUrlSigner fromConfig() {
        return new BlobUrlSigner(
            RuntimeConfigValueResolver.readString(KEYS_PROPERTY, KEYS_ENV, null),
            number(TTL_PROPERTY, TTL_ENV, 300),
            number(MAX_TTL_PROPERTY, MAX_TTL_ENV, 3600),
            number(SKEW_PROPERTY, SKEW_ENV, 60),
            flag(REQUIRED_PROPERTY, REQUIRED_ENV));
    }

    /**
     * Reads a number strictly: a malformed value fails startup instead of
     * silently becoming the default.
     */
    private static long number(String property, String env, long defaultValue) {
        String value = RuntimeConfigValueResolver.readString(property, env, null);
        if (!RuntimeConfigValueResolver.hasText(value)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(property + " must be a whole number of seconds", e);
        }
    }

    /**
     * Reads {@code true} or {@code false} strictly, so a typo cannot turn
     * {@code required} off.
     */
    private static boolean flag(String property, String env) {
        String value = RuntimeConfigValueResolver.readString(property, env, null);
        if (!RuntimeConfigValueResolver.hasText(value)) {
            return false;
        }
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.equals("true") && !normalized.equals("false")) {
            throw new IllegalArgumentException(property + " must be true or false");
        }
        return Boolean.parseBoolean(normalized);
    }

    public boolean enabled() {
        return currentKeyId != null;
    }

    /**
     * Whether unsigned blob downloads are refused.
     */
    public boolean required() {
        return required;
    }

    /**
     * Normalizes a blob id for signing: drops a {@code #length} suffix, plain
     * or percent-encoded.
     *
     * @return the id, or {@code null} if it contains other characters
     */
    public static String normalizeBlobId(String blobId) {
        if (blobId == null) {
            return null;
        }
        int hash = blobId.indexOf('#');
        int encodedHash = blobId.indexOf("%23");
        int end = hash >= 0 ? hash : encodedHash >= 0 ? encodedHash : blobId.length();
        String id = blobId.substring(0, end);
        return BLOB_ID.matcher(id).matches() ? id : null;
    }

    /**
     * Signs a download URL.
     *
     * @param ttlSeconds lifetime, or {@code null} for the default
     * @throws IllegalStateException if signing is not configured
     * @throws IllegalArgumentException if the id or lifetime is invalid
     */
    public SignedUrl sign(String blobId, Long ttlSeconds, long nowSeconds) {
        if (!enabled()) {
            throw new IllegalStateException("Signed blob URLs are not configured");
        }
        String id = normalizeBlobId(blobId);
        if (id == null) {
            throw new IllegalArgumentException("Invalid blob id");
        }
        long ttl = ttlSeconds == null ? defaultTtl : ttlSeconds;
        if (ttl < 1 || ttl > maxTtl) {
            throw new IllegalArgumentException("ttl must be between 1 and " + maxTtl + " seconds");
        }
        long expiresAt = nowSeconds + ttl;
        String signature = signature(keys.get(currentKeyId), id, expiresAt, currentKeyId);
        return new SignedUrl(id, PATH_PREFIX + id + "?exp=" + expiresAt + "&kid=" + currentKeyId + "&sig=" + signature,
            expiresAt, currentKeyId);
    }

    /**
     * Checks the signature of a request for {@code /api/blob/{blobId}}.
     */
    public Verdict verify(String blobId, String exp, String kid, String sig, long nowSeconds) {
        if (!enabled() || (sig == null && exp == null && kid == null)) {
            return Verdict.ABSENT;
        }
        byte[] key = kid == null ? null : keys.get(kid);
        if (key == null || exp == null || sig == null || blobId == null || !BLOB_ID.matcher(blobId).matches()) {
            return Verdict.INVALID;
        }
        long expiresAt;
        byte[] provided;
        try {
            expiresAt = Long.parseLong(exp);
            provided = Base64.getUrlDecoder().decode(sig);
        } catch (IllegalArgumentException e) {
            return Verdict.INVALID;
        }
        if (provided.length != SIGNATURE_BYTES || !MessageDigest.isEqual(provided,
                Base64.getUrlDecoder().decode(signature(key, blobId, expiresAt, kid)))) {
            return Verdict.INVALID;
        }
        if (nowSeconds > expiresAt + skew) {
            return Verdict.EXPIRED;
        }
        if (expiresAt - nowSeconds > maxTtl + skew) {
            return Verdict.INVALID;
        }
        return Verdict.VALID;
    }

    @Override
    public String toString() {
        return "BlobUrlSigner[currentKeyId=" + currentKeyId + ", keyIds=" + keys.keySet() + ", defaultTtl="
            + defaultTtl + ", maxTtl=" + maxTtl + ", skew=" + skew + ", required=" + required + "]";
    }

    private static String signature(byte[] key, String blobId, long expiresAt, String keyId) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            byte[] digest = mac.doFinal(("oak-blob-url/v1\nGET\n" + PATH_PREFIX + blobId + "\n" + expiresAt + "\n" + keyId)
                .getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }

    private static String checkKeyId(String keyId) {
        if (!KEY_ID.matcher(keyId).matches()) {
            // Not echoed: a misplaced key could end up here.
            throw new IllegalArgumentException("Invalid blob URL key id, expected " + KEY_ID.pattern());
        }
        return keyId;
    }

    private static byte[] decodeKey(String keyId, String base64) {
        byte[] key;
        try {
            key = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Blob URL key " + keyId + " is not valid base64");
        }
        if (key.length != KEY_BYTES) {
            throw new IllegalArgumentException("Blob URL key " + keyId + " must be " + KEY_BYTES + " bytes");
        }
        return key;
    }
}
