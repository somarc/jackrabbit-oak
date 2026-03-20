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
package org.apache.jackrabbit.oak.segment.consensus.queue;

import java.util.Locale;

enum AdaptiveReleaseMode {

    EPOCH("epoch"),
    ADAPTIVE_SHADOW("adaptive-shadow");

    private final String configValue;

    AdaptiveReleaseMode(String configValue) {
        this.configValue = configValue;
    }

    String configValue() {
        return configValue;
    }

    static AdaptiveReleaseMode fromValue(String value) {
        if (value == null) {
            return EPOCH;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT).replace('_', '-');
        if (normalized.isEmpty()) {
            return EPOCH;
        }
        if ("shadow".equals(normalized)) {
            return ADAPTIVE_SHADOW;
        }
        for (AdaptiveReleaseMode mode : values()) {
            if (mode.configValue.equals(normalized)) {
                return mode;
            }
        }
        return EPOCH;
    }
}
