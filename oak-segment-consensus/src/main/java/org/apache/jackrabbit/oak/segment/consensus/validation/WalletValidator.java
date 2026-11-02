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

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * API-level check for Ethereum addresses ({@code walletAddress}, {@code namespace}): exactly {@code 0x} followed
 * by 40 hex characters, case-insensitive, as the transaction contract requires. Valid addresses are returned
 * lowercased; invalid ones yield an HTTP 400 {@link ValidationResult}.
 */
public final class WalletValidator {

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");

    private WalletValidator() {
    }

    @NotNull
    public static ValidationResult<String> validate(@Nullable String wallet) {
        if (wallet == null || wallet.isEmpty()) {
            return ValidationResult.error(
                "Missing wallet address. Please provide a valid Ethereum address (0x...)."
            );
        }
        String trimmed = wallet.trim();
        if (!trimmed.startsWith("0x")) {
            return ValidationResult.error(
                "Invalid wallet address format. Must start with '0x' prefix. Got: " + truncateForError(trimmed)
            );
        }
        if (!ADDRESS.matcher(trimmed).matches()) {
            return ValidationResult.error(
                "Invalid wallet address. Expected '0x' followed by exactly 40 hexadecimal characters. Got: "
                    + truncateForError(trimmed)
            );
        }
        return ValidationResult.valid(trimmed.toLowerCase(Locale.ROOT));
    }

    private static String truncateForError(String value) {
        return value.length() <= 20 ? value : value.substring(0, 17) + "...";
    }
}
