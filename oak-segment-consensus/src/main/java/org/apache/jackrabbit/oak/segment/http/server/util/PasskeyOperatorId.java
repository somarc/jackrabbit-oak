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
package org.apache.jackrabbit.oak.segment.http.server.util;

import java.security.MessageDigest;

/**
 * Deterministic identifier for passkey-backed validator operators.
 *
 * <p>This is intentionally not an Ethereum address. It is a stable identifier
 * derived from WebAuthn public key material so POC auth flows can keep working
 * without pretending a P-256 passkey is a secp256k1 wallet. Explicit wallet
 * binding needs a separate contract.</p>
 */
public final class PasskeyOperatorId {

    private PasskeyOperatorId() {
    }

    public static String derive(byte[] publicKey) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(publicKey);
            StringBuilder sb = new StringBuilder("0x");
            for (int i = hash.length - 20; i < hash.length; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return sb.toString().toLowerCase();
        } catch (Exception e) {
            throw new RuntimeException("Failed to derive passkey operator ID", e);
        }
    }
}
