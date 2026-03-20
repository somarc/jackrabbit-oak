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
package org.apache.jackrabbit.oak.segment.consensus.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared runtime override registry for optional OSGi-provided property values.
 */
public final class RuntimePropertyOverrideRegistry {

    private static final ConcurrentHashMap<String, Map<String, String>> OVERRIDES_BY_NAMESPACE =
        new ConcurrentHashMap<>();
    private static final AtomicReference<Map<String, String>> MERGED_OVERRIDES =
        new AtomicReference<>(Collections.emptyMap());

    private RuntimePropertyOverrideRegistry() {
    }

    public static void setOverrides(String namespace, Map<String, String> overrides) {
        if (overrides == null || overrides.isEmpty()) {
            OVERRIDES_BY_NAMESPACE.remove(namespace);
        } else {
            OVERRIDES_BY_NAMESPACE.put(namespace, Collections.unmodifiableMap(new LinkedHashMap<>(overrides)));
        }
        rebuildMergedView();
    }

    public static void clear(String namespace) {
        OVERRIDES_BY_NAMESPACE.remove(namespace);
        rebuildMergedView();
    }

    public static String get(String key) {
        return MERGED_OVERRIDES.get().get(key);
    }

    private static void rebuildMergedView() {
        Map<String, String> merged = new LinkedHashMap<>();
        for (Map<String, String> overrides : OVERRIDES_BY_NAMESPACE.values()) {
            merged.putAll(overrides);
        }
        MERGED_OVERRIDES.set(Collections.unmodifiableMap(merged));
    }
}
