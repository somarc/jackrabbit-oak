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

import java.net.URI;
import java.time.Duration;
import java.util.Map;

/**
 * IPFS Cluster settings. Replication is off unless an endpoint is configured.
 *
 * <p>With replication on, a write is acknowledged only after at least
 * {@code replicationMin} cluster peers report the content pinned, so losing
 * fewer peers than that cannot lose an acknowledged binary. The authorization
 * value is sent as the {@code Authorization} header (Basic or Bearer, as the
 * cluster's REST API is configured) and never included in
 * {@link #toString()}.</p>
 *
 * <p>Cluster pins carry no owner, so deleting a record leaves its cluster pin
 * unless {@code unpinOnDelete} declares the cluster dedicated to this Oak
 * repository.</p>
 */
record IpfsClusterSettings(String endpoint, String authorization, int replicationMin, int replicationMax,
                           Duration pinTimeout, boolean unpinOnDelete) {

    static final int DEFAULT_REPLICATION_MIN = 2;
    static final int DEFAULT_REPLICATION_MAX = 3;
    static final Duration DEFAULT_PIN_TIMEOUT = Duration.ofMinutes(2);

    static final String ENDPOINT = "ipfsClusterEndpoint";
    static final String AUTHORIZATION = "ipfsClusterAuthorization";
    static final String REPLICATION_MIN = "ipfsClusterReplicationMin";
    static final String REPLICATION_MAX = "ipfsClusterReplicationMax";
    static final String PIN_TIMEOUT_MILLIS = "ipfsClusterPinTimeoutMillis";
    static final String UNPIN_ON_DELETE = "ipfsClusterUnpinOnDelete";

    IpfsClusterSettings {
        endpoint = blankToNull(endpoint);
        authorization = blankToNull(authorization);
        if (replicationMin < 1 || replicationMax < replicationMin) {
            throw new IllegalArgumentException(
                "IPFS Cluster replication needs 1 <= ipfsClusterReplicationMin <= ipfsClusterReplicationMax");
        }
        if (pinTimeout.isNegative() || pinTimeout.isZero()) {
            throw new IllegalArgumentException("ipfsClusterPinTimeoutMillis must be positive");
        }
        IpfsClientSettings.requireNoControlCharacters(authorization, AUTHORIZATION);
        if (endpoint != null) {
            IpfsClientSettings.serviceBase(endpoint);
        }
    }

    static IpfsClusterSettings fromProperties(Map<?, ?> properties) {
        return new IpfsClusterSettings(
            string(properties, ENDPOINT),
            string(properties, AUTHORIZATION),
            toInt(REPLICATION_MIN, number(properties, REPLICATION_MIN, DEFAULT_REPLICATION_MIN)),
            toInt(REPLICATION_MAX, number(properties, REPLICATION_MAX, DEFAULT_REPLICATION_MAX)),
            Duration.ofMillis(number(properties, PIN_TIMEOUT_MILLIS, DEFAULT_PIN_TIMEOUT.toMillis())),
            Boolean.parseBoolean(blankToNull(string(properties, UNPIN_ON_DELETE))));
    }

    static IpfsClusterSettings disabled() {
        return new IpfsClusterSettings(null, null, DEFAULT_REPLICATION_MIN, DEFAULT_REPLICATION_MAX,
            DEFAULT_PIN_TIMEOUT, false);
    }

    boolean enabled() {
        return endpoint != null;
    }

    /**
     * Returns the REST API base URI, ending in {@code /}.
     */
    URI apiBase() {
        return IpfsClientSettings.serviceBase(endpoint);
    }

    @Override
    public String toString() {
        return "IpfsClusterSettings[endpoint=" + endpoint
            + ", authorization=" + (authorization == null ? "none" : "<redacted>")
            + ", replicationMin=" + replicationMin + ", replicationMax=" + replicationMax
            + ", pinTimeout=" + pinTimeout + ", unpinOnDelete=" + unpinOnDelete + "]";
    }

    private static int toInt(String key, long value) {
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is out of range");
        }
        return (int) value;
    }

    private static String string(Map<?, ?> properties, String key) {
        Object value = properties.get(key);
        return value == null ? null : value.toString();
    }

    private static long number(Map<?, ?> properties, String key, long defaultValue) {
        String value = blankToNull(string(properties, key));
        if (value == null) {
            return defaultValue;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, not " + value, e);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.trim().isEmpty() ? null : value.trim();
    }
}
