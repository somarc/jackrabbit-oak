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
import org.jetbrains.annotations.NotNull;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * One enterprise's row in the federation directory: its status, its cluster's signer set and member
 * endpoints, the namespaces (address roots) it owns and the wallets allowed to write into them.
 */
public final class DirectoryEntry {

    public enum Status {
        PENDING, ACTIVE, SUSPENDED, RETIRED
    }

    static final String GENESIS_NAMESPACE = "0x0000000000000000000000000000000000000000";

    private final String enterpriseId;
    private final Status status;
    private final String clusterId;
    private final String configDigest;
    private final List<String> signers;
    private final long threshold;
    private final List<String> endpoints;
    private final List<String> namespaces;
    private final List<String> writers;
    private final String paymentContract;

    private DirectoryEntry(Map<String, Object> json) {
        enterpriseId = FederationJson.string(json, "enterpriseId").toLowerCase(Locale.ROOT);
        if (!FederationJson.BYTES32.matcher(enterpriseId).matches()) {
            throw new MutationRejectedException("enterpriseId must be 0x-prefixed 32-byte hex: " + enterpriseId);
        }
        try {
            status = Status.valueOf(FederationJson.string(json, "status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new MutationRejectedException("Unknown directory status: " + json.get("status"), e);
        }
        clusterId = FederationJson.optionalBytes32(json, "clusterId");
        configDigest = FederationJson.optionalBytes32(json, "configDigest");
        LinkedHashSet<String> signerSet = new LinkedHashSet<>();
        for (String signer : FederationJson.strings(json, "signers")) {
            if (!signerSet.add(FederationJson.address(signer, "signers"))) {
                throw new MutationRejectedException("Duplicate signer: " + signer);
            }
        }
        signers = List.copyOf(signerSet);
        threshold = json.containsKey("threshold") ? FederationJson.nonNegativeLong(json, "threshold") : 0L;
        if (!signers.isEmpty() && (threshold * 2 <= signers.size() || threshold > signers.size())) {
            throw new MutationRejectedException("threshold " + threshold + " is not a majority of "
                + signers.size() + " signers");
        }
        List<String> urls = new ArrayList<>();
        for (String endpoint : FederationJson.strings(json, "endpoints")) {
            urls.add(endpoint(endpoint));
        }
        endpoints = List.copyOf(urls);
        namespaces = FederationJson.addressSet(json, "namespaces");
        if (namespaces.contains(GENESIS_NAMESPACE)) {
            throw new MutationRejectedException("The genesis namespace cannot be claimed by an enterprise");
        }
        writers = FederationJson.addressSet(json, "writers");
        String payment = FederationJson.optionalString(json, "paymentContract");
        paymentContract = payment.isEmpty() ? "" : FederationJson.address(payment, "paymentContract");
    }

    @NotNull
    public static DirectoryEntry fromMap(Map<String, Object> json) {
        return new DirectoryEntry(json);
    }

    private static String endpoint(String value) {
        URI uri;
        try {
            uri = URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new MutationRejectedException("Invalid endpoint: " + value, e);
        }
        String scheme = uri.getScheme();
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
            || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new MutationRejectedException("Endpoint must be an http(s) base URL: " + value);
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> json = new TreeMap<>();
        json.put("enterpriseId", enterpriseId);
        json.put("status", status.name());
        json.put("clusterId", clusterId);
        json.put("configDigest", configDigest);
        json.put("signers", signers);
        json.put("threshold", threshold);
        json.put("endpoints", endpoints);
        json.put("namespaces", namespaces);
        json.put("writers", writers);
        json.put("paymentContract", paymentContract);
        return json;
    }

    /** The namespace's own wallet and the enterprise's listed writers may write into it. */
    public boolean mayWrite(String signer, String namespace) {
        return signer.equals(namespace) || writers.contains(signer);
    }

    public String getEnterpriseId() {
        return enterpriseId;
    }

    public Status getStatus() {
        return status;
    }

    public String getClusterId() {
        return clusterId;
    }

    public String getConfigDigest() {
        return configDigest;
    }

    public List<String> getSigners() {
        return signers;
    }

    public long getThreshold() {
        return threshold;
    }

    public List<String> getEndpoints() {
        return endpoints;
    }

    public List<String> getNamespaces() {
        return namespaces;
    }

    public List<String> getWriters() {
        return writers;
    }

    public String getPaymentContract() {
        return paymentContract;
    }
}
