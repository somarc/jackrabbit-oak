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
 * JMX MBean for analyzing terms within fields.
 * 
 * <h2>Purpose</h2>
 * Deep-dive into term-level statistics: what terms exist, their frequencies,
 * and distribution patterns.
 * 
 * <h2>Performance</h2>
 * Single-field operations are moderate. Cross-field operations can be very expensive.
 * ALWAYS specify a fieldName for large indexes!
 */
public interface LukeTermAnalysisMBean {
    String TYPE = "LukeTermAnalysis";

    @Description("Gets term statistics for a specific field: term text, doc frequency, total frequency. " +
            "Use for understanding what terms exist in a field. " +
            "Moderate operation for single field: O(terms in field).")
    String[] getLukeTermStats(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath,
            @Name("fieldName")
            @Description("Field name to analyze (e.g., 'jcr:primaryType', ':fulltext')")
            String fieldName,
            @Name("maxTerms")
            @Description("Maximum terms to return (recommended: 100-500)")
            int maxTerms
    ) throws IOException;

    @Description("⚠️ EXPENSIVE: Finds terms with highest document frequency. " +
            "Answers: 'What are the most common values in this index?' " +
            "⚠️ ALWAYS specify fieldName for large indexes! " +
            "Runtime: 1min-2hrs for all fields.")
    String[] getTopTermsByDocFreq(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("fieldName")
            @Description("⚠️ REQUIRED for large indexes! Field name (empty = ALL fields - very slow!)")
            String fieldName,
            @Name("maxTerms")
            @Description("Maximum terms to return (recommended: 50-100)")
            int maxTerms
    ) throws IOException;

    @Description("Analyzes term frequency distribution for a field (Zipf-style analysis). " +
            "Shows long-tail vs short-tail distribution. " +
            "Answers: 'Are my terms evenly distributed or highly skewed?' " +
            "Runtime: O(unique terms in field).")
    String[] getTermDistribution(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("fieldName")
            @Description("Field to analyze")
            String fieldName,
            @Name("buckets")
            @Description("Number of histogram buckets (recommended: 20)")
            int buckets
    ) throws IOException;
}

