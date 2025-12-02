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
package org.apache.jackrabbit.oak.plugins.index.lucene.luke.mbean;

import java.io.IOException;

import javax.management.openmbean.TabularData;

import org.apache.jackrabbit.oak.api.jmx.Description;
import org.apache.jackrabbit.oak.api.jmx.Name;

/**
 * JMX MBean for discovering and validating local Lucene indexes.
 * 
 * <h2>Purpose</h2>
 * Find indexes in the local IndexCopier cache and validate their structure.
 * Start here to discover what indexes are available for analysis.
 * 
 * <h2>All Operations are Fast: O(1)</h2>
 */
public interface LukeDiscoveryMBean {
    String TYPE = "LukeDiscovery";

    @Description("Lists all local index directories from the IndexCopier cache. " +
            "Shows index path, local filesystem path, size, and validity status. " +
            "Fast operation: O(1).")
    TabularData getLocalIndexDirectories();

    @Description("Gets the local filesystem path for a given Oak index path. " +
            "Fast operation: O(1).")
    String getLocalIndexPath(
            @Name("indexPath")
            @Description("Repository index path (e.g., /oak:index/damAssetLucene)")
            String indexPath
    );

    @Description("Validates that a local index directory exists and contains valid Lucene segments. " +
            "Fast operation: O(1).")
    String validateLocalIndex(
            @Name("indexPath")
            @Description("Index path to validate")
            String indexPath
    ) throws IOException;
}

