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
package org.apache.jackrabbit.oak.segment.consensus.server;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Supplier;

import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainConfig;
import org.apache.jackrabbit.oak.segment.consensus.config.BlockchainRuntimeValidator;
import org.apache.jackrabbit.oak.segment.consensus.config.RuntimeConfigValueResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class StartupPreflightCoordinator {

    private static final Logger log = LoggerFactory.getLogger(StartupPreflightCoordinator.class);

    private final Supplier<BlockchainConfig> blockchainConfigSupplier;

    StartupPreflightCoordinator() {
        this(BlockchainConfig::getInstance);
    }

    StartupPreflightCoordinator(Supplier<BlockchainConfig> blockchainConfigSupplier) {
        this.blockchainConfigSupplier = blockchainConfigSupplier;
    }

    File prepare(String storeDirectory) throws IOException {
        // Fail on a bad blockchain runtime before any storage, HTTP or Aeron resource exists.
        BlockchainRuntimeValidator.validate(blockchainConfigSupplier.get());

        Path storePath = Paths.get(storeDirectory);
        if (!Files.exists(storePath)) {
            Files.createDirectories(storePath);
            log.info("Created store directory: {}", storePath);
        }

        boolean isAeronMode = "aeron".equalsIgnoreCase(
            RuntimeConfigValueResolver.readString("consensus.mode", "aeron")
        );
        if (!isAeronMode) {
            throw new IllegalArgumentException(
                "This POC only supports Aeron Cluster consensus. Set consensus.mode=aeron or omit it (defaults to aeron)."
            );
        }
        return new File(storeDirectory);
    }
}
