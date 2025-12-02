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

import org.apache.jackrabbit.oak.api.jmx.Description;
import org.apache.jackrabbit.oak.api.jmx.Name;

/**
 * JMX MBean for analyzing index fields.
 * 
 * <h2>Purpose</h2>
 * Understand what fields exist, how much space they take, and their characteristics.
 * Use to identify bloated or unnecessary fields.
 * 
 * <h2>Performance Warning</h2>
 * Most operations count terms which is O(n) for each field.
 * For 100GB+ indexes, expect 10-60 minute runtimes.
 */
public interface LukeFieldAnalysisMBean {
    String TYPE = "LukeFieldAnalysis";

    @Description("Gets field information including term counts, indexed/stored flags, and types. " +
            "⚠️ Term counting is expensive for large indexes. Runtime: 5s-30min.")
    String[] getLukeFieldInfo(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath,
            @Name("maxFields")
            @Description("Maximum number of fields to return (recommended: 50-100)")
            int maxFields
    ) throws IOException;

    @Description("⚠️ EXPENSIVE: Ranks fields by term count with percentage of total. " +
            "Answers: 'Which fields dominate this index?' " +
            "Runtime: 10s-60min for 100GB+ indexes.")
    String[] getFieldSizeAnalysis(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("maxFields")
            @Description("Maximum fields to display (recommended: 20)")
            int maxFields
    ) throws IOException;

    @Description("Gets detailed field flags showing indexed/stored/vectored/norms status. " +
            "Answers: 'Is this field stored? Does it have term vectors?' " +
            "Fast operation: O(fields).")
    String[] getFieldFlags(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath
    ) throws IOException;

    @Description("⚠️ EXPENSIVE: Analyzes field cardinality and document coverage. " +
            "Answers: 'Which fields have too many unique values? Which fields are sparse?' " +
            "High cardinality = memory hog. Low coverage = maybe unnecessary. " +
            "Runtime 5-30min for large indexes.")
    String[] getFieldCardinality(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("maxFields")
            @Description("Number of fields to analyze (recommended: 20)")
            int maxFields
    ) throws IOException;
}

