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
package org.apache.jackrabbit.oak.segment.consensus.servlets;

import org.apache.jackrabbit.oak.segment.consensus.Proposal;
import org.apache.jackrabbit.oak.segment.consensus.Vote;
import org.apache.jackrabbit.oak.segment.consensus.Validator;
import org.apache.jackrabbit.oak.segment.consensus.impl.BasicValidator;
import org.apache.jackrabbit.oak.segment.consensus.impl.SimpleProposal;
import org.apache.jackrabbit.oak.segment.consensus.wallet.Wallet;
import org.osgi.service.component.annotations.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Servlet to publish content to the Blockchain AEM global store.
 * <p>
 * This servlet demonstrates the consensus flow:
 * 1. Creates a wallet-signed proposal
 * 2. Validates it through the consensus engine
 * 3. Writes to /oak-chain/content/<wallet-uuid>/...
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/blockchain/publish",
        "sling.servlet.methods=POST"
    }
)
public class PublishToChainServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(PublishToChainServlet.class);
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String contentPath = request.getParameter("path");
        String title = request.getParameter("title");
        String content = request.getParameter("content");

        if (contentPath == null || contentPath.isEmpty()) {
            sendError(response, 400, "Missing required parameter: path");
            return;
        }

        try {
            // 1. Create or load wallet (for POC, create a new one each time)
            Wallet wallet = new Wallet();
            UUID walletId = wallet.getWalletId();

            LOG.info("Publishing content to chain - Wallet: {}", walletId);

            // 2. Determine target path in global store
            String targetPath = "/oak-chain/content/" + walletId + contentPath;

            // 3. Create proposal using Builder
            // Create mock segment IDs
            List<UUID> segmentIds = new ArrayList<>();
            segmentIds.add(UUID.randomUUID());

            Proposal proposal = new SimpleProposal.Builder()
                .targetPath(targetPath)
                .segmentIds(segmentIds)
                .paymentProof("POC-no-payment")
                .estimatedSize(content != null ? content.length() : 0)
                .signAndBuild(wallet);

            UUID proposalUuid = proposal.getProposalId();

            // 4. Validate through consensus (single validator for POC)
            Validator validator = new BasicValidator(wallet);
            Vote vote = validator.validate(proposal);

            boolean approved = (vote.getDecision() == Vote.Decision.APPROVE);
            LOG.info("Proposal {} - Vote: {} ({})", proposalUuid, approved, vote.getReason());

            // 5. If approved, write to global store (simulated for POC)
            if (approved) {
                // In full implementation, this would:
                // - Connect to global store
                // - Write the content nodes
                // - Broadcast to network

                LOG.info("✅ Content published to: {}", targetPath);

                sendSuccess(response, proposalUuid.toString(), walletId.toString(), targetPath);
            } else {
                sendError(response, 403, "Proposal rejected: " + vote.getReason());
            }

        } catch (Exception e) {
            LOG.error("Error publishing to chain", e);
            sendError(response, 500, "Internal error: " + e.getMessage());
        }
    }

    private void sendSuccess(HttpServletResponse response, String proposalId, String walletId, String targetPath) throws IOException {
        PrintWriter out = response.getWriter();
        out.println("{");
        out.println("  \"success\": true,");
        out.println("  \"proposalId\": \"" + proposalId + "\",");
        out.println("  \"walletId\": \"" + walletId + "\",");
        out.println("  \"path\": \"" + targetPath + "\",");
        out.println("  \"message\": \"Content published successfully\"");
        out.println("}");
    }

    private void sendError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        PrintWriter out = response.getWriter();
        out.println("{");
        out.println("  \"success\": false,");
        out.println("  \"error\": \"" + escapeJson(message) + "\"");
        out.println("}");
    }

    private String escapeJson(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}

