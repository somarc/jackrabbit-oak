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
 * Command to list all local index directories.
 */
public class LukeListCommand implements Command {

    @Override
    public void execute(String... args) throws Exception {
        if (args.length < 1) {
            System.err.println("Error: Repository path required");
            System.err.println("Usage: java -jar oak-run-luke.jar list <repository-path>");
            System.exit(1);
        }

        String repositoryPath = args[0];
        System.out.println("Scanning for local index directories in: " + repositoryPath);
        System.out.println();
        System.out.println("This command requires an active Oak repository.");
        System.out.println("For now, use the JMX MBean 'LukeIndexStatsMBean.getLocalIndexDirectories()' instead.");
        System.out.println();
        System.out.println("Future versions will support offline index scanning.");
    }
}

