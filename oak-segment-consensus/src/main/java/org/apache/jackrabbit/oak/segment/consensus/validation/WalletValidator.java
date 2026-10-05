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
package org.apache.jackrabbit.oak.segment.consensus.validation;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Validator for Ethereum wallet addresses.
 * 
 * <p>Validates that wallet addresses conform to Ethereum address format:
 * <ul>
 *   <li>Must start with "0x" prefix</li>
 *   <li>Must be 42 characters total (0x + 40 hex chars)</li>
 *   <li>Must contain only valid hexadecimal characters after prefix</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * ValidationResult<String> result = WalletValidator.validate(walletAddress);
 * if (!result.isValid()) {
 *     response.sendError(result.getHttpStatus(), result.getError());
 *     return;
 * }
 * String normalizedWallet = result.getNormalizedValue(); // lowercase
 * }</pre>
 * 
 * <p><strong>Normalization:</strong>
 * Valid addresses are normalized to lowercase for consistent storage and comparison.
 * 
 * @see ValidationResult
 */
public final class WalletValidator {
    
    /** Standard Ethereum address length (0x + 40 hex chars) */
    public static final int ETHEREUM_ADDRESS_LENGTH = 42;
    
    /** Minimum acceptable address length (for partial validation) */
    public static final int MIN_ADDRESS_LENGTH = 10;
    
    /** Ethereum address prefix */
    public static final String ADDRESS_PREFIX = "0x";
    
    /** Pattern for valid hex characters */
    private static final Pattern HEX_PATTERN = Pattern.compile("^[a-fA-F0-9]+$");
    
    
    // Private constructor - utility class
    private WalletValidator() {
    }
    
    /**
     * Validate an Ethereum wallet address.
     * 
     * <p>Performs the following checks:
     * <ol>
     *   <li>Not null or empty</li>
     *   <li>Starts with "0x" prefix</li>
     *   <li>At least 10 characters (minimum for partial addresses)</li>
     *   <li>Contains only valid hex characters after prefix</li>
     * </ol>
     * 
     * <p>If valid, returns the address normalized to lowercase.
     * 
     * @param wallet the wallet address to validate
     * @return validation result with normalized address or error
     */
    @NotNull
    public static ValidationResult<String> validate(@Nullable String wallet) {
        // Check for null or empty
        if (wallet == null || wallet.isEmpty()) {
            return ValidationResult.error(
                "Missing wallet address. Please provide a valid Ethereum address (0x...)."
            );
        }
        
        // Trim whitespace
        String trimmed = wallet.trim();
        
        // Check prefix
        if (!trimmed.startsWith(ADDRESS_PREFIX)) {
            return ValidationResult.error(
                "Invalid wallet address format. Must start with '0x' prefix. Got: " + 
                truncateForError(trimmed)
            );
        }
        
        // Check minimum length
        if (trimmed.length() < MIN_ADDRESS_LENGTH) {
            return ValidationResult.error(
                "Invalid wallet address format. Address too short (minimum " + 
                MIN_ADDRESS_LENGTH + " characters). Got: " + trimmed.length() + " characters."
            );
        }
        
        // Extract hex portion (after 0x)
        String hexPart = trimmed.substring(2);
        
        // Validate hex characters
        if (!HEX_PATTERN.matcher(hexPart).matches()) {
            return ValidationResult.error(
                "Invalid wallet address. Contains non-hexadecimal characters after '0x' prefix."
            );
        }
        
        // Normalize to lowercase
        String normalized = trimmed.toLowerCase();
        
        return ValidationResult.valid(normalized);
    }
    
    /**
     * Normalize a wallet address to lowercase.
     * 
     * <p>Does NOT validate - use {@link #validate(String)} if validation is needed.
     * 
     * @param wallet the wallet address
     * @return lowercase version, or null if input is null
     */
    @Nullable
    public static String normalize(@Nullable String wallet) {
        if (wallet == null) {
            return null;
        }
        return wallet.trim().toLowerCase();
    }
    
    /**
     * Truncate a value for safe inclusion in error messages.
     * 
     * @param value the value to truncate
     * @return truncated value (max 20 chars)
     */
    private static String truncateForError(String value) {
        if (value == null) {
            return "null";
        }
        if (value.length() <= 20) {
            return value;
        }
        return value.substring(0, 17) + "...";
    }
}
