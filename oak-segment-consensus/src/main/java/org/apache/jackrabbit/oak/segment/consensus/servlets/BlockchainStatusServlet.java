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

import org.apache.jackrabbit.oak.api.PropertyState;
import org.apache.jackrabbit.oak.api.Tree;
import org.apache.jackrabbit.oak.spi.mount.Mount;
import org.apache.jackrabbit.oak.spi.mount.MountInfoProvider;
import org.apache.jackrabbit.oak.spi.state.NodeState;
import org.apache.jackrabbit.oak.spi.state.NodeStore;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
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

/**
 * Servlet providing blockchain mount status and content listing.
 * Returns JSON with:
 * - Mount status (/oak-chain present or not)
 * - Sync state and liveness
 * - Published content from all participants
 */
@Component(
    service = Servlet.class,
    property = {
        "sling.servlet.paths=/bin/blockchain/status",
        "sling.servlet.methods=GET"
    }
)
public class BlockchainStatusServlet extends HttpServlet {

    private static final Logger LOG = LoggerFactory.getLogger(BlockchainStatusServlet.class);
    private static final long serialVersionUID = 1L;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private volatile MountInfoProvider mountInfoProvider;

    @Reference(cardinality = ReferenceCardinality.OPTIONAL)
    private volatile NodeStore nodeStore;

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {

        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");

        PrintWriter out = response.getWriter();
        out.println("{");

        // Mount status
        boolean hasMountProvider = (mountInfoProvider != null);
        boolean hasOakChainMount = false;
        
        if (hasMountProvider) {
            for (Mount mount : mountInfoProvider.getNonDefaultMounts()) {
                if ("oak-chain-global".equals(mount.getName())) {
                    hasOakChainMount = true;
                    break;
                }
            }
        }

        out.println("  \"mountStatus\": {");
        out.println("    \"configured\": " + hasOakChainMount + ",");
        out.println("    \"mountPath\": \"/oak-chain\",");
        out.println("    \"readOnly\": true");
        out.println("  },");

        // Sync status
        out.println("  \"syncStatus\": {");
        out.println("    \"lastSync\": " + System.currentTimeMillis() + ",");
        out.println("    \"status\": \"" + (hasOakChainMount ? "active" : "not-mounted") + "\",");
        out.println("    \"participants\": 2");
        out.println("  },");

        // Content listing
        out.println("  \"content\": [");
        
        List<String> contentPaths = new ArrayList<>();
        if (nodeStore != null && hasOakChainMount) {
            try {
                NodeState root = nodeStore.getRoot();
                NodeState oakChain = root.getChildNode("oak-chain");
                
                if (oakChain.exists()) {
                    NodeState content = oakChain.getChildNode("content");
                    if (content.exists()) {
                        // List all wallet directories
                        for (String walletId : content.getChildNodeNames()) {
                            NodeState walletNode = content.getChildNode(walletId);
                            // List content under each wallet
                            for (String contentName : walletNode.getChildNodeNames()) {
                                String path = "/oak-chain/content/" + walletId + "/" + contentName;
                                contentPaths.add(path);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOG.warn("Error reading oak-chain content", e);
            }
        }
        
        for (int i = 0; i < contentPaths.size(); i++) {
            out.print("    {\"path\": \"" + escapeJson(contentPaths.get(i)) + "\", \"status\": \"published\"}");
            if (i < contentPaths.size() - 1) {
                out.println(",");
            } else {
                out.println();
            }
        }
        
        out.println("  ]");
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

