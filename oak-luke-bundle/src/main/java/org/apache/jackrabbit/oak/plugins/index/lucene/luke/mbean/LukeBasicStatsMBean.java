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
 * JMX MBean for basic index statistics and health.
 * 
 * <h2>Purpose</h2>
 * Quick overview of index health: document counts, deletions, segments.
 * Start here for a health check before diving deeper.
 * 
 * <h2>Most Operations are Fast</h2>
 * Only getIndexCompositionStats is expensive (counts all terms).
 */
public interface LukeBasicStatsMBean {
    String TYPE = "LukeBasicStats";

    @Description("Gets essential index metrics: document count, deletions, field count, and size. " +
            "Fast operation: O(1). START HERE for quick overview.")
    String getLukeBasicStats(
            @Name("indexPath")
            @Description("Index path to inspect (e.g., /oak:index/damAssetLucene)")
            String indexPath
    ) throws IOException;

    @Description("Comprehensive index health report with actionable recommendations. " +
            "Checks: deletion ratio, segment count, field balance, size anomalies. " +
            "Fast operation: O(segments + fields).")
    String getIndexHealthReport(
            @Name("indexPath")
            @Description("Index path to inspect")
            String indexPath
    ) throws IOException;

    @Description("Gets detailed segment information: count, sizes, deletions per segment. " +
            "Answers: 'Is my index fragmented? Are there many small segments?' " +
            "Fast operation: O(segments).")
    String[] getSegmentInfo(
            @Name("indexPath")
            @Description("Index path to inspect")
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

    @Description("Fulltext-specific analysis: term count, estimated size, backup recommendations. " +
            "Answers: 'How big is my fulltext data? Should I use pre-extracted cache?' " +
            "Fast operation: O(1) - uses cached stats.")
    String getFulltextStats(
            @Name("indexPath")
            @Description("Index path to analyze (e.g., /oak:index/damAssetLucene)")
            String indexPath
    ) throws IOException;
}

