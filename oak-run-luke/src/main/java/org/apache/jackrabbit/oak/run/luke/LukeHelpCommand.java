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
        System.out.println("Examples:");
        System.out.println();
        System.out.println("  # List all cached indexes");
        System.out.println("  java -jar oak-run-luke.jar list /path/to/repository");
        System.out.println();
        System.out.println("  # Inspect specific index");
        System.out.println("  java -jar oak-run-luke.jar inspect /path/to/repository --index /oak:index/damAssetLucene");
        System.out.println();
        System.out.println("  # Launch LUKE GUI");
        System.out.println("  java -jar oak-run-luke.jar gui /path/to/repository");
        System.out.println();
        System.out.println("For JMX-based inspection, deploy the LukeIndexStatsMBean in your Oak instance.");
        System.out.println();
    }
}

