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
package org.apache.jackrabbit.oak.segment.consensus.queue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

final class ProposalPersistenceStore {
    private static final Logger log = LoggerFactory.getLogger(ProposalPersistenceStore.class);

    private final Path storeFile;

    ProposalPersistenceStore(Path directory) {
        this.storeFile = directory.resolve("queued-proposals.bin");
        try {
            Files.createDirectories(directory);
        } catch (Exception e) {
            log.warn("Failed to create proposal persistence directory {}: {}", directory, e.getMessage());
        }
    }

    List<QueuedProposal> load() {
        if (!Files.exists(storeFile)) {
            return new ArrayList<>();
        }
        try (ObjectInputStream in = new ObjectInputStream(Files.newInputStream(storeFile))) {
            Object data = in.readObject();
            if (data instanceof List) {
                @SuppressWarnings("unchecked")
                List<QueuedProposal> proposals = (List<QueuedProposal>) data;
                return proposals != null ? proposals : new ArrayList<>();
            }
        } catch (Exception e) {
            log.warn("Failed to load persisted proposals: {}", e.getMessage());
        }
        return new ArrayList<>();
    }

    void save(Collection<QueuedProposal> proposals) {
        try {
            Path tempFile = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            try (ObjectOutputStream out = new ObjectOutputStream(Files.newOutputStream(tempFile))) {
                out.writeObject(new ArrayList<>(proposals));
            }
            Files.move(tempFile, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("Failed to persist proposals: {}", e.getMessage());
        }
    }
}
