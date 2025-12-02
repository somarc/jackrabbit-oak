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
 * ⭐ JMX MBean for ACTIONABLE INDEX INSIGHTS
 * 
 * <h2>Purpose</h2>
 * Not just data - ACTIONABLE RECOMMENDATIONS!
 * Answers: "What's eating my index? What should I DO about it?"
 * 
 * <h2>The "Aha!" Moment Operations</h2>
 * These operations don't just show data, they tell you what to fix.
 * 
 * <h2>Performance</h2>
 * Most operations are moderate to expensive. Worth the wait for the insights!
 * Memory-safe: All operations have built-in limits for 100GB+ indexes.
 */
public interface LukeInsightsMBean {
    String TYPE = "LukeInsights";

    @Description("⭐ MAIN FEATURE: Actionable index insights with optimization suggestions. " +
            "Produces a table with fields, sizes, top values, and specific recommendations. " +
            "Answers: 'What's eating my index? What should I do about it?' " +
            "⚠️ EXPENSIVE for large indexes: Runtime 1-30min for 100GB+. " +
            "Memory-safe: Skips huge fields automatically.")
    String getIndexInsights(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("maxFields")
            @Description("Number of fields to analyze (recommended: 10-20)")
            int maxFields
    ) throws IOException;

    @Description("Analyzes content distribution using the :path field. " +
            "Shows breakdown by content area (e.g., /content/dam, /content/pages). " +
            "Answers: 'What content is my index serving? Where should I focus?' " +
            "Runtime: O(unique paths) - typically 1-5min. " +
            "Memory-safe: Limited to 50,000 path prefixes.")
    String getContentDistribution(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("depth")
            @Description("Path depth to analyze (2=/content/dam, 3=/content/dam/projects)")
            int depth
    ) throws IOException;

    @Description("Detects potential duplicate or near-duplicate values in a field. " +
            "Answers: 'Are there junk variations like urgent/URGENT/Urgent?' " +
            "Useful for tag fields, categories, authors. " +
            "Memory-safe: Limited to 100,000 terms. Refuses high-cardinality fields.")
    String[] detectDuplicateValues(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("fieldName")
            @Description("Field to check for duplicates (e.g., 'cq:tags', 'author')")
            String fieldName,
            @Name("maxResults")
            @Description("Maximum duplicate groups to return")
            int maxResults
    ) throws IOException;
}

