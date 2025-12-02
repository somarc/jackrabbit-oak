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
 * Help command for oak-run-luke.
 */
public class LukeHelpCommand implements Command {

    @Override
    public void execute(String... args) {
        System.out.println("Apache Jackrabbit Oak - LUKE Integration");
        System.out.println("=========================================");
        System.out.println();
        System.out.println("Provides LUKE-based index inspection and analysis capabilities");
        System.out.println("for Lucene indexes stored in Oak repositories.");
        System.out.println();
        System.out.println("Available commands:");
        System.out.println();
        System.out.println("  help       - Display this help message");
        System.out.println("  list       - List all local index directories in IndexCopier cache");
        System.out.println("  inspect    - Inspect a specific index and display statistics");
        System.out.println("  gui        - Launch LUKE GUI for interactive index inspection");
        System.out.println();
        System.out.println("Usage:");
        System.out.println();
        System.out.println("  Standalone CLI (requires running Oak repository):");
        System.out.println("    - Not recommended for production - use OSGi bundle instead");
        System.out.println("    - Requires direct filesystem access to segment store or index cache");
        System.out.println();
        System.out.println("  OSGi Bundle Deployment (recommended for AEM):");
        System.out.println("    1. Deploy oak-luke-bundle-1.22.24-SNAPSHOT.jar to AEM");
        System.out.println("    2. Access via JMX Console:");
        System.out.println("       http://localhost:4502/system/console/jmx");
        System.out.println("    3. Navigate to: org.apache.jackrabbit.oak:name=LukeIndexStats");
        System.out.println();
        System.out.println("Examples (JMX Operations):");
        System.out.println();
        System.out.println("  # List all local index directories");
        System.out.println("  getLocalIndexDirectories()");
        System.out.println();
        System.out.println("  # Inspect damAssetLucene index");
        System.out.println("  getLukeIndexStats(\"/oak:index/damAssetLucene\")");
        System.out.println();
        System.out.println("  # Get field information (100 fields max)");
        System.out.println("  getLukeFieldInfo(\"/oak:index/damAssetLucene\", 100)");
        System.out.println();
        System.out.println("  # Analyze specific field terms");
        System.out.println("  getLukeTermStats(\"/oak:index/damAssetLucene\", \"jcr:content/metadata/dc:title\", 50)");
        System.out.println();
        System.out.println("  # Validate index integrity");
        System.out.println("  validateLocalIndex(\"/oak:index/damAssetLucene\")");
        System.out.println();
        System.out.println("For offline analysis of segment stores:");
        System.out.println("  Use oak-run console mode with LUKE functionality (future enhancement)");
        System.out.println();
    }
}

