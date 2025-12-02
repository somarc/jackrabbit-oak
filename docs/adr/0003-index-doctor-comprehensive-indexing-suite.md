# ADR-0003: Index Doctor - Comprehensive Indexing Suite Vision

**Status**: Proposed  
**Date**: 2024-12-02  
**Author**: Marc Hess  
**Supersedes**: None  
**Related**: ADR-0001 (JMX MBean Interface), ADR-0002 (Fulltext Backup Separation)

## Context

### Current State

AEM/Oak indexing information is fragmented across multiple JMX MBeans:

| MBean | What It Provides | Limitations |
|-------|------------------|-------------|
| `LukeIndexStatsMBean` | Deep field/term analysis | Doesn't know query patterns |
| `LuceneIndexMBean` | Index stats, consistency | No optimization suggestions |
| `IndexStatsMBean` | Async indexing status | No correlation with queries |
| `QueryStatManagerMBean` | Slow queries | Doesn't link to index issues |
| `CheckpointMBean` | Checkpoint management | Manual correlation needed |

**Result**: Administrators must manually correlate data from 5+ MBeans to diagnose indexing issues.

### Observed Problem (Real Instance)

From a production AEM 6.5.x instance running Oak 1.22.x:

```
Index: /oak:index/lucene
Documents: 708,848 active | 172,195 deleted (19.5% waste)

HIGH-CARDINALITY FIELDS DETECTED:
- full:imageData         (927.1 cardinality) - XMP binary data being tokenized
- full:xmpGImg:image     (633.1 cardinality) - Base64 thumbnails
- full:dam:Comments      (41.9 cardinality)  - Adobe metadata

SPARSE FIELDS (low coverage, still indexed):
- full:xmpMM:InstanceID  (1.4% coverage)
- full:xmpMM:DocumentID  (0.9% coverage)
```

**Key Insight**: The deprecated `/oak:index/lucene` catch-all index is:
1. Bloated with high-cardinality XMP metadata
2. Serving queries that should use dedicated indexes
3. Still relied upon because no tool suggests alternatives

### The Vision: Index Doctor

A comprehensive diagnostic tool that:

1. **Aggregates** data from all indexing-related MBeans
2. **Correlates** slow queries with index issues
3. **Understands** AEM best practices (deprecated indexes, property vs lucene)
4. **Recommends** specific index refactoring actions
5. **Tracks** progress toward index modernization

## Decision

Create an "Index Doctor" suite that provides holistic indexing health analysis for AEM instances, with a phased implementation approach.

### Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                              INDEX DOCTOR SUITE                                      │
│                                                                                      │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         Data Collection Layer                                │   │
│  │                                                                              │   │
│  │   ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐       │   │
│  │   │ LukeIndex    │ │ LuceneIndex  │ │ IndexStats   │ │ QueryStat    │       │   │
│  │   │ StatsMBean   │ │ MBean        │ │ MBean        │ │ ManagerMBean │       │   │
│  │   └──────┬───────┘ └──────┬───────┘ └──────┬───────┘ └──────┬───────┘       │   │
│  │          │                │                │                │                │   │
│  │          └────────────────┴────────────────┴────────────────┘                │   │
│  │                                    │                                         │   │
│  └────────────────────────────────────┼─────────────────────────────────────────┘   │
│                                       ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                         Analysis Engine                                      │   │
│  │                                                                              │   │
│  │   ┌─────────────────┐  ┌─────────────────┐  ┌─────────────────┐             │   │
│  │   │ Deprecation     │  │ Query-Index     │  │ Size/Perf       │             │   │
│  │   │ Detector        │  │ Correlator      │  │ Analyzer        │             │   │
│  │   │                 │  │                 │  │                 │             │   │
│  │   │ • /oak:index/   │  │ • Which queries │  │ • High card.    │             │   │
│  │   │   lucene usage  │  │   hit which     │  │ • Sparse fields │             │   │
│  │   │ • Deprecated    │  │   indexes?      │  │ • Deletion %    │             │   │
│  │   │   properties    │  │ • Slow query    │  │ • Segment frag  │             │   │
│  │   │                 │  │   root cause    │  │                 │             │   │
│  │   └─────────────────┘  └─────────────────┘  └─────────────────┘             │   │
│  │                                                                              │   │
│  └────────────────────────────────────┬─────────────────────────────────────────┘   │
│                                       ▼                                              │
│  ┌─────────────────────────────────────────────────────────────────────────────┐   │
│  │                      Recommendation Engine                                   │   │
│  │                                                                              │   │
│  │   ┌─────────────────────────────────────────────────────────────────────┐   │   │
│  │   │ "Your /oak:index/lucene is handling 45% of queries.                 │   │   │
│  │   │  Create these dedicated indexes to reduce reliance:                 │   │   │
│  │   │                                                                     │   │   │
│  │   │  1. damAssetLucene-custom (covers 30% of queries)                   │   │   │
│  │   │     - Add: dam:Comments (currently high-cardinality in /oak:index/) │   │   │
│  │   │     - Exclude: xmpGImg:image (base64 data, not queryable)           │   │   │
│  │   │                                                                     │   │   │
│  │   │  2. cqPageLucene-custom (covers 12% of queries)                     │   │   │
│  │   │     - Add: jcr:description for full-text search                     │   │   │
│  │   │                                                                     │   │   │
│  │   │  3. Property index for jcr:primaryType (covers 3% of queries)       │   │   │
│  │   │     - Much faster than Lucene for exact matches                     │   │   │
│  │   │                                                                     │   │   │
│  │   │  Estimated impact: 45% → 0% reliance on deprecated index"           │   │   │
│  │   └─────────────────────────────────────────────────────────────────────┘   │   │
│  │                                                                              │   │
│  └──────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

### Phased Implementation

#### Phase 1: Foundation (Current - COMPLETE)
- ✅ LUKE-style field/term analysis
- ✅ High-cardinality detection
- ✅ Sparse field identification
- ✅ Content distribution analysis
- ✅ Duplicate value detection

#### Phase 2: Multi-MBean Aggregation
- [ ] Collect data from all indexing MBeans
- [ ] Unified "Index Health Dashboard" view
- [ ] Cross-MBean correlation engine
- [ ] Historical tracking (store snapshots)

#### Phase 3: Query-Index Correlation
- [ ] Parse slow queries from QueryStatManagerMBean
- [ ] Map queries to indexes they use
- [ ] Identify queries hitting deprecated `/oak:index/lucene`
- [ ] Suggest query rewrites or new indexes

#### Phase 4: Automated Recommendations
- [ ] Index definition generator
- [ ] Property index vs Lucene recommendations
- [ ] Exclusion rules for high-cardinality fields
- [ ] Migration path from deprecated indexes

#### Phase 5: Index Modernization Tracker
- [ ] Track progress toward deprecation elimination
- [ ] Before/after comparison of changes
- [ ] Rollback safety checks
- [ ] Production validation checklist

### Key Analyses

#### 1. Deprecated Index Detection

```java
/**
 * Detects reliance on deprecated /oak:index/lucene
 */
public DeprecationReport analyzeDeprecatedIndexUsage() {
    // Collect from QueryStatManagerMBean
    List<SlowQuery> queries = getSlowQueries();
    
    // Identify which hit /oak:index/lucene
    int deprecatedHits = 0;
    Map<String, Integer> queryPatterns = new HashMap<>();
    
    for (SlowQuery q : queries) {
        if (q.getIndexUsed().equals("/oak:index/lucene")) {
            deprecatedHits++;
            String pattern = extractQueryPattern(q);
            queryPatterns.merge(pattern, 1, Integer::sum);
        }
    }
    
    return new DeprecationReport(
        deprecatedHits,
        queries.size(),
        queryPatterns,
        generateMigrationSuggestions(queryPatterns)
    );
}
```

#### 2. High-Cardinality Impact Analysis

```java
/**
 * Analyzes memory impact of high-cardinality fields
 */
public CardinalityReport analyzeCardinalityImpact(String indexPath) {
    List<FieldInsight> insights = getIndexInsights(indexPath);
    
    long totalMemoryImpact = 0;
    List<FieldRecommendation> recommendations = new ArrayList<>();
    
    for (FieldInsight fi : insights) {
        if (fi.cardinality > 100) {
            // High cardinality = ~8 bytes per unique term in memory
            long memoryBytes = fi.termCount * 8;
            totalMemoryImpact += memoryBytes;
            
            recommendations.add(new FieldRecommendation(
                fi.fieldName,
                "EXCLUDE_FROM_FULLTEXT",
                String.format("Saves ~%s memory", humanReadableSize(memoryBytes)),
                generateExclusionRule(fi.fieldName)
            ));
        }
    }
    
    return new CardinalityReport(totalMemoryImpact, recommendations);
}
```

#### 3. Query-Index Correlation

```java
/**
 * Correlates slow queries with index characteristics
 */
public QueryIndexCorrelation correlateQueriesWithIndexes() {
    // Get slow queries
    List<SlowQuery> slowQueries = queryStatManager.getSlowQueries();
    
    // Get index insights
    Map<String, IndexInsights> indexInsights = getAllIndexInsights();
    
    List<Correlation> correlations = new ArrayList<>();
    
    for (SlowQuery query : slowQueries) {
        String indexUsed = query.getIndexUsed();
        IndexInsights insights = indexInsights.get(indexUsed);
        
        // Check if query slowness correlates with index issues
        if (insights.deletionRatio > 20) {
            correlations.add(new Correlation(
                query, 
                "HIGH_DELETION_RATIO",
                "Index has " + insights.deletionRatio + "% deletions - optimize"
            ));
        }
        
        if (insights.segmentCount > 50) {
            correlations.add(new Correlation(
                query,
                "FRAGMENTED_INDEX", 
                "Index has " + insights.segmentCount + " segments - merge"
            ));
        }
    }
    
    return new QueryIndexCorrelation(correlations);
}
```

### Output Example: Comprehensive Health Report

```
╔═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╗
║                                    INDEX DOCTOR - COMPREHENSIVE HEALTH REPORT                                          ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣
║ AEM Instance: author-prod-01 | Oak Version: 1.22.22 | Report Date: 2024-12-02                                          ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣
║                                                                                                                        ║
║ OVERALL HEALTH SCORE: 62/100 (NEEDS ATTENTION)                                                                         ║
║                                                                                                                        ║
║ ┌────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐ ║
║ │ CRITICAL ISSUES                                                                                                    │ ║
║ │                                                                                                                    │ ║
║ │ 🔴 DEPRECATED INDEX RELIANCE                                                                                       │ ║
║ │    /oak:index/lucene handles 45% of queries (2,340 queries/hour)                                                   │ ║
║ │    This index is deprecated in Oak 1.22+ and will be removed in future versions.                                   │ ║
║ │                                                                                                                    │ ║
║ │ 🔴 HIGH MEMORY USAGE FROM CARDINALITY                                                                              │ ║
║ │    8 high-cardinality fields consuming ~450MB heap                                                                 │ ║
║ │    Top offenders: full:imageData (927 card), full:xmpGImg:image (633 card)                                         │ ║
║ │                                                                                                                    │ ║
║ └────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘ ║
║                                                                                                                        ║
║ ┌────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐ ║
║ │ WARNINGS                                                                                                           │ ║
║ │                                                                                                                    │ ║
║ │ 🟡 INDEX FRAGMENTATION                                                                                             │ ║
║ │    /oak:index/lucene: 19.5% deletions (172,195 docs)                                                               │ ║
║ │    /oak:index/damAssetLucene: 12.3% deletions                                                                      │ ║
║ │                                                                                                                    │ ║
║ │ 🟡 ASYNC INDEXING LAG                                                                                              │ ║
║ │    fulltext-async lane: 45 seconds behind (threshold: 30s)                                                         │ ║
║ │                                                                                                                    │ ║
║ └────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘ ║
║                                                                                                                        ║
║ ┌────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐ ║
║ │ RECOMMENDED ACTIONS (Priority Order)                                                                               │ ║
║ │                                                                                                                    │ ║
║ │ 1. CREATE DEDICATED INDEX: damAssetLucene-custom                                                    [HIGH IMPACT]  │ ║
║ │    Covers: 30% of queries currently hitting /oak:index/lucene                                                      │ ║
║ │    Add properties: dam:Comments, dc:title, dc:description                                                          │ ║
║ │    Exclude: xmpGImg:image, imageData (high-cardinality, not queryable)                                             │ ║
║ │    → Run: [Generate Index Definition]                                                                              │ ║
║ │                                                                                                                    │ ║
║ │ 2. CREATE PROPERTY INDEX: primaryTypeIndex                                                          [MEDIUM]       │ ║
║ │    Covers: 8% of queries (exact match on jcr:primaryType)                                                          │ ║
║ │    Property indexes are 10x faster than Lucene for exact matches                                                   │ ║
║ │    → Run: [Generate Index Definition]                                                                              │ ║
║ │                                                                                                                    │ ║
║ │ 3. OPTIMIZE INDEXES                                                                                 [QUICK WIN]    │ ║
║ │    /oak:index/lucene: Reclaim 65MB (19.5% deletions)                                                               │ ║
║ │    /oak:index/damAssetLucene: Reclaim 120MB (12.3% deletions)                                                      │ ║
║ │    → Run: oak-run compact or wait for scheduled optimization                                                       │ ║
║ │                                                                                                                    │ ║
║ │ 4. EXCLUDE HIGH-CARDINALITY FIELDS FROM FULLTEXT                                                    [MEMORY]       │ ║
║ │    Add to tika-config.xml or index excludes:                                                                       │ ║
║ │    - xmpGImg:image (base64 thumbnail data)                                                                         │ ║
║ │    - imageData (binary image data)                                                                                 │ ║
║ │    - xmpMM:* (XMP metadata IDs)                                                                                    │ ║
║ │    → Estimated memory savings: ~450MB heap                                                                         │ ║
║ │                                                                                                                    │ ║
║ └────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘ ║
║                                                                                                                        ║
║ ┌────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┐ ║
║ │ DEPRECATION MIGRATION PROGRESS                                                                                     │ ║
║ │                                                                                                                    │ ║
║ │ Target: 0% queries hitting /oak:index/lucene                                                                       │ ║
║ │ Current: 45% ████████████████████████░░░░░░░░░░░░░░░░░░░░░░░░                                                       │ ║
║ │                                                                                                                    │ ║
║ │ Query Coverage by Index:                                                                                           │ ║
║ │   /oak:index/lucene (DEPRECATED)    45% ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░                                     │ ║
║ │   /oak:index/damAssetLucene         25% ▓▓▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░                                     │ ║
║ │   /oak:index/cqPageLucene           18% ▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░                                     │ ║
║ │   /oak:index/ntBaseLucene            8% ▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░                                     │ ║
║ │   Property indexes                   4% ▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░                                     │ ║
║ │                                                                                                                    │ ║
║ └────────────────────────────────────────────────────────────────────────────────────────────────────────────────────┘ ║
║                                                                                                                        ║
╚═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╝
```

## Consequences

### Positive

1. **Single pane of glass** for all indexing health information
2. **Actionable recommendations** instead of raw data
3. **Deprecation tracking** helps plan index modernization
4. **Query correlation** identifies root causes of slow queries
5. **Memory optimization** through cardinality analysis
6. **Migration confidence** with progress tracking

### Negative

1. **Complexity** - aggregating multiple MBeans requires careful error handling
2. **Performance overhead** - collecting data from multiple sources
3. **AEM-specific logic** - may not generalize to non-AEM Oak deployments
4. **Maintenance burden** - must track Oak/AEM version changes

### Risks

1. **MBean availability** - some MBeans may not exist in all configurations
2. **Data staleness** - snapshots may not reflect current state
3. **Recommendation accuracy** - automated suggestions may not fit all use cases

## Implementation Notes

### MBean Dependencies

| MBean | Package | Availability |
|-------|---------|--------------|
| `LukeIndexStatsMBean` | `oak-luke-bundle` | Custom (this project) |
| `LuceneIndexMBean` | `oak-lucene` | Oak 1.8+ |
| `IndexStatsMBean` | `oak-core` | Oak 1.0+ |
| `QueryStatManagerMBean` | `oak-core` | Oak 1.6+ |
| `CheckpointMBean` | `oak-core` | Oak 1.0+ |
| `RepositoryManagementMBean` | `oak-jcr` | Oak 1.0+ |

### Configuration

```yaml
index-doctor:
  collection:
    interval: 5m              # How often to collect data
    history-retention: 30d    # How long to keep snapshots
    
  thresholds:
    deletion-ratio-warning: 10%
    deletion-ratio-critical: 25%
    cardinality-warning: 50
    cardinality-critical: 100
    async-lag-warning: 30s
    async-lag-critical: 120s
    deprecated-usage-target: 0%
    
  recommendations:
    auto-generate-definitions: true
    include-rollback-scripts: true
```

## Open Questions

1. Should Index Doctor be a separate bundle or integrated into oak-luke-bundle?
2. How to persist historical data without impacting repository performance?
3. Should recommendations include automatic index definition deployment?
4. How to handle multi-instance AEM (author/publish) correlation?

## References

- [Oak Index Management](https://jackrabbit.apache.org/oak/docs/query/indexing.html)
- [AEM Index Best Practices](https://experienceleague.adobe.com/docs/experience-manager-65/deploying/practices/best-practices-for-queries-and-indexing.html)
- [Oak Query Troubleshooting](https://jackrabbit.apache.org/oak/docs/query/query-troubleshooting.html)
- [LUKE Lucene Index Toolbox](https://github.com/DmitryKey/luke)

---

**Next Steps**:
1. ✅ Phase 1 complete (LUKE integration)
2. Design Phase 2 data collection architecture
3. Prototype query-index correlation algorithm
4. Define index definition generation templates

