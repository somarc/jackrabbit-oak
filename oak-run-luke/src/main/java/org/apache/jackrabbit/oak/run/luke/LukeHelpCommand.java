/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.jackrabbit.oak.run.luke;

import org.apache.jackrabbit.oak.run.commons.Command;

/**
 * Help command showing available LUKE commands.
 */
public class LukeHelpCommand implements Command {

    @Override
    public void execute(String... args) throws Exception {
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println("LUKE - Lucene Index Explorer for Apache Jackrabbit Oak");
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
        System.out.println();
        System.out.println("Answers the question: 'What's in my Lucene index? What's taking up space?'");
        System.out.println();
        System.out.println("AVAILABLE COMMANDS:");
        System.out.println();
        System.out.println("  gui       Launch interactive GUI browser (like oak-run explore)");
        System.out.println("            java -jar oak-run-luke.jar gui [path-to-index]");
        System.out.println();
        System.out.println("  inspect   Analyze a Lucene index from command line");
        System.out.println("            java -jar oak-run-luke.jar inspect /path/to/index [options]");
        System.out.println();
        System.out.println("  list      Find all Lucene indexes in a directory");
        System.out.println("            java -jar oak-run-luke.jar list /path/to/repository");
        System.out.println();
        System.out.println("  help      Show this help message");
        System.out.println();
        System.out.println("EXAMPLES:");
        System.out.println();
        System.out.println("  # Launch GUI");
        System.out.println("  java -jar oak-run-luke.jar gui");
        System.out.println();
        System.out.println("  # Find indexes in AEM");
        System.out.println("  java -jar oak-run-luke.jar list crx-quickstart/repository/index/");
        System.out.println();
        System.out.println("  # Quick overview of an index");
        System.out.println("  java -jar oak-run-luke.jar inspect crx-quickstart/repository/index/damAssetLucene-*/data");
        System.out.println();
        System.out.println("  # Health check");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --health");
        System.out.println();
        System.out.println("  # Analyze :fulltext field");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --field :fulltext --top-terms 100");
        System.out.println();
        System.out.println("  # View segment info");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --segments");
        System.out.println();
        System.out.println("  # Sample documents");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/index --sample 5");
        System.out.println();
        System.out.println("INSPECT OPTIONS:");
        System.out.println("  --health        Show index health report");
        System.out.println("  --segments      Show segment information");
        System.out.println("  --fields        List all fields with term counts");
        System.out.println("  --field <name>  Analyze specific field");
        System.out.println("  --top-terms N   Show top N terms (default: 20)");
        System.out.println("  --doc <id>      Show specific document by ID");
        System.out.println("  --sample N      Sample N random documents");
        System.out.println();
        System.out.println("For detailed help on a specific command:");
        System.out.println("  java -jar oak-run-luke.jar <command> --help");
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════════════════════");
    }
}
