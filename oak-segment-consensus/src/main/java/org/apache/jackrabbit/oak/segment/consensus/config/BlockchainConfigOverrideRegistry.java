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
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runtime override registry for BlockchainConfig values injected by OSGi.
 */
final class BlockchainConfigOverrideRegistry {

    private static final AtomicReference<Map<String, String>> OVERRIDES =
        new AtomicReference<>(Collections.emptyMap());

    private BlockchainConfigOverrideRegistry() {
    }

    static void setOverrides(Map<String, String> overrides) {
        OVERRIDES.set(Collections.unmodifiableMap(new LinkedHashMap<>(overrides)));
    }

    static void clear() {
        OVERRIDES.set(Collections.emptyMap());
    }

    static String get(String sysProp) {
        return OVERRIDES.get().get(sysProp);
    }
}
