# 🔬 Oak LUKE - Lucene Index Analysis Toolkit

> **"I have a 100GB Lucene index... WTF is in there?"**

Oak LUKE brings the power of [LUKE (Lucene Index Toolbox)](https://github.com/DmitryKey/luke) to Apache Jackrabbit Oak, specifically designed for AEM on-prem environments where indexes can grow massive and opaque.

```
╔═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╗
║                              INDEX INSIGHTS - ACTIONABLE OPTIMIZATION GUIDE                                            ║
╠═══════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╣
║ Index: /oak:index/damAssetLucene                                                                                       ║
║ Documents: 1,234,567 active | 45,000 deleted (3.5% waste) | Size: 87.3 GB                                              ║
╠══════════════════════════╤═══════════════╤══════════╤══════════╤══════════════════════════════╤═══════════════════════╣
║ Field                    │ Terms (Size)  │ Coverage │ Card.    │ Top Values                   │ Suggestion            ║
╠══════════════════════════╪═══════════════╪══════════╪══════════╪══════════════════════════════╪═══════════════════════╣
║ :fulltext                │    45,000,000 │   95.0%  │   2500.0 │ report(12k), email(8k)...    │ Use pre-extracted cache║
║ :path                    │     1,234,567 │  100.0%  │      1.0 │ /content/dam/...(500)        │ OK                    ║
║ jcr:primaryType          │           127 │  100.0%  │      0.0 │ dam:Asset(800k)              │ OK                    ║
╚══════════════════════════╧═══════════════╧══════════╧══════════╧══════════════════════════════╧═══════════════════════╝
```

---

## ✨ Features

### 🎯 Actionable Insights (Not Just Data!)

Unlike raw index dumps, Oak LUKE provides **"aha!" moments**:

| Feature | Question It Answers |
|---------|---------------------|
| **Index Insights** | "What's eating my index? What should I DO about it?" |
| **Content Distribution** | "Is my blog indexed? Why's search slow on /content/dam?" |
| **Cardinality Analysis** | "Which fields are memory hogs with millions of unique values?" |
| **Duplicate Detection** | "Are there junk variations like urgent/URGENT/Urgent?" |
| **Health Report** | "Is my index fragmented? Should I optimize?" |

### 📦 Two Deployment Options

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                                                                             │
│   ┌─────────────────────────┐        ┌─────────────────────────┐           │
│   │    JMX MBean Bundle     │        │    Standalone CLI/GUI   │           │
│   │   (oak-luke-bundle)     │        │    (oak-run-luke)       │           │
│   │                         │        │                         │           │
│   │  • Live AEM instance    │        │  • Offline analysis     │           │
│   │  • JMX Console access   │        │  • No running Oak       │           │
│   │  • Production-safe      │        │  • Batch processing     │           │
│   │  • Read-only ops        │        │  • Interactive GUI      │           │
│   └─────────────────────────┘        └─────────────────────────┘           │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### Standalone CLI (Recommended for First-Time Users)

```bash
# Build the JAR
cd jackrabbit-oak/oak-run-luke
mvn clean package -DskipTests

# Discover indexes
java -jar target/oak-run-luke-*.jar list /path/to/crx-quickstart

# ⭐ Get actionable insights (START HERE!)
java -jar target/oak-run-luke-*.jar inspect /path/to/index --insights

# Launch interactive GUI
java -jar target/oak-run-luke-*.jar gui /path/to/index
```

### JMX MBean (For Live AEM Instances)

1. Deploy `oak-luke-bundle-*.jar` to AEM
2. Access via JMX Console: `http://localhost:4502/system/console/jmx`
3. Find: `org.apache.jackrabbit.oak:name=LukeIndexStats,type=LukeIndexStats`

---

## 📖 CLI Commands

### `list` - Discover Indexes

```bash
java -jar oak-run-luke.jar list /path/to/crx-quickstart
```

```
═══════════════════════════════════════════════════════════════════════════════════════
LUCENE INDEX DISCOVERY
═══════════════════════════════════════════════════════════════════════════════════════
Found 12 index(es):

Index Path                                    |    Documents |       Size | Status
----------------------------------------------+--------------+------------+--------
damAssetLucene-11-custom-1                    |    1,234,567 |    87.3 GB | OK
cqPageLucene-7                                |      456,789 |    12.1 GB | OK
ntBaseLucene-1                                |       45,331 |     5.4 MB | OK
```

### `inspect` - Deep Index Analysis

#### ⭐ Actionable Insights (Recommended!)

```bash
java -jar oak-run-luke.jar inspect /path/to/index --insights
```

Shows a table with:
- **Field** - Index field name
- **Terms (Size)** - Number of unique terms (proxy for size contribution)
- **Coverage** - % of documents containing this field
- **Cardinality** - Unique values per document (high = memory intensive)
- **Top Values** - Most common values with doc counts
- **Suggestion** - Actionable recommendation

#### Content Distribution

```bash
java -jar oak-run-luke.jar inspect /path/to/index --content-dist 3
```

```
═══════════════════════════════════════════════════════════════════════════════════════
CONTENT DISTRIBUTION ANALYSIS
═══════════════════════════════════════════════════════════════════════════════════════
Content Path                                       |  Documents | % Total | Bar
---------------------------------------------------+------------+---------+-----
/content/dam/projects                              |    500,000 |   40.5% | ████████
/content/we-retail/us                              |    200,000 |   16.2% | ███
/content/experience-fragments                      |     50,000 |    4.1% | 

INSIGHTS:
• Top content area: /content/dam/projects (40.5% of index)
• DAM content: 45.2% - Heavy DAM usage. Ensure pre-extracted cache for re-indexing.
```

#### Field Cardinality Analysis

```bash
java -jar oak-run-luke.jar inspect /path/to/index --cardinality
```

```
═══════════════════════════════════════════════════════════════════════════════════════
FIELD CARDINALITY ANALYSIS
═══════════════════════════════════════════════════════════════════════════════════════
Cardinality = Unique Values / Documents. High cardinality = memory intensive.

Field                      | Unique Terms |  Doc Count | Coverage | Cardinality | Status
---------------------------+--------------+------------+----------+-------------+------------------
jcr:uuid                   |    1,234,567 |  1,234,567 |  100.0%  |        1.00 | 
:fulltext                  |   50,000,000 |  1,200,000 |   97.2%  |       41.67 | ⚡ MODERATE
custom:searchableId        |   10,000,000 |     50,000 |    4.1%  |      200.00 | ⚠️ HIGH CARDINALITY
sparseField                |           50 |        100 |    0.0%  |        0.50 | 📉 SPARSE

LEGEND: ⚠️ HIGH CARDINALITY (>100) = Consider faceted search or filtering
        ⚡ MODERATE (>10) = Monitor for growth
        📉 SPARSE (<5% coverage, <1k docs) = Consider if field is needed
```

#### Duplicate Value Detection

```bash
java -jar oak-run-luke.jar inspect /path/to/index --duplicates cq:tags
```

```
═══════════════════════════════════════════════════════════════════════════════════════
DUPLICATE VALUE DETECTION
═══════════════════════════════════════════════════════════════════════════════════════
Field: cq:tags

⚠️ Found 12 groups of potential duplicates:

Group 'marketing' (5,234 total docs):
    • "marketing" (3,000 docs)
    • "Marketing" (1,500 docs)
    • "MARKETING" (734 docs)

Group 'urgent' (1,234 total docs):
    • "urgent" (800 docs)
    • "URGENT" (300 docs)
    • "Urgent" (134 docs)

RECOMMENDATION: Consider normalizing these values during indexing
                or implementing a custom analyzer with case-folding.
```

#### Other Analysis Options

```bash
# Health report with score
java -jar oak-run-luke.jar inspect /path/to/index --health

# Segment analysis
java -jar oak-run-luke.jar inspect /path/to/index --segments

# List all fields
java -jar oak-run-luke.jar inspect /path/to/index --fields

# Analyze specific field
java -jar oak-run-luke.jar inspect /path/to/index --field :fulltext --top-terms 50

# Sample documents
java -jar oak-run-luke.jar inspect /path/to/index --sample 5

# View specific document
java -jar oak-run-luke.jar inspect /path/to/index --doc 12345
```

### `gui` - Interactive Explorer

```bash
java -jar oak-run-luke.jar gui /path/to/index
```

Launches a Swing-based GUI similar to the original LUKE tool:
- Tree view of available indexes
- Tabbed panels for Overview, Fields, Terms, Segments, Health
- Interactive field and term browsing

---

## 🖥️ JMX MBean Operations

When deployed as an OSGi bundle, access via JMX Console:

### Discovery & Navigation
| Operation | Description |
|-----------|-------------|
| `getLocalIndexDirectories()` | List all cached indexes |
| `getLocalIndexPath(indexPath)` | Get filesystem path for index |
| `validateLocalIndex(indexPath)` | Verify index integrity |

### Analysis
| Operation | Description | Cost |
|-----------|-------------|------|
| `getLukeBasicStats(indexPath)` | Doc count, fields, size | Fast |
| `getIndexHealthReport(indexPath)` | Health score & recommendations | Fast |
| `getSegmentInfo(indexPath)` | Segment details & deletions | Fast |

### Field Analysis
| Operation | Description | Cost |
|-----------|-------------|------|
| `getLukeFieldInfo(indexPath, maxFields)` | Field list with term counts | ⚠️ Expensive |
| `getFieldSizeAnalysis(indexPath, maxFields)` | Fields ranked by size | ⚠️ Expensive |
| `getFieldCardinality(indexPath, maxFields)` | Cardinality analysis | ⚠️ Expensive |
| `getFieldFlags(indexPath)` | Indexed/stored/vectors flags | Fast |

### Term Analysis
| Operation | Description | Cost |
|-----------|-------------|------|
| `getLukeTermStats(indexPath, field, maxTerms)` | Term details for field | Moderate |
| `getTopTermsByDocFreq(indexPath, field, maxTerms)` | Most common terms | ⚠️ Expensive |
| `getTermDistribution(indexPath, field, buckets)` | Zipf distribution | Moderate |

### Actionable Insights
| Operation | Description | Cost |
|-----------|-------------|------|
| `getIndexInsights(indexPath, maxFields)` | ⭐ Optimization table | ⚠️ Expensive |
| `getContentDistribution(indexPath, depth)` | Content breakdown by path | Moderate |
| `detectDuplicateValues(indexPath, field, max)` | Find duplicate variations | Moderate |

### Document Inspection
| Operation | Description | Cost |
|-----------|-------------|------|
| `sampleDocuments(indexPath, size, fields)` | Random document sample | Fast |
| `getDocument(indexPath, docId)` | View specific document | Fast |

---

## ⚡ Performance Guide

### For Large Indexes (100GB+)

| Index Size | `--insights` | `--content-dist` | `--cardinality` | `--fields` |
|------------|--------------|------------------|-----------------|------------|
| < 1GB | Seconds | Seconds | Seconds | Seconds |
| 1-10GB | 1-5 min | 1-2 min | 2-5 min | 1-5 min |
| 10-50GB | 5-30 min | 2-10 min | 10-30 min | 5-30 min |
| 50-100GB | 30-60 min | 10-30 min | 30-60 min | 30-60 min |
| 100GB+ | 1-2 hrs | 30-60 min | 1-2 hrs | 1-2 hrs |

### Recommendations

1. **Start with `--health`** - Fast health check first
2. **Use specific fields** - `--field :fulltext` instead of scanning all fields
3. **Limit results** - Use `--top-terms 50` instead of default 100+
4. **Off-peak hours** - Run expensive operations during maintenance windows
5. **Standalone JAR** - Use CLI for very large indexes, avoid JMX timeouts

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           AEM / Oak Repository                                       │
│                                                                                      │
│   crx-quickstart/repository/index/                                                   │
│   ├── damAssetLucene-11-custom-1/                                                    │
│   │   └── data/                      ◄─── Lucene index files                        │
│   │       ├── segments_N                                                             │
│   │       ├── _0.cfs                                                                 │
│   │       └── ...                                                                    │
│   ├── cqPageLucene-7/                                                                │
│   └── ...                                                                            │
│                                                                                      │
│   ┌─────────────────────────────────────────────────────────────────────────────┐   │
│   │                          oak-luke-bundle (OSGi)                              │   │
│   │                                                                              │   │
│   │   LukeIndexStatsService ──► LukeIndexStatsMBeanImplSimple                   │   │
│   │        │                              │                                      │   │
│   │        ▼                              ▼                                      │   │
│   │   IndexCopier ──────────────► Lucene DirectoryReader                        │   │
│   │   (optional)                    (FSDirectory.open)                          │   │
│   │                                                                              │   │
│   │   Exposes: JMX MBean at org.apache.jackrabbit.oak:name=LukeIndexStats       │   │
│   └─────────────────────────────────────────────────────────────────────────────┘   │
│                                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────────────────────────────┐
│                           oak-run-luke (Standalone)                                  │
│                                                                                      │
│   java -jar oak-run-luke.jar [command] /path/to/index [options]                     │
│                                                                                      │
│   Commands:                                                                          │
│   ├── list      Discover indexes in repository                                      │
│   ├── inspect   Deep analysis with various options                                  │
│   ├── gui       Launch interactive Swing GUI                                        │
│   └── help      Show usage information                                              │
│                                                                                      │
│   Uses: Direct Lucene API (no Oak/AEM required)                                     │
│                                                                                      │
└─────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 🔧 Building

```bash
# Build standalone JAR
cd jackrabbit-oak/oak-run-luke
mvn clean package -DskipTests

# Build OSGi bundle
cd jackrabbit-oak/oak-luke-bundle
mvn clean package -DskipTests

# Build both
cd jackrabbit-oak
mvn clean package -DskipTests -pl oak-run-luke,oak-luke-bundle
```

---

## 🎯 Use Cases

### 1. "Why is my index so big?"

```bash
java -jar oak-run-luke.jar inspect /path/to/index --insights
```

Look at the **Terms (Size)** column - fields with millions of terms dominate your index.

### 2. "What content is being indexed?"

```bash
java -jar oak-run-luke.jar inspect /path/to/index --content-dist 3
```

See breakdown by `/content/dam/projects`, `/content/we-retail`, etc.

### 3. "Why is re-indexing so slow?"

```bash
java -jar oak-run-luke.jar inspect /path/to/index --insights
```

Check for `:fulltext` field with high term count → Use `oak-run tika --generate/--populate` for pre-extracted cache.

### 4. "Why is my search slow?"

```bash
# Check for high cardinality fields
java -jar oak-run-luke.jar inspect /path/to/index --cardinality

# Check for index fragmentation
java -jar oak-run-luke.jar inspect /path/to/index --health
```

### 5. "Are my tags a mess?"

```bash
java -jar oak-run-luke.jar inspect /path/to/index --duplicates cq:tags
```

Find variations like `Marketing` vs `marketing` vs `MARKETING`.

### 6. "What does an indexed document look like?"

```bash
# Random sample
java -jar oak-run-luke.jar inspect /path/to/index --sample 5

# Specific document
java -jar oak-run-luke.jar inspect /path/to/index --doc 12345
```

---

## 📋 Compatibility

| Component | Version |
|-----------|---------|
| Apache Oak | 1.22.x (AEM 6.5.x) |
| Lucene | 4.7.2-oak2 |
| Java | 8+ |
| AEM | 6.5.x on-prem |

---

## 🤝 Related Tools

| Tool | Purpose |
|------|---------|
| `oak-run tika` | Pre-extract text for faster re-indexing |
| `oak-run console` | Interactive Oak repository exploration |
| `oak-run checkpoints` | Manage Oak checkpoints |
| `LuceneIndexMBean` | High-level index stats (built into Oak) |

---

## 📚 Further Reading

- [Oak Lucene Index Documentation](https://jackrabbit.apache.org/oak/docs/query/lucene.html)
- [Index Management Best Practices](https://jackrabbit.apache.org/oak/docs/query/indexing.html)
- [Original LUKE Tool](https://github.com/DmitryKey/luke)

---

## 📄 License

Apache License, Version 2.0

---

<p align="center">
  <b>Built for AEM administrators who need to understand their indexes, not just stare at them.</b>
</p>
