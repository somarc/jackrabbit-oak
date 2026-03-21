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
import java.util.List;

import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronClusterLauncher;
import org.apache.jackrabbit.oak.segment.consensus.aeron.AeronConsensusEngine;

final class AeronClusterLaunchCoordinator {

    interface LauncherFactory {
        AeronClusterLauncher create(int nodeId,
                                    List<String> hostnames,
                                    File clusterBaseDir,
                                    AeronConsensusEngine aeronConsensusEngine);
    }

    private final LauncherFactory launcherFactory;
    private final FatalMediaDriverExitHandler fatalMediaDriverExitHandler;

    AeronClusterLaunchCoordinator() {
        this(AeronClusterLauncher::new, new FatalMediaDriverExitHandler());
    }

    AeronClusterLaunchCoordinator(LauncherFactory launcherFactory,
                                  FatalMediaDriverExitHandler fatalMediaDriverExitHandler) {
        this.launcherFactory = launcherFactory;
        this.fatalMediaDriverExitHandler = fatalMediaDriverExitHandler;
    }

    AeronClusterLauncher launch(int nodeId,
                                List<String> hostnames,
                                File clusterBaseDir,
                                AeronConsensusEngine aeronConsensusEngine) throws IOException {
        AeronClusterLauncher launcher =
            launcherFactory.create(nodeId, hostnames, clusterBaseDir, aeronConsensusEngine);

        launcher.setShutdownCallback(fatalMediaDriverExitHandler::handleFatalDriverError);

        try {
            launcher.launch();
        } catch (Exception e) {
            throw new IOException("Failed to launch Aeron Cluster", e);
        }

        return launcher;
    }
}
