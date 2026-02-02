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
package org.apache.jackrabbit.oak.segment.agentic.eip8004;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class Eip8004RegistrationCli {
    private Eip8004RegistrationCli() {
    }

    public static void main(String[] args) throws Exception {
        CliArgs cli = CliArgs.parse(args);
        if (cli == null) {
            printUsage();
            System.exit(1);
            return;
        }

        Eip8004Config config = Eip8004Config.load();
        List<String> capabilities = cli.capabilities;
        if (capabilities.isEmpty()) {
            capabilities = config.getCapabilities();
        }

        AgentRegistrationFile registrationFile = AgentRegistrationFile.forAgent(
                cli.walletAddress,
                cli.agentType,
                config,
                capabilities
        );

        String json = registrationFile.toJson();
        Files.writeString(cli.outputPath, json + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println("Wrote EIP-8004 registration JSON to " + cli.outputPath);
    }

    private static void printUsage() {
        System.out.println("Usage: Eip8004RegistrationCli --wallet <0x...> --out <path> [--agentType validator|sling-author] [--capabilities a,b,c]");
    }

    private static final class CliArgs {
        private final String walletAddress;
        private final String agentType;
        private final Path outputPath;
        private final List<String> capabilities;

        private CliArgs(String walletAddress, String agentType, Path outputPath, List<String> capabilities) {
            this.walletAddress = walletAddress;
            this.agentType = agentType;
            this.outputPath = outputPath;
            this.capabilities = capabilities;
        }

        private static CliArgs parse(String[] args) {
            String wallet = null;
            String agentType = "validator";
            String out = null;
            List<String> capabilities = new ArrayList<>();

            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--wallet".equals(arg) && i + 1 < args.length) {
                    wallet = args[++i];
                } else if ("--agentType".equals(arg) && i + 1 < args.length) {
                    agentType = args[++i];
                } else if ("--out".equals(arg) && i + 1 < args.length) {
                    out = args[++i];
                } else if ("--capabilities".equals(arg) && i + 1 < args.length) {
                    String raw = args[++i];
                    for (String part : raw.split(",")) {
                        String value = part.trim();
                        if (!value.isEmpty()) {
                            capabilities.add(value);
                        }
                    }
                }
            }

            if (wallet == null || wallet.isEmpty() || out == null || out.isEmpty()) {
                return null;
            }

            return new CliArgs(wallet, agentType, Path.of(out), capabilities);
        }
    }
}
