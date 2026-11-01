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
package org.apache.jackrabbit.oak.segment.consensus.federation;

import org.apache.jackrabbit.oak.segment.consensus.validation.MutationRejectedException;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonParser;
import org.web3j.crypto.Hash;
import org.web3j.utils.Numeric;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Canonical encoding and field validation shared by the replicated federation records. Every check is a
 * pure function of its input, so a record rejected on one member is rejected on all of them.
 */
final class FederationJson {

    static final Pattern ADDRESS = Pattern.compile("^0x[0-9a-f]{40}$");
    static final Pattern BYTES32 = Pattern.compile("^0x[0-9a-f]{64}$");

    private FederationJson() {
    }

    /** Compact JSON with object keys sorted at every level; list order is preserved. */
    static String canonical(Object value) {
        return JsonParser.toJson(sorted(value));
    }

    static String keccak(String text) {
        return Numeric.toHexString(Hash.sha3(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static Object sorted(Object value) {
        if (value instanceof Map) {
            Map<String, Object> result = new TreeMap<>();
            ((Map<?, ?>) value).forEach((key, v) -> result.put(String.valueOf(key), sorted(v)));
            return result;
        }
        if (value instanceof List) {
            List<Object> result = new ArrayList<>();
            ((List<?>) value).forEach(v -> result.add(sorted(v)));
            return result;
        }
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        return value;
    }

    static Map<String, Object> object(Map<String, Object> json, String key) {
        Object value = json.get(key);
        if (!(value instanceof Map)) {
            throw new MutationRejectedException("Missing object field: " + key);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    static String string(Map<String, Object> json, String key) {
        Object value = json.get(key);
        if (!(value instanceof String)) {
            throw new MutationRejectedException("Missing string field: " + key);
        }
        return (String) value;
    }

    static String optionalString(Map<String, Object> json, String key) {
        Object value = json.get(key);
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new MutationRejectedException("Field is not a string: " + key);
        }
        return (String) value;
    }

    static long nonNegativeLong(Map<String, Object> json, String key) {
        Object value = json.get(key);
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 0) {
            throw new MutationRejectedException("Field is not a non-negative integer: " + key);
        }
        return ((Number) value).longValue();
    }

    static String address(String value, String what) {
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!ADDRESS.matcher(normalized).matches()) {
            throw new MutationRejectedException("Invalid address for " + what + ": " + value);
        }
        return normalized;
    }

    /** An empty string or a 0x-prefixed 32-byte hex value, lower-cased. */
    static String optionalBytes32(Map<String, Object> json, String key) {
        String value = optionalString(json, key).toLowerCase(Locale.ROOT);
        if (!value.isEmpty() && !BYTES32.matcher(value).matches()) {
            throw new MutationRejectedException("Invalid bytes32 for " + key + ": " + value);
        }
        return value;
    }

    static List<String> strings(Map<String, Object> json, String key) {
        Object value = json.get(key);
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List)) {
            throw new MutationRejectedException("Field is not a list: " + key);
        }
        List<String> result = new ArrayList<>();
        for (Object item : (List<?>) value) {
            if (!(item instanceof String)) {
                throw new MutationRejectedException("List " + key + " holds a non-string value");
            }
            result.add((String) item);
        }
        return result;
    }

    /** Addresses lower-cased and sorted; a duplicate is rejected rather than silently merged. */
    static List<String> addressSet(Map<String, Object> json, String key) {
        TreeSet<String> result = new TreeSet<>();
        for (String value : strings(json, key)) {
            if (!result.add(address(value, key))) {
                throw new MutationRejectedException("Duplicate address in " + key + ": " + value);
            }
        }
        return List.copyOf(result);
    }
}
