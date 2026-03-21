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
package org.apache.jackrabbit.oak.segment.http.wallet;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.ConfigurationPolicy;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Service for Sling authors to propose signed write transactions to validators.
 * 
 * <p>This service allows Sling author instances to:
 * - Create content write proposals
 * - Sign proposals with their Ethereum wallet
 * - Submit signed transactions to validators for consensus</p>
 * 
 * <p><strong>Signed Transaction Flow:</strong></p>
 * <ol>
 *   <li>Sling author creates write proposal (contentType, message)</li>
 *   <li>Service signs transaction with wallet private key</li>
 *   <li>Signed transaction submitted to validator</li>
 *   <li>Validator verifies signature using wallet public key</li>
 *   <li>If valid, validator processes write via consensus</li>
 * </ol>
 */
@Component(
    service = SlingWriteProposalService.class,
    configurationPolicy = ConfigurationPolicy.OPTIONAL,
    immediate = true
)
@Designate(ocd = SlingWriteProposalService.Configuration.class)
public class SlingWriteProposalService {
    
    private static final Logger log = LoggerFactory.getLogger(SlingWriteProposalService.class);
    
    @ObjectClassDefinition(
        name = "Sling Author Write Proposal Service",
        description = "Service for proposing signed write transactions to validators"
    )
    @interface Configuration {
        @AttributeDefinition(
            name = "Validator URL",
            description = "URL of the validator to submit write proposals to (e.g., http://oak-global-store:8090)"
        )
        String validatorUrl() default "http://oak-global-store:8090";
        
        @AttributeDefinition(
            name = "Client ID",
            description = "Client identifier for this Sling author instance"
        )
        String clientId() default "";
        
        @AttributeDefinition(
            name = "Enabled",
            description = "Enable write proposal functionality"
        )
        boolean enabled() default true;
    }
    
    @Reference
    private volatile SlingAuthorWalletService walletService;
    
    private String validatorUrl;
    private String clientId;
    private boolean enabled;
    private volatile ValidatorProposalClient proposalClient;
    
    @Activate
    protected void activate(Configuration config) {
        this.validatorUrl = config.validatorUrl();
        this.clientId = config.clientId();
        this.enabled = config.enabled();
        
        if (!enabled) {
            log.info("Sling Write Proposal Service disabled");
            return;
        }
        
        // Determine client ID if not provided
        if (clientId == null || clientId.isEmpty()) {
            String hostname = System.getenv("HOSTNAME");
            if (hostname != null && !hostname.isEmpty()) {
                clientId = hostname;
            } else {
                clientId = System.getProperty("sling.instance.id", "sling-author");
            }
        }
        
        log.info("Sling Write Proposal Service activated");
        log.info("  Validator URL: {}", validatorUrl);
        log.info("  Client ID: {}", clientId);
        if (walletService != null && walletService.isAvailable()) {
            log.info("  Wallet Address: {}", walletService.getWalletAddress());
        } else {
            log.warn("  Wallet Service: Not available (writes will fail)");
        }
        proposalClient = new ValidatorProposalClient(validatorUrl, clientId);
    }
    
    /**
     * Propose a signed write transaction.
     * 
     * <p>The transaction is signed with the Sling author's wallet private key.
     * The validator will verify the signature before processing.</p>
     * 
     * @param contentType Content type (e.g., "page", "asset")
     * @param message Content message/description
     * @return Write proposal result (success/failure)
     */
    public WriteResult proposeWrite(String contentType, String message) {
        if (!enabled) {
            return new WriteResult(false, "Write proposal service is disabled");
        }
        
        if (walletService == null || !walletService.isAvailable()) {
            return new WriteResult(false, "Wallet service not available");
        }
        
        String walletAddress = walletService.getWalletAddress();
        if (walletAddress == null || walletAddress.isEmpty()) {
            return new WriteResult(false, "Wallet address not available");
        }
        
        try {
            // Create transaction message to sign
            // Format: walletAddress:timestamp:contentType:message
            long timestamp = System.currentTimeMillis();
            String transactionMessage = String.format("%s:%d:%s:%s", 
                walletAddress, timestamp, contentType, message);
            
            // Sign transaction with wallet private key
            String signature = walletService.sign(transactionMessage);
            
            if (signature == null) {
                return new WriteResult(false, "Failed to sign write transaction");
            }
            
            log.info("Submitting signed write transaction");
            log.debug("  Wallet: {}", walletAddress);
            log.debug("  Content Type: {}", contentType);
            log.debug("  Message: {}", message);
            log.debug("  Signature: {}...{}", 
                signature.substring(0, Math.min(10, signature.length())),
                signature.length() > 10 ? signature.substring(signature.length() - 4) : "");
            
            Map<String, String> params = new LinkedHashMap<>();
            params.put("wallet", walletAddress);
            params.put("signature", signature);
            params.put("message", message);
            params.put("contentType", contentType);
            params.put("clientId", clientId);
            params.put("timestamp", Long.toString(timestamp));

            ValidatorProposalClient.Response response = getProposalClient().post("/v1/propose-write", params);

            if (response.isSuccessStatus()) {
                log.info("Write transaction accepted by validator");
                return new WriteResult(true, "Write transaction accepted", response.body);
            } else {
                log.warn("Write transaction rejected: HTTP {} - {}", response.statusCode, response.body);
                return new WriteResult(false, "Write transaction rejected: " + response.body);
            }
            
        } catch (Exception e) {
            log.error("Failed to propose signed write transaction", e);
            return new WriteResult(false, "Failed to propose write: " + e.getMessage());
        }
    }
    
    /**
     * Result of a write proposal.
     */
    public static class WriteResult {
        public final boolean success;
        public final String message;
        public final String responseBody;
        
        public WriteResult(boolean success, String message) {
            this(success, message, null);
        }
        
        public WriteResult(boolean success, String message, String responseBody) {
            this.success = success;
            this.message = message;
            this.responseBody = responseBody;
        }
    }

    private ValidatorProposalClient getProposalClient() {
        ValidatorProposalClient client = proposalClient;
        if (client == null) {
            client = new ValidatorProposalClient(validatorUrl, clientId);
            proposalClient = client;
        }
        return client;
    }
}
