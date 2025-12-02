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
 * Command to inspect a specific index.
 */
public class LukeInspectCommand implements Command {

    @Override
    public void execute(String... args) throws Exception {
        System.out.println("LUKE Index Inspector");
        System.out.println("====================");
        System.out.println();
        System.out.println("This command requires an active Oak repository with JMX enabled.");
        System.out.println();
        System.out.println("To inspect an index, use the JMX MBean:");
        System.out.println("  - LukeIndexStatsMBean.getLukeIndexStats(indexPath)");
        System.out.println("  - LukeIndexStatsMBean.getLukeFieldInfo(indexPath, maxFields)");
        System.out.println("  - LukeIndexStatsMBean.getLukeTermStats(indexPath, fieldName, maxTerms)");
        System.out.println();
        System.out.println("Future versions will support offline index inspection.");
    }
}

