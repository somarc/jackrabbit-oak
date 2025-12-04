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
package org.apache.jackrabbit.oak.spi.security.authentication.web3;

import org.jetbrains.annotations.NotNull;

import java.io.Serializable;
import java.security.Principal;
import java.util.Objects;

/**
 * Principal representing an authenticated Ethereum wallet address.
 * 
 * <p>This principal can be used in Oak's ACL system for wallet-based permissions:</p>
 * <pre>
 * // Example: Grant wallet address read access to content
 * /content/user-data/0x1234...
 *   jcr:mixinTypes=[rep:AccessControllable]
 *   rep:policy [rep:ACL]
 *     allow [rep:GrantACE]
 *       rep:principalName="0x1234567890abcdef1234567890abcdef12345678"
 *       rep:privileges=[jcr:read,jcr:write]
 * </pre>
 * 
 * <p>The wallet address is stored in checksummed EIP-55 format (mixed case)
 * but comparisons are case-insensitive following Ethereum conventions.</p>
 * 
 * @see <a href="https://eips.ethereum.org/EIPS/eip-55">EIP-55: Mixed-case checksum address encoding</a>
 */
public final class Web3Principal implements Principal, Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * Ethereum wallet address (40 hex characters, 0x-prefixed).
     * Example: 0x1234567890abcdef1234567890abcdef12345678
     */
    private final String walletAddress;
    
    /**
     * Creates a new Web3Principal for the given Ethereum wallet address.
     * 
     * @param walletAddress Ethereum address (must be 0x-prefixed, 42 characters total)
     * @throws IllegalArgumentException if address format is invalid
     */
    public Web3Principal(@NotNull String walletAddress) {
        Objects.requireNonNull(walletAddress, "Wallet address cannot be null");
        
        // Validate Ethereum address format: 0x followed by 40 hex characters
        if (!isValidEthereumAddress(walletAddress)) {
            throw new IllegalArgumentException(
                "Invalid Ethereum address format: " + walletAddress + 
                " (expected 0x followed by 40 hex characters)"
            );
        }
        
        this.walletAddress = walletAddress;
    }
    
    /**
     * Returns the wallet address. This is the principal's name in Oak's security system.
     * 
     * @return Ethereum wallet address (0x-prefixed, lowercase)
     */
    @Override
    @NotNull
    public String getName() {
        return walletAddress.toLowerCase();
    }
    
    /**
     * Returns the wallet address (same as {@link #getName()}).
     * 
     * @return Ethereum wallet address
     */
    @NotNull
    public String getWalletAddress() {
        return walletAddress;
    }
    
    /**
     * Two Web3Principals are equal if their wallet addresses match (case-insensitive).
     * 
     * @param obj object to compare
     * @return true if both represent the same wallet address
     */
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof Web3Principal)) {
            return false;
        }
        Web3Principal other = (Web3Principal) obj;
        // Case-insensitive comparison following Ethereum conventions
        return this.walletAddress.equalsIgnoreCase(other.walletAddress);
    }
    
    /**
     * Hash code based on lowercase wallet address for case-insensitive equality.
     * 
     * @return hash code
     */
    @Override
    public int hashCode() {
        return walletAddress.toLowerCase().hashCode();
    }
    
    /**
     * String representation for debugging.
     * 
     * @return string like "Web3Principal[0x1234...]"
     */
    @Override
    public String toString() {
        return "Web3Principal[" + walletAddress + "]";
    }
    
    /**
     * Validates Ethereum address format.
     * 
     * @param address address to validate
     * @return true if valid format (0x + 40 hex chars)
     */
    private static boolean isValidEthereumAddress(String address) {
        if (address == null || address.length() != 42) {
            return false;
        }
        if (!address.startsWith("0x")) {
            return false;
        }
        // Check if remaining 40 characters are valid hex
        String hexPart = address.substring(2);
        return hexPart.matches("[0-9a-fA-F]{40}");
    }
}

