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

/**
 * Resolves runtime config values using OSGi override, then system property,
 * then optional environment variable, then default.
 */
public final class RuntimeConfigValueResolver {

    private RuntimeConfigValueResolver() {
    }

    public static String readString(String sysProp, String defaultValue) {
        String override = RuntimePropertyOverrideRegistry.get(sysProp);
        if (override != null) {
            return override;
        }
        String raw = System.getProperty(sysProp);
        return raw != null ? raw : defaultValue;
    }

    public static String readString(String sysProp, String envVar, String defaultValue) {
        String override = RuntimePropertyOverrideRegistry.get(sysProp);
        if (override != null) {
            return override;
        }
        String propValue = System.getProperty(sysProp);
        if (propValue != null) {
            return propValue;
        }
        String envValue = System.getenv(envVar);
        return envValue != null ? envValue : defaultValue;
    }

    public static String readStringEnvFirst(String sysProp, String envVar, String defaultValue) {
        String override = RuntimePropertyOverrideRegistry.get(sysProp);
        if (override != null) {
            return override;
        }
        String envValue = System.getenv(envVar);
        if (envValue != null) {
            return envValue;
        }
        String propValue = System.getProperty(sysProp);
        return propValue != null ? propValue : defaultValue;
    }

    public static boolean readBoolean(String sysProp, boolean defaultValue) {
        String raw = readString(sysProp, (String) null);
        if (!hasText(raw)) {
            return defaultValue;
        }
        return Boolean.parseBoolean(raw.trim());
    }

    public static int readInt(String sysProp, int defaultValue) {
        String raw = readString(sysProp, (String) null);
        if (!hasText(raw)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static int readInt(String sysProp, String envVar, int defaultValue) {
        String raw = readString(sysProp, envVar, null);
        if (!hasText(raw)) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static long readLong(String sysProp, long defaultValue) {
        String raw = readString(sysProp, (String) null);
        if (!hasText(raw)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static long readLongEnvFirst(String sysProp, String envVar, long defaultValue) {
        String raw = readStringEnvFirst(sysProp, envVar, null);
        if (!hasText(raw)) {
            return defaultValue;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    public static boolean hasConfiguredValue(String sysProp) {
        String override = RuntimePropertyOverrideRegistry.get(sysProp);
        if (override != null) {
            return hasText(override);
        }
        return hasText(System.getProperty(sysProp));
    }

    public static boolean hasConfiguredValue(String sysProp, String envVar) {
        String override = RuntimePropertyOverrideRegistry.get(sysProp);
        if (override != null) {
            return hasText(override);
        }
        return hasText(System.getProperty(sysProp)) || hasText(System.getenv(envVar));
    }

    public static boolean hasConfiguredValueEnvFirst(String sysProp, String envVar) {
        String override = RuntimePropertyOverrideRegistry.get(sysProp);
        if (override != null) {
            return hasText(override);
        }
        return hasText(System.getenv(envVar)) || hasText(System.getProperty(sysProp));
    }

    public static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
