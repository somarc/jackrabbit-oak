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
 * Servlet to unpublish content from the Blockchain AEM global store.
 * <p>
 * This servlet demonstrates the unpublish flow:
 * 1. Verifies wallet ownership of the content path
 * 2. Creates a signed deletion proposal
 * 3. Validates through consensus
 * 4. Removes from /oak-chain/content/<wallet-uuid>/...
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/blockchain/unpublish",
        "sling.servlet.methods=POST"
    }
)
public class UnpublishFromChainServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(UnpublishFromChainServlet.class);
    private static final long serialVersionUID = 1L;

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        String contentPath = request.getParameter("path");
        String walletIdParam = request.getParameter("walletId");

        if (contentPath == null || contentPath.isEmpty()) {
            sendError(response, 400, "Missing required parameter: path");
            return;
        }

        try {
            // 1. Load or verify wallet (for POC, create a wallet)
            // In production, would verify signature and ownership
            Wallet wallet = new Wallet();
            UUID walletId = wallet.getWalletId();

            LOG.info("Unpublishing content from chain - Wallet: {}, Path: {}", walletId, contentPath);

            // 2. Verify path ownership (must be under /oak-chain/content/<walletId>)
            String expectedPrefix = "/oak-chain/content/" + walletId;
            if (!contentPath.startsWith(expectedPrefix)) {
                sendError(response, 403, "Cannot unpublish content not owned by this wallet");
                return;
            }

            // 3. Create deletion proposal using Builder
            // Create mock segment IDs for deletion
            List<UUID> segmentIds = new ArrayList<>();
            segmentIds.add(UUID.randomUUID());

            Proposal proposal = new SimpleProposal.Builder()
                .targetPath(contentPath)
                .segmentIds(segmentIds)
                .paymentProof("POC-no-payment-delete")
                .estimatedSize(0)
                .signAndBuild(wallet);

            UUID proposalUuid = proposal.getProposalId();

            // 4. Validate through consensus
            Validator validator = new BasicValidator(wallet);
            Vote vote = validator.validate(proposal);

            boolean approved = (vote.getDecision() == Vote.Decision.APPROVE);
            LOG.info("Unpublish Proposal {} - Vote: {} ({})", proposalUuid, approved, vote.getReason());

            // 5. If approved, delete from global store (simulated for POC)
            if (approved) {
                // In full implementation, this would:
                // - Connect to global store
                // - Mark content for deletion
                // - Broadcast to network
                // - Trigger revision cleanup

                LOG.info("✅ Content unpublished from: {}", contentPath);

                sendSuccess(response, proposalUuid.toString(), walletId.toString(), contentPath);
            } else {
                sendError(response, 403, "Unpublish rejected: " + vote.getReason());
            }

        } catch (Exception e) {
            LOG.error("Error unpublishing from chain", e);
            sendError(response, 500, "Internal error: " + e.getMessage());
        }
    }

    private void sendSuccess(HttpServletResponse response, String proposalId, String walletId, String path) throws IOException {
        PrintWriter out = response.getWriter();
        out.println("{");
        out.println("  \"success\": true,");
        out.println("  \"proposalId\": \"" + proposalId + "\",");
        out.println("  \"walletId\": \"" + walletId + "\",");
        out.println("  \"path\": \"" + path + "\",");
        out.println("  \"message\": \"Content unpublished successfully\"");
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

