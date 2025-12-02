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
package org.apache.jackrabbit.oak.plugins.index.lucene.luke;

import java.io.IOException;

import javax.management.openmbean.TabularData;

import org.apache.jackrabbit.oak.api.jmx.Description;
import org.apache.jackrabbit.oak.api.jmx.Name;

/**
 * JMX MBean interface for LUKE-style Lucene index inspection.
 * 
 * <h2>Purpose</h2>
 * Answers the critical question: "I have an enormous Lucene index - what's in it? What's taking up space?"
 * 
 * <h2>Design Philosophy</h2>
 * <ul>
 *   <li><b>Read-only</b>: Never modifies indexes or repository</li>
 *   <li><b>Production-safe</b>: All operations are non-blocking with documented O(n) costs</li>
 *   <li><b>LUKE-inspired</b>: Brings LUKE's inspection power to JMX</li>
 * </ul>
 * 
 * <h2>Performance Warnings</h2>
 * Operations marked with ⚠️ are expensive. For 100GB+ indexes:
 * <ul>
 *   <li>Use field-specific queries instead of scanning all fields</li>
 *   <li>Consider running during maintenance windows</li>
 *   <li>Use the standalone JAR for offline analysis</li>
 * </ul>
 * 
 * <h2>For Fulltext Backup</h2>
 * Use oak-run tika commands for fulltext backup (can run while AEM is online):
 * <pre>
 * oak-run tika --generate ...
 * oak-run tika --populate ...
 * </pre>
 * 
 * @see <a href="https://github.com/DmitryKey/luke">LUKE - Lucene Index Toolbox</a>
 */
public interface LukeIndexStatsMBean {
    String TYPE = "LukeIndexStats";

    // ============================================================
    // INDEX DISCOVERY & NAVIGATION
    // ============================================================

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

    // ============================================================
    // BASIC INDEX STATISTICS
    // ============================================================

    @Description("Gets essential index metrics: document count, deletions, field count, and size. " +
            "Fast operation: O(1). Start here for quick overview.")
    String getLukeBasicStats(
            @Name("indexPath")
            @Description("Index path to inspect (e.g., /oak:index/damAssetLucene)")
            String indexPath
    ) throws IOException;

    @Description("⚠️ EXPENSIVE: Comprehensive index composition analysis. " +
            "Shows document stats, field stats, and TOP 10 largest fields by term count. " +
            "Runtime: 10s-60min for 100GB+ indexes (counts all terms).")
    String getIndexCompositionStats(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath
    ) throws IOException;

    // ============================================================
    // FIELD ANALYSIS
    // ============================================================

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

    // ============================================================
    // TERM ANALYSIS
    // ============================================================

    @Description("Gets term statistics for a specific field: term text, doc frequency, total frequency. " +
            "Use for understanding what terms exist in a field. " +
            "Fast operation for single field: O(terms in field).")
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

    // ============================================================
    // SEGMENT ANALYSIS
    // ============================================================

    @Description("Gets detailed segment information: count, sizes, deletions per segment. " +
            "Answers: 'Is my index fragmented? Are there many small segments?' " +
            "Fast operation: O(segments).")
    String[] getSegmentInfo(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath
    ) throws IOException;

    // ============================================================
    // INDEX HEALTH & DIAGNOSTICS
    // ============================================================

    @Description("Comprehensive index health report with actionable recommendations. " +
            "Checks: deletion ratio, segment count, field balance, size anomalies. " +
            "Fast operation: O(segments + fields).")
    String getIndexHealthReport(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath
    ) throws IOException;

    // ============================================================
    // DOCUMENT SAMPLING
    // ============================================================

    @Description("Samples random documents showing stored field values. " +
            "Answers: 'What does an actual document look like in this index?' " +
            "Fast operation: O(sampleSize).")
    String[] sampleDocuments(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath,
            @Name("sampleSize")
            @Description("Number of documents to sample (recommended: 5-10)")
            int sampleSize,
            @Name("showFields")
            @Description("Comma-separated field names to show (empty = all stored fields)")
            String showFields
    ) throws IOException;

    @Description("Gets stored field values for a specific document by doc ID. " +
            "Fast operation: O(1).")
    String getDocument(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath,
            @Name("docId")
            @Description("Lucene document ID (0-based)")
            int docId
    ) throws IOException;

    // ============================================================
    // FULLTEXT-SPECIFIC ANALYSIS
    // ============================================================

    @Description("Fulltext-specific analysis: term count, estimated size, backup recommendations. " +
            "Answers: 'How big is my fulltext data? Should I use pre-extracted cache?' " +
            "Fast operation: O(1) - uses cached stats.")
    String getFulltextStats(
            @Name("indexPath")
            @Description("Index path to analyze (e.g., /oak:index/damAssetLucene)")
            String indexPath
    ) throws IOException;

    // ============================================================
    // ACTIONABLE INSIGHTS (The "Aha!" Moments)
    // ============================================================

    @Description("⭐ MAIN FEATURE: Actionable index insights with optimization suggestions. " +
            "Answers: 'What's eating my index? What should I do about it?' " +
            "Produces a table with fields, sizes, top values, and specific recommendations. " +
            "⚠️ EXPENSIVE for large indexes: Runtime 1-30min for 100GB+.")
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
            "Runtime: O(unique paths) - typically 1-5min.")
    String getContentDistribution(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("depth")
            @Description("Path depth to analyze (2=/content/dam, 3=/content/dam/projects)")
            int depth
    ) throws IOException;

    @Description("Analyzes field cardinality and document coverage. " +
            "Answers: 'Which fields have too many unique values? Which fields are sparse?' " +
            "High cardinality = memory hog. Low coverage = maybe unnecessary. " +
            "⚠️ EXPENSIVE: Runtime 5-30min for large indexes.")
    String[] getFieldCardinality(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("maxFields")
            @Description("Number of fields to analyze (recommended: 20)")
            int maxFields
    ) throws IOException;

    @Description("Detects potential duplicate or near-duplicate values in a field. " +
            "Answers: 'Are there junk variations like urgent/URGENT/Urgent?' " +
            "Useful for tag fields, categories, authors. " +
            "Runtime: O(unique terms in field).")
    String[] detectDuplicateValues(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("fieldName")
            @Description("Field to check for duplicates (e.g., 'tags', 'author')")
            String fieldName,
            @Name("maxResults")
            @Description("Maximum duplicate groups to return")
            int maxResults
    ) throws IOException;

    // ============================================================
    // GUI (PLACEHOLDER)
    // ============================================================

    @Description("Placeholder for LUKE GUI launch. JMX MBean cannot launch GUI. " +
            "Use standalone JAR for GUI access.")
    String launchLukeGUI(
            @Name("indexPath")
            @Description("Ignored - GUI not supported in JMX mode")
            String indexPath
    ) throws IOException;
}
