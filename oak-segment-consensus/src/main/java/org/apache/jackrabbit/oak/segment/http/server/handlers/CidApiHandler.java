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
package org.apache.jackrabbit.oak.segment.http.server.handlers;

import org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore;
import org.apache.jackrabbit.oak.segment.consensus.config.IpfsGatewayUrls;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.binary.BlobUrlSigner;
import org.apache.jackrabbit.oak.segment.http.server.binary.CidMappingService;
import org.apache.jackrabbit.oak.segment.http.server.util.JsonOutputUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;

/**
 * HTTP API handler for CID (Content Identifier) operations.
 * 
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>GET /api/cid/{oakBlobId} - Get IPFS CID for Oak blob ID</li>
 *   <li>GET /api/cid/reverse/{ipfsCid} - Get Oak blob ID for IPFS CID</li>
 *   <li>GET /api/cid/gateway/{oakBlobId} - Get IPFS gateway URL</li>
 *   <li>GET /api/cid/stats - Get CID mapping statistics</li>
 * </ul>
 * 
 * <h2>Example Responses</h2>
 * <pre>
 * GET /api/cid/ed06f9cbf0fe878013ccb266170e6b3ba676933a6f065675cc0115c840bf1442
 * {
 *   "oakBlobId": "ed06f9cb...",
 *   "ipfsCid": "Qmf4F3CWU6Ly958TFiR8BRP18gwvW3Xsj2yXu5DqkonWc3",
 *   "gatewayUrl": "http://127.0.0.1:8099/ipfs/Qmf4F3...",
 *   "localUrl": "http://127.0.0.1:8099/ipfs/Qmf4F3..."
 * }
 * </pre>
 *
 * <p>CIDs come from this node's mappings or, for binaries stored through
 * another validator, the replicated binary index. Gateway URLs are built from
 * this node's settings when asked for, and left out when no gateway is
 * configured.</p>
 */
public class CidApiHandler {

    private static final Logger log = LoggerFactory.getLogger(CidApiHandler.class);

    private final ServerContext context;

    public CidApiHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle GET /api/cid/{oakBlobId}
     */
    public void handleGetCid(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || path.length() < 10) {
            ApiErrorUtil.sendJsonError(response, 400, "Missing Oak blob ID in path");
            return;
        }

        // Extract blob ID from path: /api/cid/{blobId}
        String oakBlobId = path.substring(path.lastIndexOf('/') + 1);

        if (context.cidMappingService == null && ipfsDataStore() == null) {
            ApiErrorUtil.sendJsonError(response, 503, "CID mapping service not available");
            return;
        }

        Optional<String> cid = resolveCidOrReport(oakBlobId, response);
        if (cid.isEmpty()) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("oakBlobId", oakBlobId);
        payload.put("ipfsCid", cid.get());
        String gatewayUrl = IpfsGatewayUrls.gatewayUrl(cid.get());
        if (gatewayUrl != null) {
            payload.put("gatewayUrl", gatewayUrl);
        }
        String localUrl = IpfsGatewayUrls.localGatewayUrl(cid.get());
        if (localUrl != null) {
            payload.put("localUrl", localUrl);
        }
        JsonOutputUtil.send(response, 200, JsonOutputUtil.toJson(payload));
    }

    /**
     * Handle GET /api/cid/reverse/{ipfsCid}
     */
    public void handleReverseLookup(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || !path.contains("/reverse/")) {
            ApiErrorUtil.sendJsonError(response, 400, "Missing IPFS CID in path");
            return;
        }

        String ipfsCid = path.substring(path.lastIndexOf('/') + 1);

        if (context.cidMappingService == null) {
            ApiErrorUtil.sendJsonError(response, 503, "CID mapping service not available");
            return;
        }

        Optional<String> oakBlobId = context.cidMappingService.getOakBlobId(ipfsCid);

        if (oakBlobId.isEmpty()) {
            ApiErrorUtil.sendJsonError(response, 404, "No Oak blob ID found for CID: " + ipfsCid);
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ipfsCid", ipfsCid);
        payload.put("oakBlobId", oakBlobId.get());
        JsonOutputUtil.send(response, 200, JsonOutputUtil.toJson(payload));
    }

    /**
     * Handle GET /api/cid/gateway/{oakBlobId}
     * Returns a redirect to the IPFS gateway.
     */
    public void handleGatewayRedirect(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getPathInfo();
        if (path == null || !path.contains("/gateway/")) {
            ApiErrorUtil.sendJsonError(response, 400, "Missing Oak blob ID in path");
            return;
        }

        String oakBlobId = path.substring(path.lastIndexOf('/') + 1);

        if (IpfsGatewayUrls.gatewayBase() == null) {
            ApiErrorUtil.sendJsonError(response, 404, "ipfs_gateway_not_configured",
                "No IPFS gateway is configured; download through /api/blob/{blobId}");
            return;
        }

        if (context.cidMappingService == null && ipfsDataStore() == null) {
            ApiErrorUtil.sendJsonError(response, 503, "CID mapping service not available");
            return;
        }

        Optional<String> cid = resolveCidOrReport(oakBlobId, response);
        if (cid.isEmpty()) {
            return;
        }

        // Redirect to IPFS gateway
        response.sendRedirect(IpfsGatewayUrls.gatewayUrl(cid.get()));
    }

    /**
     * Looks the CID up in this node's mappings, then through the IPFS
     * DataStore, which also consults the replicated binary index.
     */
    private Optional<String> resolveCid(String oakBlobId) {
        if (context.cidMappingService != null) {
            Optional<String> cid = context.cidMappingService.getCid(oakBlobId);
            if (cid.isPresent()) {
                return cid;
            }
        }
        IPFSDataStore dataStore = ipfsDataStore();
        String id = BlobUrlSigner.normalizeBlobId(oakBlobId);
        if (dataStore == null || id == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(dataStore.getCID(id));
        } catch (RuntimeException e) {
            log.warn("Could not resolve the CID of {}: {}", id, e.toString());
            throw new IpfsUnavailableException();
        }
    }

    /**
     * IPFS could not be asked, which is not the same as an unknown blob.
     */
    private static final class IpfsUnavailableException extends RuntimeException {
        private IpfsUnavailableException() {
            super(null, null, false, false);
        }
    }

    private Optional<String> resolveCidOrReport(String oakBlobId, HttpServletResponse response) throws IOException {
        try {
            Optional<String> cid = resolveCid(oakBlobId);
            if (cid.isEmpty()) {
                ApiErrorUtil.sendJsonError(response, 404, "No CID found for Oak blob ID: " + oakBlobId);
            }
            return cid;
        } catch (IpfsUnavailableException e) {
            ApiErrorUtil.sendJsonError(response, 503, "ipfs_unavailable", "The IPFS node could not be asked");
            return Optional.empty();
        }
    }

    private IPFSDataStore ipfsDataStore() {
        return context.ipfsDataStore();
    }

    /**
     * Handle GET /api/cid/stats
     */
    public void handleStats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (context.cidMappingService == null) {
            ApiErrorUtil.sendJsonError(response, 503, "CID mapping service not available");
            return;
        }

        CidMappingService.CidMappingStats stats = context.cidMappingService.getStats();
        JsonOutputUtil.send(response, 200, stats.toJson());
    }
}
