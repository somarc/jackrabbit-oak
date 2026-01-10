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

/**
 * Result of a validation operation.
 * 
 * <p>Encapsulates success/failure state along with:
 * <ul>
 *   <li>Error message (if validation failed)</li>
 *   <li>Normalized value (if validation succeeded)</li>
 *   <li>HTTP status code suggestion</li>
 * </ul>
 * 
 * <p><strong>Usage:</strong>
 * <pre>{@code
 * ValidationResult result = WalletValidator.validate(wallet);
 * if (!result.isValid()) {
 *     response.sendError(result.getHttpStatus(), result.getError());
 *     return;
 * }
 * String normalizedWallet = result.getNormalizedValue();
 * }</pre>
 * 
 * @param <T> the type of the normalized value
 */
public class ValidationResult<T> {
    
    private final boolean valid;
    private final String error;
    private final T normalizedValue;
    private final int httpStatus;
    
    private ValidationResult(boolean valid, String error, T normalizedValue, int httpStatus) {
        this.valid = valid;
        this.error = error;
        this.normalizedValue = normalizedValue;
        this.httpStatus = httpStatus;
    }
    
    /**
     * Create a successful validation result with a normalized value.
     * 
     * @param normalizedValue the validated and normalized value
     * @param <T> the type of the value
     * @return a valid result
     */
    @NotNull
    public static <T> ValidationResult<T> valid(@NotNull T normalizedValue) {
        return new ValidationResult<>(true, null, normalizedValue, 200);
    }
    
    /**
     * Create a failed validation result with an error message.
     * 
     * @param error the error message
     * @param <T> the type of the expected value
     * @return an invalid result with HTTP 400 status
     */
    @NotNull
    public static <T> ValidationResult<T> error(@NotNull String error) {
        return new ValidationResult<>(false, error, null, 400);
    }
    
    /**
     * Create a failed validation result with an error message and custom HTTP status.
     * 
     * @param error the error message
     * @param httpStatus the HTTP status code to suggest
     * @param <T> the type of the expected value
     * @return an invalid result
     */
    @NotNull
    public static <T> ValidationResult<T> error(@NotNull String error, int httpStatus) {
        return new ValidationResult<>(false, error, null, httpStatus);
    }
    
    /**
     * Check if validation passed.
     * 
     * @return true if valid, false otherwise
     */
    public boolean isValid() {
        return valid;
    }
    
    /**
     * Get the error message (if validation failed).
     * 
     * @return error message, or null if valid
     */
    @Nullable
    public String getError() {
        return error;
    }
    
    /**
     * Get the normalized value (if validation passed).
     * 
     * @return normalized value, or null if invalid
     */
    @Nullable
    public T getNormalizedValue() {
        return normalizedValue;
    }
    
    /**
     * Get the suggested HTTP status code.
     * 
     * @return HTTP status code (200 for valid, 400/403/etc for invalid)
     */
    public int getHttpStatus() {
        return httpStatus;
    }
    
    @Override
    public String toString() {
        if (valid) {
            return "ValidationResult{valid=true, value=" + normalizedValue + "}";
        } else {
            return "ValidationResult{valid=false, error='" + error + "', httpStatus=" + httpStatus + "}";
        }
    }
}
