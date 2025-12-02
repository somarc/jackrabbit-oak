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
 * MBean interface for LUKE-based index inspection and statistics.
 * Extends the standard LuceneIndexMBean with LUKE-specific capabilities
 * for deep index analysis and inspection.
 */
public interface LukeIndexStatsMBean {
    String TYPE = "LukeIndexStats";

    @Description("Lists all available local index directories from the IndexCopier cache")
    TabularData getLocalIndexDirectories();

    @Description("Gets detailed index statistics using LUKE for a specific index path")
    TabularData getLukeIndexStats(
            @Name("indexPath")
            @Description("Index path to inspect (e.g., /oak:index/damAssetLucene)")
            String indexPath
    ) throws IOException;

    @Description("Gets detailed field information for a specific index using LUKE")
    String[] getLukeFieldInfo(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath,
            @Name("maxFields")
            @Description("Maximum number of fields to return (default: 100)")
            int maxFields
    ) throws IOException;

    @Description("Gets term statistics for a specific field in an index using LUKE")
    String[] getLukeTermStats(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath,
            @Name("fieldName")
            @Description("Field name to analyze")
            String fieldName,
            @Name("maxTerms")
            @Description("Maximum number of terms to return (default: 100)")
            int maxTerms
    ) throws IOException;

    @Description("Gets document count and other basic statistics for an index")
    String getLukeBasicStats(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath
    ) throws IOException;

    @Description("Launches LUKE GUI for interactive index inspection")
    String launchLukeGUI(
            @Name("indexPath")
            @Description("Index path to inspect (empty for selection dialog)")
            String indexPath
    ) throws IOException;

    @Description("Gets the local filesystem path for a given index path")
    String getLocalIndexPath(
            @Name("indexPath")
            @Description("Repository index path (e.g., /oak:index/damAssetLucene)")
            String indexPath
    );

    @Description("Validates that a local index directory is readable and contains valid Lucene index files")
    String validateLocalIndex(
            @Name("indexPath")
            @Description("Index path to validate")
            String indexPath
    ) throws IOException;

    @Description("⚠️ EXPENSIVE: Analyzes which fields consume the most space (by term count). " +
            "Runtime: 10s-60min depending on index size. For 100GB+ indexes, use standalone JAR during maintenance windows.")
    String[] getFieldSizeAnalysis(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("maxFields")
            @Description("Maximum number of fields (recommended: 20 for large indexes)")
            int maxFields
    ) throws IOException;

    @Description("⚠️ VERY EXPENSIVE: Gets top terms by document frequency. " +
            "Runtime: 1min-2hrs depending on index size. ALWAYS specify fieldName - never use empty string for 100GB+ indexes!")
    String[] getTopTermsByDocFreq(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath,
            @Name("fieldName")
            @Description("⚠️ REQUIRED for large indexes! Specific field name (e.g., 'jcr:primaryType'). Empty = ALL fields (very slow!)")
            String fieldName,
            @Name("maxTerms")
            @Description("Maximum number of terms (recommended: 50-100)")
            int maxTerms
    ) throws IOException;

    @Description("⚠️ EXPENSIVE: Comprehensive index analysis including top 10 largest fields. " +
            "Runtime: 10s-60min. For 100GB+ indexes, use standalone JAR during maintenance windows.")
    String getIndexCompositionStats(
            @Name("indexPath")
            @Description("Index path to analyze")
            String indexPath
    ) throws IOException;

    @Description("Get fulltext-specific statistics for an index. " +
            "Shows stored fulltext size, estimated backup time, and recommendations. " +
            "Fast operation: < 5 seconds.")
    String getFulltextStats(
            @Name("indexPath")
            @Description("Index path to analyze (e.g., /oak:index/damAssetLucene)")
            String indexPath
    ) throws IOException;

    // ============================================================
    // FULLTEXT BACKUP OPERATIONS (Phase 2)
    // ============================================================

    @Description("⚠️ LONG RUNNING: Start background fulltext backup job. " +
            "Extracts stored :fulltext from Lucene index and saves to filesystem. " +
            "Compatible with oak-run tika --populate format. " +
            "Runtime: 10-60 minutes for 100GB+ indexes. Monitor with getFulltextBackupProgress().")
    String startFulltextBackup(
            @Name("storePath")
            @Description("Local filesystem path to store extracted text (e.g., /opt/aem/fulltext-store)")
            String storePath,
            @Name("indexPath")
            @Description("Index path to backup (e.g., /oak:index/damAssetLucene)")
            String indexPath
    ) throws IOException;

    @Description("Get progress of running fulltext backup job.")
    String getFulltextBackupProgress(
            @Name("jobId")
            @Description("Job ID returned from startFulltextBackup")
            String jobId
    ) throws IOException;

    @Description("Cancel a running fulltext backup job.")
    String cancelFulltextBackup(
            @Name("jobId")
            @Description("Job ID to cancel")
            String jobId
    ) throws IOException;

    @Description("List all fulltext backup jobs (running and completed).")
    String[] listFulltextBackupJobs() throws IOException;
}

