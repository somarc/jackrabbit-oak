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

import org.apache.jackrabbit.oak.segment.consensus.service.FileStoreFlushService;
import org.apache.jackrabbit.oak.segment.consensus.service.MutationAuditMetadata;
import org.apache.jackrabbit.oak.segment.consensus.service.WriteApplicationService;
import org.apache.jackrabbit.oak.segment.file.FileStore;
import org.apache.jackrabbit.oak.spi.state.NodeStore;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/** Puts a proposal into a store's applied-proposal index the way replicated apply does. */
final class AppliedProposals {

    static final long APPLIED_AT = 1_793_586_396_590L;

    private AppliedProposals() {
    }

    static void applyWrite(NodeStore store, String wallet, String path, String proposalId) {
        new WriteApplicationService(mock(FileStore.class, RETURNS_DEEP_STUBS), () -> store, () -> null,
            mock(FileStoreFlushService.class)).applyWriteWithAuditMetadata(wallet, path, "page", "m", "0xsig",
            null, null, null, null,
            MutationAuditMetadata.write(null, null, proposalId, null, null, null, null).withAppliedAt(APPLIED_AT));
    }
}
