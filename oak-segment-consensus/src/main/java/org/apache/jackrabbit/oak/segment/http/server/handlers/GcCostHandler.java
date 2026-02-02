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

import org.apache.jackrabbit.oak.segment.consensus.gc.GCCostEstimate;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;
import org.apache.jackrabbit.oak.segment.http.server.util.FormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Handler for GC cost estimation (`/v1/gc/estimate`).
 */
public class GcCostHandler {

    private static final Logger log = LoggerFactory.getLogger(GcCostHandler.class);

    private final ServerContext context;

    public GcCostHandler(ServerContext context) {
        this.context = context;
    }

    /**
     * Handle GET /v1/gc/estimate - GC cost estimation endpoint.
     */
    public void handleGCCostEstimate(HttpServletRequest request, HttpServletResponse response) throws IOException {
        if (context.gcCostEstimator == null) {
            log.warn("GC Cost Estimator not available - endpoint disabled");
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                "GC Cost Estimator not available");
            return;
        }

        try {
            String targetRevision = request.getParameter("revision");
            log.debug("GC cost estimation request - revision: {}", targetRevision != null ? targetRevision : "HEAD");

            GCCostEstimate estimate = context.gcCostEstimator.estimateCost(targetRevision);

            log.info("GC cost estimation complete - reclaimable: {} MB ({}%), cost: {} USDC",
                estimate.getReclaimableSizeMB(),
                String.format("%.2f", estimate.getReclaimablePercentage()),
                estimate.getEstimatedCostUSDC());

            // Convert to JSON
            response.setContentType("application/json");
            response.setStatus(HttpServletResponse.SC_OK);

            StringBuilder json = new StringBuilder("{");
            json.append("\"reclaimableSegmentCount\":").append(estimate.getReclaimableSegmentCount()).append(",");
            json.append("\"reclaimableSizeBytes\":").append(estimate.getReclaimableSizeBytes()).append(",");
            json.append("\"reclaimableSizeMB\":").append(estimate.getReclaimableSizeMB()).append(",");
            json.append("\"reclaimablePercentage\":").append(String.format("%.2f", estimate.getReclaimablePercentage())).append(",");
            json.append("\"totalSegmentCount\":").append(estimate.getTotalSegmentCount()).append(",");
            json.append("\"totalSizeBytes\":").append(estimate.getTotalSizeBytes()).append(",");
            json.append("\"totalSizeMB\":").append(estimate.getTotalSizeMB()).append(",");
            json.append("\"estimatedCostUSDC\":\"").append(estimate.getEstimatedCostUSDC()).append("\",");

            // Reclaimable by TAR file
            json.append("\"reclaimableByTarFile\":{");
            boolean first = true;
            for (java.util.Map.Entry<String, Long> entry : estimate.getReclaimableByTarFile().entrySet()) {
                if (!first) json.append(",");
                first = false;
                json.append("\"").append(FormatUtils.escapeJson(entry.getKey())).append("\":").append(entry.getValue());
            }
            json.append("}");

            json.append("}");

            response.getWriter().write(json.toString());

        } catch (IllegalArgumentException e) {
            // Invalid revision format
            log.warn("Invalid revision format: {}", request.getParameter("revision"), e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_BAD_REQUEST,
                "Invalid revision format: " + FormatUtils.escapeJson(e.getMessage()));

        } catch (IOException e) {
            // Graph traversal failed
            log.error("GC cost estimation failed", e);
            ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                "GC cost estimation failed: " + FormatUtils.escapeJson(e.getMessage()));
        }
    }
}
