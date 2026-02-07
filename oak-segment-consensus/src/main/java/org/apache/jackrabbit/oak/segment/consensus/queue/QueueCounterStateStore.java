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

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

final class QueueCounterStateStore {
    private static final Logger log = LoggerFactory.getLogger(QueueCounterStateStore.class);

    private final Path storeFile;

    QueueCounterStateStore(Path directory) {
        this.storeFile = directory.resolve("counter-state.properties");
        try {
            Files.createDirectories(directory);
        } catch (Exception e) {
            log.warn("Failed to create counter state directory {}: {}", directory, e.getMessage());
        }
    }

    Map<String, Long> load() {
        if (!Files.exists(storeFile)) {
            return new HashMap<>();
        }
        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(storeFile)) {
            props.load(in);
        } catch (Exception e) {
            log.warn("Failed to load counter state: {}", e.getMessage());
            return new HashMap<>();
        }

        Map<String, Long> state = new HashMap<>();
        for (String name : props.stringPropertyNames()) {
            try {
                state.put(name, Long.parseLong(props.getProperty(name, "0")));
            } catch (NumberFormatException ignored) {
                // Skip malformed entry.
            }
        }
        return state;
    }

    void save(Map<String, Long> state) {
        Properties props = new Properties();
        for (Map.Entry<String, Long> entry : state.entrySet()) {
            props.setProperty(entry.getKey(), String.valueOf(entry.getValue()));
        }
        try {
            Path tempFile = storeFile.resolveSibling(storeFile.getFileName() + ".tmp");
            try (OutputStream out = Files.newOutputStream(tempFile)) {
                props.store(out, "Proposal queue counter state");
            }
            Files.move(tempFile, storeFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            log.warn("Failed to persist counter state: {}", e.getMessage());
        }
    }
}
