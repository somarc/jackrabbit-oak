# LUKE 4.0.0-ALPHA vs. oak-run-luke Feature Parity Analysis

## Executive Summary

**Status:** ⚠️ **PARTIAL PARITY** - Core inspection features implemented, but missing:
- GUI (intentional - JMX/CLI instead)
- Document browsing/editing
- Search/Query features
- Advanced analysis plugins

---

## Feature Comparison Matrix

| Feature Category | LUKE 4.0.0-ALPHA | oak-run-luke OSGi | oak-run-luke JAR | Priority |
|------------------|------------------|-------------------|------------------|----------|
| **Index Statistics** | | | | |
| Document count, terms, fields | ✅ GUI | ✅ JMX | ❌ Planned | **HIGH** |
| Top N terms by doc freq | ✅ GUI | ✅ JMX | ❌ Planned | **HIGH** |
| Field-level term counts | ✅ GUI | ✅ JMX | ❌ Planned | **HIGH** |
| Index size analysis | ✅ GUI | ✅ JMX | ❌ Planned | **HIGH** |
| Segment-level details | ✅ GUI | ❌ Missing | ❌ Missing | **MEDIUM** |
| **Document Browsing** | | | | |
| Browse by doc ID | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| View stored fields | ✅ GUI | ❌ Missing | ❌ Missing | **MEDIUM** |
| View term vectors | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| Reconstruct documents | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| **Search & Query** | | | | |
| Query parser interface | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| Explain queries | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| "More Like This" | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| **Term Analysis** | | | | |
| HighFreqTerms (doc freq) | ✅ CLI + GUI | ✅ JMX | ❌ Planned | **HIGH** |
| HighFreqTerms (total freq) | ✅ CLI + GUI | ✅ JMX | ❌ Planned | **HIGH** |
| Term positions/offsets | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| Payload viewing | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| **Field Analysis** | | | | |
| Field list with flags | ✅ GUI | ✅ JMX | ❌ Planned | **HIGH** |
| Field term count/percentage | ✅ GUI | ✅ JMX | ❌ Planned | **HIGH** |
| Field-level codec details | ✅ GUI | ❌ Missing | ❌ Missing | **MEDIUM** |
| **Index Files** | | | | |
| List index files | ✅ GUI | ✅ JMX | ❌ Planned | **MEDIUM** |
| File sizes | ✅ GUI | ✅ JMX | ❌ Planned | **MEDIUM** |
| Commit points | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| Segment info | ✅ GUI | ❌ Missing | ❌ Missing | **MEDIUM** |
| **Tools & Utilities** | | | | |
| CheckIndex | ✅ GUI | ❌ Missing | ❌ Missing | **MEDIUM** |
| Export to XML | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| Index optimization | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| Cleanup | ✅ GUI | ❌ Missing | ❌ Missing | **LOW** |
| **Plugins** | | | | |
| Analyzer Tool | ✅ Plugin | ❌ Missing | ❌ Missing | **LOW** |
| Similarity Designer | ✅ Plugin | ❌ Missing | ❌ Missing | **LOW** |
| Vocabulary Analysis | ✅ Plugin | ❌ Missing | ❌ Missing | **MEDIUM** |
| Zipf Analysis | ✅ Plugin | ❌ Missing | ❌ Missing | **MEDIUM** |
| Scripting | ✅ Plugin | ❌ Missing | ❌ Missing | **LOW** |
| Hadoop/HDFS support | ✅ Plugin | N/A (Oak-specific) | N/A | **N/A** |
| **Novel Oak-Specific Features** | | | | |
| IndexCopier integration | N/A | ✅ JMX | ❌ Planned | **HIGH** |
| Oak NodeStore awareness | N/A | ✅ OSGi | ❌ Planned | **HIGH** |
| JMX MBean interface | N/A | ✅ JMX | N/A | **HIGH** |
| Zero-dump inspection | N/A | ✅ JMX | ✅ Planned | **HIGH** |
| Multi-index discovery | N/A | ✅ JMX | ✅ Planned | **HIGH** |
| Oak 1.22.x compatibility | N/A | ✅ OSGi | ✅ Planned | **CRITICAL** |

---

## Critical Differences

### 1. **Interface Philosophy**
- **LUKE:** Desktop GUI (Swing/Thinlet) - visual, interactive
- **oak-run-luke:** JMX MBean + CLI - production-safe, scriptable

### 2. **Index Access**
- **LUKE:** Requires exclusive lock, direct filesystem access
- **oak-run-luke:** 
  - OSGi: Works with **live AEM**, leverages IndexCopier cache
  - JAR: Standalone, read-only access to segment store

### 3. **Target Use Case**
- **LUKE:** Development, offline analysis, index debugging
- **oak-run-luke:** 
  - **Production monitoring** (JMX)
  - **Capacity planning** (what's eating space?)
  - **On-prem AEM troubleshooting**

---

## Feature Parity Status

### ✅ **COMPLETE - Core Inspection (HIGH Priority)**
These are the **"what's eating my index space?"** features:

1. **getLocalIndexDirectories()** - List all cached indexes
2. **getLukeBasicStats(indexPath)** - Index overview
3. **getLukeFieldInfo(indexPath)** - Field-level term counts
4. **getFieldSizeAnalysis(indexPath)** - Top 10 largest fields
5. **getTopTermsByDocFreq(indexPath, fieldName, N)** - Top N terms
6. **getIndexCompositionStats(indexPath)** - Comprehensive breakdown

**Result:** ✅ **Answers the primary question:** "What is taking up space in my 100GB+ index?"

---

## ⚠️ **MISSING - Important Features (MEDIUM Priority)**

### 1. **Segment-Level Analysis**
**LUKE Has:**
- Detailed segment info (codec, version, file list)
- Per-segment statistics

**Why it matters:**
- Understanding merge policy effectiveness
- Identifying over-segmentation

**Complexity:** Medium (requires SegmentInfos parsing)

### 2. **Vocabulary Analysis Plugin**
**LUKE Has:**
- Term distribution analysis
- Vocabulary growth charts
- Field-specific vocabulary stats

**Why it matters:**
- Understanding tokenization effectiveness
- Identifying over-indexing

**Complexity:** Medium (statistical analysis)

### 3. **Zipf Analysis Plugin**
**LUKE Has:**
- Term frequency distribution (Zipf's law)
- Charts showing frequency vs. rank
- Outlier detection

**Why it matters:**
- Validates index quality
- Detects anomalies (spam, repetition)

**Complexity:** Medium (statistical + charting)

### 4. **CheckIndex Tool**
**LUKE Has:**
- Lucene's CheckIndex integration
- Corruption detection
- Repair options

**Why it matters:**
- Critical for production troubleshooting
- Detects index corruption

**Complexity:** Low (call existing Lucene CheckIndex)

---

## ❌ **INTENTIONALLY OMITTED - Low Priority**

### 1. **GUI Interface**
- **Rationale:** JMX is better for production AEM environments
- **Alternative:** JMX console in AEM, VisualVM, JConsole

### 2. **Document Editing**
- **Rationale:** Dangerous in production, Oak has better tools
- **Alternative:** oak-run console, AEM query tools

### 3. **Search/Query Interface**
- **Rationale:** AEM has query debugger, explain query tools
- **Alternative:** AEM Query Performance tool, query.json explain=true

### 4. **Analyzer Testing**
- **Rationale:** Development-time tool, not production monitoring
- **Alternative:** Unit tests, Lucene AnalyzerUtils

---

## 🚀 **NOVEL FEATURES - Oak-Specific (HIGH Value)**

These features are **NOT in LUKE** but critical for Oak/AEM:

### 1. **IndexCopier Cache Integration**
- Works with Oak's local index cache
- No need to dump indexes from datastore
- **100GB+ indexes:** Saves hours of dump time

### 2. **Multi-Index Auto-Discovery**
- Scans `crx-quickstart/repository/index/`
- Detects all Lucene indexes
- Shows metadata vs. real indexes

### 3. **Oak 1.22.x Compatibility**
- Works with **AEM 6.5.x on-prem** (Oak 1.22.22)
- No reliance on newer Oak APIs
- Backwards compatible

### 4. **Production-Safe Operation**
- Read-only
- JMX operations are synchronous (no background threads eating resources)
- Performance warnings documented

### 5. **Accurate Term Counting**
- Handles Lucene 4.7.2's `Terms.size() == -1`
- Explicit iteration when codec doesn't cache
- Shows real counts for capacity planning

---

## Recommended Next Steps

### Phase 1: Complete oak-run-luke JAR (HIGH Priority)
**Goal:** Standalone JAR for offline analysis

#### Features to Implement:
1. ✅ **DONE:** JMX MBean interface
2. **TODO:** CLI commands for standalone JAR:
   ```bash
   # Basic stats
   java -jar oak-run-luke.jar stats --index /path/to/segment-store --index-path /oak:index/damAssetLucene
   
   # Field analysis
   java -jar oak-run-luke.jar fields --index /path/to/segment-store --index-path /oak:index/lucene
   
   # Top terms
   java -jar oak-run-luke.jar top-terms --index /path/to/segment-store --index-path /oak:index/lucene --field :fulltext --top 100
   
   # Composition analysis
   java -jar oak-run-luke.jar composition --index /path/to/segment-store --index-path /oak:index/damAssetLucene
   
   # List all indexes
   java -jar oak-run-luke.jar list --index /path/to/segment-store
   ```

**Complexity:** Low (reuse MBean implementation logic)

**Benefit:** Offline analysis for maintenance windows

---

### Phase 2: Add Segment Analysis (MEDIUM Priority)
**Goal:** Understand segment structure

#### Features to Implement:
1. **getSegmentInfo(indexPath)** - List segments with sizes
2. **getSegmentDetails(indexPath, segmentName)** - Detailed segment stats
3. **getCodecInfo(indexPath)** - Codec version, configuration

**Complexity:** Medium (SegmentInfos API)

**Benefit:** 
- Diagnose merge policy issues
- Identify over-segmentation
- Optimize index structure

---

### Phase 3: Add Vocabulary Analysis (MEDIUM Priority)
**Goal:** Statistical analysis of term distribution

#### Features to Implement:
1. **getVocabularyStats(indexPath, fieldName)** - Vocabulary size, growth
2. **getTermDistribution(indexPath, fieldName)** - Frequency buckets
3. **getZipfAnalysis(indexPath, fieldName)** - Zipf's law validation

**Complexity:** Medium (statistical analysis)

**Benefit:**
- Validate tokenization quality
- Detect over-indexing
- Optimize analyzer configuration

---

### Phase 4: Add CheckIndex Integration (LOW Priority)
**Goal:** Index health validation

#### Features to Implement:
1. **checkIndex(indexPath)** - Run CheckIndex, return status
2. **checkIndexVerbose(indexPath)** - Full CheckIndex output

**Complexity:** Low (call Lucene CheckIndex)

**Benefit:** Critical for corruption detection

---

## Success Criteria for Apache Oak Contribution

To get **enthusiastic merge** from Oak team, prioritize:

### 1. ✅ **DONE - Core Value Proposition**
- Answers: "What's in my giant index?"
- Works with AEM 6.5.x (Oak 1.22.x)
- Production-safe (read-only, documented performance)

### 2. **TODO - Completeness**
- Standalone JAR with full CLI
- Comprehensive documentation
- Performance benchmarks (100GB+ indexes)

### 3. **TODO - Code Quality**
- Unit tests (Lucene index fixtures)
- Integration tests (Oak segment store)
- Javadoc coverage

### 4. **TODO - Oak Integration**
- Consider: IndexTracker integration for newer Oak versions
- Consider: Elastic index support
- Consider: Solr index support (if feasible)

---

## Recommendation: Focus on Standalone JAR CLI

**Why?**
1. ✅ OSGi bundle is **feature-complete** for JMX use case
2. ❌ Standalone JAR is **incomplete** (no CLI commands yet)
3. 🎯 Standalone JAR enables **offline analysis** (critical for maintenance)

**Next Action:**
Build out `oak-run-luke` CLI commands (Phase 1 above) to match OSGi MBean functionality.

---

## Appendix: LUKE Algorithms We've Adopted

### 1. FieldTermCount
**Source:** `luke-4.0.0-ALPHA/src/org/getopt/luke/FieldTermCount.java`

**What we implemented:**
```java
public class FieldTermCount implements Comparable<FieldTermCount> {
    public String fieldName;
    public long termCount;
    public double percentage;
}
```

**Used in:** `getFieldSizeAnalysis()`, `getIndexCompositionStats()`

### 2. HighFreqTerms (partial)
**Source:** `luke-4.0.0-ALPHA/src/org/getopt/luke/HighFreqTerms.java`

**What we implemented:**
- Top N terms by doc freq: `getTopTermsByDocFreq()`
- TermInfo data structure with docFreq + totalTermFreq

**What we're missing:**
- Sorting by total term freq (vs. doc freq)
- Multiple field analysis in one pass

### 3. Manual Term Counting
**Source:** LUKE's handling of `Terms.size() == -1`

**What we implemented:**
```java
private long countTermsManually(Terms terms) throws IOException {
    long count = 0;
    TermsEnum te = terms.iterator(null);
    while (te.next() != null) {
        count++;
    }
    return count;
}
```

**Why it matters:** Lucene 4.7.2 codecs don't always cache term counts

---

## Conclusion

### Current State: ⭐⭐⭐⭐☆ (4/5 stars)

**Strengths:**
- ✅ Core "what's eating space?" question **SOLVED**
- ✅ Production-safe, live AEM integration via OSGi
- ✅ Oak 1.22.x compatibility (AEM 6.5.x)
- ✅ Novel features (IndexCopier, multi-index discovery)

**Gaps:**
- ❌ No standalone CLI (JAR incomplete)
- ⚠️ Missing statistical analysis (Zipf, vocabulary)
- ⚠️ No segment-level details
- ⚠️ No CheckIndex integration

**Recommendation:**
1. **SHORT TERM:** Complete standalone JAR CLI (Phase 1) - **2-3 days**
2. **MEDIUM TERM:** Add segment analysis (Phase 2) - **3-5 days**
3. **LONG TERM:** Statistical plugins (Phase 3) - **5-10 days**

**For Apache Oak contribution:**
- Focus on **Phase 1** (standalone JAR)
- Add **unit/integration tests**
- Write comprehensive **docs + benchmarks**
- Submit PR with: "LUKE-inspired index inspection for Oak 1.22+"

---

**This is already best-in-class for Oak/AEM.** Let's finish the standalone JAR to make it a no-brainer merge! 🚀

