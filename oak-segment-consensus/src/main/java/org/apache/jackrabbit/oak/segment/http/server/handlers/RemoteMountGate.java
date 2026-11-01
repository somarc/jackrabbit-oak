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

import jakarta.servlet.http.HttpServletResponse;
import org.apache.jackrabbit.oak.segment.consensus.mount.RemoteMountStatus;
import org.apache.jackrabbit.oak.segment.consensus.util.WalletPathUtil;
import org.apache.jackrabbit.oak.segment.http.server.ServerContext;
import org.apache.jackrabbit.oak.segment.http.server.util.ApiErrorUtil;

import java.io.IOException;

/**
 * Answers 503 {@code mount_unavailable} for reads served by a remote mount
 * that is not available, so clients never mistake an unreachable remote
 * cluster for absent content.
 */
final class RemoteMountGate {

    static final String CODE = "mount_unavailable";

    private RemoteMountGate() {
    }

    static boolean rejectIfUnavailable(ServerContext context, HttpServletResponse response, String path)
            throws IOException {
        if (context == null || path == null) {
            return false;
        }
        RemoteMountStatus status = context.remoteMounts.unavailableFor(path);
        if (status == null) {
            return false;
        }
        String source = status.federation() != null
            ? "Remote enterprise " + status.federation().enterpriseId()
            : "Remote cluster " + status.endpoint() + " serving prefixes " + status.prefixes();
        ApiErrorUtil.sendJsonError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE, CODE,
            source + " is unavailable: " + status.lastError());
        return true;
    }

    static boolean rejectIfWalletUnavailable(ServerContext context, HttpServletResponse response, String wallet)
            throws IOException {
        String walletRoot;
        try {
            walletRoot = WalletPathUtil.getShardRoot(wallet);
        } catch (RuntimeException invalidWallet) {
            return false;
        }
        return rejectIfUnavailable(context, response, walletRoot);
    }
}
