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
 * Resolves IPFS gateway URLs for the current runtime.
 *
 * <p>The CID is the durable content identity. Gateway URLs are environment-specific
 * delivery hints and must be configurable so local mock clusters can resolve
 * against a local Kubo gateway while shared/public modes can point at a public
 * gateway.</p>
 */
public final class IpfsGatewayUrls {

    public static final String GATEWAY_BASE_PROPERTY = "ipfs.gateway.base";
    public static final String GATEWAY_BASE_ENV = "IPFS_GATEWAY_BASE";
    public static final String LOCAL_GATEWAY_BASE_PROPERTY = "ipfs.local.gateway.base";
    public static final String LOCAL_GATEWAY_BASE_ENV = "IPFS_LOCAL_GATEWAY_BASE";

    public static final String DEFAULT_GATEWAY_BASE = "https://ipfs.io/ipfs/";
    public static final String DEFAULT_LOCAL_GATEWAY_BASE = "http://127.0.0.1:8099/ipfs/";

    private IpfsGatewayUrls() {
    }

    public static String gatewayBase() {
        return normalizeBase(RuntimeConfigValueResolver.readString(
            GATEWAY_BASE_PROPERTY,
            GATEWAY_BASE_ENV,
            DEFAULT_GATEWAY_BASE
        ));
    }

    public static String localGatewayBase() {
        return normalizeBase(RuntimeConfigValueResolver.readString(
            LOCAL_GATEWAY_BASE_PROPERTY,
            LOCAL_GATEWAY_BASE_ENV,
            DEFAULT_LOCAL_GATEWAY_BASE
        ));
    }

    public static String gatewayUrl(String cid) {
        return gatewayBase() + cid;
    }

    public static String localGatewayUrl(String cid) {
        return localGatewayBase() + cid;
    }

    private static String normalizeBase(String base) {
        if (!RuntimeConfigValueResolver.hasText(base)) {
            return DEFAULT_GATEWAY_BASE;
        }
        return base.endsWith("/") ? base : base + "/";
    }
}
