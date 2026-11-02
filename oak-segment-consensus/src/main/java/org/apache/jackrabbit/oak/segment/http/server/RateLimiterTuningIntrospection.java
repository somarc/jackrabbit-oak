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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimePropertySourceRegistry;

/**
 * Read-only introspection helpers for effective rate-limiter tuning.
 */
public final class RateLimiterTuningIntrospection {

    static final String COMPONENT = "rateLimiterTuning";

    private RateLimiterTuningIntrospection() {
    }

    public static Map<String, Object> effectiveValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("enabled", RuntimeConfigValueResolver.readBoolean(RateLimiter.PROP_ENABLED, true));
        values.put("requests_per_second", RuntimeConfigValueResolver.readInt(
            RateLimiter.PROP_REQUESTS_PER_SECOND, RateLimiter.DEFAULT_REQUESTS_PER_SECOND));
        values.put("burst_size", RuntimeConfigValueResolver.readInt(RateLimiter.PROP_BURST_SIZE, RateLimiter.DEFAULT_BURST_SIZE));
        values.put("global_rps", RuntimeConfigValueResolver.readInt(RateLimiter.PROP_GLOBAL_RPS, RateLimiter.DEFAULT_GLOBAL_RPS));
        values.put("write_rps", RuntimeConfigValueResolver.readInt(RateLimiter.PROP_WRITE_RPS, RateLimiter.DEFAULT_WRITE_RPS));
        return values;
    }

    public static String source() {
        boolean configured = Stream.of(RateLimiter.PROP_ENABLED, RateLimiter.PROP_REQUESTS_PER_SECOND,
                RateLimiter.PROP_BURST_SIZE, RateLimiter.PROP_GLOBAL_RPS, RateLimiter.PROP_WRITE_RPS)
            .anyMatch(RuntimeConfigValueResolver::hasConfiguredValue);
        return RuntimePropertySourceRegistry.getSource(COMPONENT, configured ? "system-properties" : "defaults");
    }
}
