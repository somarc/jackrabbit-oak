# Fulltext Backup & Pre-Extracted Cache Design

## Problem Statement

### The Pain Point (100GB+ Indexes)
When fulltext indexes grow to 100GB+, the **binary fulltext extraction** becomes:
- ⚠️ **Insurmountably painful** - Re-indexing takes WEEKS
- 💰 **Expensive** - CPU/memory cost of Tika extraction at scale
- 🐌 **Slow** - Binary datastore I/O is the bottleneck

### Current oak-run Solution (Offline Only)
```bash
# Step 1: Extract fulltext from binaries → CSV
java -jar oak-run-1.22.20.jar tika \
  --fds-path crx-quickstart/repository/datastore \
  crx-quickstart/repository/segmentstore \
  --data-file oak-binary-stats.csv \
  --generate

# Step 2: Populate pre-extracted cache from CSV
java -jar oak-run-1.22.20.jar tika \
  --data-file oak-binary-stats.csv \
  --store-path ./store \
  --index-dir crx-quickstart/repository/index/<affected-index-cache>/data \
  populate
```

**Limitations:**
- ❌ Requires AEM shutdown (offline)
- ❌ Manual multi-step process
- ❌ No visibility into progress
- ❌ Difficult for on-prem customers

---

## Proposed Solution: JMX MBean for Live Fulltext Backup

### Vision
**A single JMX operation that:**
1. ✅ Backs up fulltext data to `/store` (like `oak-run tika --generate`)
2. ✅ Configures pre-extracted cache (OSGi config manipulation)
3. ✅ Handles video mimetypes (EmptyParser config)
4. ✅ Works on **LIVE AEM** (no downtime!)
5. ✅ Provides progress tracking via JMX

---

## Feature Breakdown

### Phase 1: Track Fulltext Index Size (EASY - 1 hour)

**Add to existing `LukeIndexStatsMBean`:**

```java
/**
 * Get fulltext-specific statistics for an index.
 * Shows binary count, total size, estimated extraction cost.
 */
String getFulltextStats(String indexPath) throws IOException;
```

**Output:**
```
═══════════════════════════════════════════════════════════════
FULLTEXT INDEX STATISTICS
═══════════════════════════════════════════════════════════════
Index: /oak:index/damAssetLucene
Location: /Users/mhess/aem/onprem/6523/crx-quickstart/repository/index/damAssetLucene-1747319650363

FULLTEXT FIELD ANALYSIS:
  Field: :fulltext
  Total Terms: 1,234,567
  Estimated Size: 45.2 GB
  
BINARY REFERENCES (if available):
  Total Binaries: 50,000
  Video Binaries: 38,770 (77.5%)
  Document Binaries: 11,230 (22.5%)
  
EXTRACTION COST ESTIMATE:
  With Tika: ~120 hours CPU time
  With Pre-Extracted Cache: ~5 hours (95% reduction)
  
RECOMMENDATION:
  ⚠️  Index is LARGE. Consider pre-extracted cache strategy.
═══════════════════════════════════════════════════════════════
```

---

### Phase 2: Backup Fulltext Data (COMPLEX - 1-2 days)

**New JMX operation:**

```java
/**
 * Extract fulltext data from binaries and save to filesystem.
 * Mimics: oak-run tika --generate
 * 
 * WARNING: EXPENSIVE operation! May take hours for large repositories.
 * Progress can be monitored via getFulltextBackupProgress().
 * 
 * @param storePath Local filesystem path to store extracted text (e.g. /opt/aem/fulltext-store)
 * @param indexPaths Comma-separated list of index paths (e.g. "/oak:index/damAssetLucene,/oak:index/lucene")
 * @param includeVideos If false, skips video mimetypes (recommended for large repos)
 * @return Job ID for tracking progress
 */
String startFulltextBackup(String storePath, String indexPaths, boolean includeVideos) throws IOException;

/**
 * Get progress of running fulltext backup job.
 * 
 * @param jobId Job ID from startFulltextBackup
 * @return Progress report (percent complete, binaries processed, estimated time remaining)
 */
String getFulltextBackupProgress(String jobId) throws IOException;

/**
 * Cancel a running fulltext backup job.
 */
void cancelFulltextBackup(String jobId) throws IOException;
```

**Implementation Strategy:**

1. **Background Job**
   - Spawns async thread (not blocking JMX call)
   - Tracks progress in memory (Map<jobId, Progress>)
   - Logs progress every 100 binaries

2. **Binary Enumeration**
   - Uses Oak's `BlobStore` / `DataStore` API
   - Filters by mimetype if `includeVideos=false`
   - Batches for memory efficiency

3. **Tika Extraction**
   - Reuses existing Tika Parser from Oak
   - Writes to `$storePath/<blobId>.txt`
   - Error handling (corrupted binaries, timeouts)

4. **Output Format (compatible with oak-run tika)**
   ```
   /opt/aem/fulltext-store/
     ├── abc123def456.txt  (extracted text for blob abc123def456)
     ├── 789ghi012jkl.txt
     └── manifest.csv      (blobId, size, mimetype, extraction time)
   ```

**Complexity:** HIGH (BlobStore API, async job management, error handling)

---

### Phase 3: Populate Pre-Extracted Cache (COMPLEX - 1-2 days)

**New JMX operation:**

```java
/**
 * Populate pre-extracted cache from fulltext backup.
 * Mimics: oak-run tika --populate
 * 
 * This copies extracted text from $storePath into Oak's pre-extracted cache,
 * enabling fast re-indexing without binary extraction.
 * 
 * @param storePath Path to fulltext backup (from startFulltextBackup)
 * @param indexPath Target index path (e.g. "/oak:index/damAssetLucene")
 * @return Job ID for tracking progress
 */
String populatePreExtractedCache(String storePath, String indexPath) throws IOException;
```

**Implementation Strategy:**

1. **Cache Location**
   - Oak's pre-extracted cache: `$REPO/index/$INDEX_NAME/pre-extracted-text/`
   - Or configurable via `DataStoreTextProviderService`

2. **Copy Strategy**
   - Read from `$storePath/<blobId>.txt`
   - Write to cache with Oak's naming convention
   - Verify integrity (checksums)

3. **Index Compatibility**
   - Validate that blobs in `$storePath` match index's binary references
   - Warn if mismatches detected

**Complexity:** HIGH (Oak cache format, file I/O, validation)

---

### Phase 4: OSGi Config Manipulation (MEDIUM - 4-6 hours)

**New JMX operations:**

```java
/**
 * Configure DataStoreTextProviderService to use pre-extracted cache.
 * 
 * Sets:
 *   - org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreTextProviderService
 *   - dir = $storePath
 * 
 * @param storePath Path to pre-extracted text store
 */
void configurePreExtractedCache(String storePath) throws IOException;

/**
 * Enable always-use-pre-extracted-cache mode for Lucene.
 * 
 * Sets:
 *   - org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexProviderService.config
 *   - alwaysUsePreExtractedCache = true
 */
void enablePreExtractedCacheMode() throws IOException;

/**
 * Get current pre-extracted cache configuration.
 */
String getPreExtractedCacheConfig() throws IOException;
```

**Implementation Strategy:**

1. **ConfigurationAdmin API**
   ```java
   @Reference
   ConfigurationAdmin configAdmin;
   
   Configuration config = configAdmin.getConfiguration(
       "org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreTextProviderService",
       null
   );
   Dictionary<String, Object> props = config.getProperties();
   props.put("dir", storePath);
   config.update(props);
   ```

2. **Validation**
   - Check if path exists and is readable
   - Verify OSGi bundle is active
   - Rollback on failure

**Complexity:** MEDIUM (OSGi ConfigurationAdmin, service references)

---

### Phase 5: Video Mimetype Handling (MEDIUM - 4-6 hours)

**New JMX operations:**

```java
/**
 * Query repository for video asset mimetypes.
 * Uses JCR query:
 *   type=dam:Asset
 *   path=/content/dam
 *   property=jcr:content/metadata/dc:format
 *   property.value=video%
 * 
 * @return Mimetype distribution (e.g. "video/mp4 (38770), video/quicktime (5295), ...")
 */
String getVideoMimetypes() throws RepositoryException;

/**
 * Generate EmptyParser Tika config for video mimetypes.
 * Creates tika-config.xml with EmptyParser for all detected video mimetypes.
 * 
 * This prevents Tika from attempting extraction on video files during re-indexing.
 * 
 * @param outputPath Filesystem path to write tika-config.xml (e.g. /opt/aem/tika-config.xml)
 * @param customMimetypes Optional comma-separated list of additional mimetypes to skip
 * @return Path to generated config file
 */
String generateEmptyParserConfig(String outputPath, String customMimetypes) throws IOException;

/**
 * Apply Tika config to a Lucene index definition.
 * Updates /oak:index/$indexName/tika/config.xml
 * 
 * @param indexPath Oak index path (e.g. "/oak:index/damAssetLucene")
 * @param tikaConfigPath Path to tika-config.xml (from generateEmptyParserConfig)
 */
void applyTikaConfig(String indexPath, String tikaConfigPath) throws RepositoryException;
```

**Sample Output (`tika-config.xml`):**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<properties>
  <parsers>
    <parser class="org.apache.tika.parser.EmptyParser">
      <mime>video/mp4</mime>
      <mime>video/quicktime</mime>
      <mime>video/x-m4v</mime>
      <mime>video/x-ms-wmv</mime>
      <mime>video/3gpp</mime>
      <mime>video/mpeg</mime>
      <mime>video/x-f4v</mime>
    </parser>
  </parsers>
</properties>
```

**Implementation Strategy:**

1. **JCR Query Execution**
   ```java
   QueryManager qm = session.getWorkspace().getQueryManager();
   Query query = qm.createQuery(
       "SELECT [jcr:content/metadata/dc:format] FROM [dam:Asset] WHERE ...",
       Query.JCR_SQL2
   );
   QueryResult result = query.execute();
   // Aggregate facets (mimetype counts)
   ```

2. **Tika Config Generation**
   - Parse query results
   - Filter for `video/*` mimetypes
   - Generate XML using DOM or String builder
   - Write to filesystem

3. **Index Definition Update**
   - Connect to NodeStore
   - Navigate to `/oak:index/$indexName/tika`
   - Set `config.xml` property
   - Save changes

**Complexity:** MEDIUM (JCR query, XML generation, NodeStore writes)

---

## Complete Workflow: End-to-End JMX Operations

### Scenario: 100GB damAssetLucene with 38,770 videos

```
Step 1: Assess Current State
JMX: getFulltextStats("/oak:index/damAssetLucene")
→ Shows 45.2 GB fulltext, 77.5% videos, 120h extraction cost

Step 2: Detect Video Mimetypes
JMX: getVideoMimetypes()
→ Returns: video/mp4 (38770), video/quicktime (5295), ...

Step 3: Generate EmptyParser Config
JMX: generateEmptyParserConfig("/opt/aem/tika-config.xml", "")
→ Creates tika-config.xml with EmptyParser for all video mimetypes

Step 4: Apply Tika Config to Index
JMX: applyTikaConfig("/oak:index/damAssetLucene", "/opt/aem/tika-config.xml")
→ Updates /oak:index/damAssetLucene/tika/config.xml

Step 5: Backup Fulltext Data (Excluding Videos)
JMX: startFulltextBackup("/opt/aem/fulltext-store", "/oak:index/damAssetLucene", false)
→ Returns: jobId = "backup-12345"

Step 6: Monitor Progress
JMX: getFulltextBackupProgress("backup-12345")
→ "Processing: 5,000 / 11,230 binaries (44.5%), ETA: 2h 15m"

Step 7: Configure Pre-Extracted Cache
JMX: configurePreExtractedCache("/opt/aem/fulltext-store")
→ Updates DataStoreTextProviderService config

Step 8: Enable Pre-Extracted Cache Mode
JMX: enablePreExtractedCacheMode()
→ Sets alwaysUsePreExtractedCache=true in LuceneIndexProviderService

Step 9: Re-Index (Fast!)
[Use standard Oak re-indexing tools]
→ Re-indexing now takes ~5 hours instead of ~120 hours (95% reduction!)
```

---

## Technical Challenges

### 1. **Oak 1.22.x Compatibility**
- BlobStore API might differ from trunk
- Tika integration points may have changed
- Pre-extracted cache format may vary

**Mitigation:** Test on AEM 6.5.23 (Oak 1.22.22), use reflection if needed

### 2. **Memory Constraints**
- 100GB indexes = millions of binaries
- Can't hold all in memory
- Need streaming/batching

**Mitigation:** Process in batches of 1,000 binaries, lazy iteration

### 3. **Thread Safety**
- Background jobs must not interfere with AEM operations
- JMX operations must be thread-safe

**Mitigation:** Use `ThreadPoolExecutor`, synchronize shared state

### 4. **Error Handling**
- Corrupted binaries
- Disk space exhaustion
- Tika extraction timeouts

**Mitigation:** Try-catch all operations, log errors, continue processing

### 5. **OSGi Service Availability**
- ConfigurationAdmin might not be available in Oak 1.22.22
- DataStoreTextProviderService might not be registered

**Mitigation:** Make service references optional, graceful degradation

---

## Performance Expectations

### Phase 2: Fulltext Backup (startFulltextBackup)

| Repository Size | Binary Count | Extraction Time | Disk Space |
|----------------|--------------|-----------------|------------|
| 10 GB | 1,000 | ~30 minutes | ~2 GB |
| 50 GB | 10,000 | ~3 hours | ~10 GB |
| 100 GB | 50,000 | ~10 hours | ~25 GB |
| 500 GB | 200,000 | ~40 hours | ~100 GB |

**Factors:**
- CPU: Tika extraction is CPU-bound
- Disk I/O: DataStore read speed
- Mimetype: PDFs/Word docs slower than plaintext

**Optimization:**
- Skip videos (77.5% reduction in sample)
- Parallel extraction (thread pool)
- Resume capability (checkpoint progress)

### Phase 3: Populate Cache (populatePreExtractedCache)

| Store Size | File Count | Copy Time |
|-----------|-----------|-----------|
| 2 GB | 1,000 | ~5 minutes |
| 10 GB | 10,000 | ~20 minutes |
| 25 GB | 50,000 | ~1 hour |
| 100 GB | 200,000 | ~3 hours |

**Factors:**
- Disk I/O: Sequential reads/writes
- Network: If store is on NFS/remote disk

---

## Implementation Plan

### Milestone 1: Phase 1 (1 hour) ✅ EASY WIN
**Goal:** Add fulltext size tracking to existing MBean

**Deliverables:**
- `getFulltextStats(indexPath)` method
- Shows term counts, estimated size, binary counts

**Complexity:** LOW (extend existing code)

---

### Milestone 2: Phase 2 (1-2 days) 🔥 HIGH VALUE
**Goal:** Backup fulltext data to filesystem

**Deliverables:**
- `startFulltextBackup(storePath, indexPaths, includeVideos)`
- `getFulltextBackupProgress(jobId)`
- `cancelFulltextBackup(jobId)`
- Background job infrastructure
- BlobStore integration
- Tika extraction logic

**Complexity:** HIGH (async jobs, BlobStore API, error handling)

---

### Milestone 3: Phase 4 (4-6 hours) 🎯 MEDIUM VALUE
**Goal:** OSGi config manipulation

**Deliverables:**
- `configurePreExtractedCache(storePath)`
- `enablePreExtractedCacheMode()`
- `getPreExtractedCacheConfig()`
- ConfigurationAdmin integration

**Complexity:** MEDIUM (OSGi APIs)

---

### Milestone 4: Phase 5 (4-6 hours) 🎯 MEDIUM VALUE
**Goal:** Video mimetype handling

**Deliverables:**
- `getVideoMimetypes()`
- `generateEmptyParserConfig(outputPath, customMimetypes)`
- `applyTikaConfig(indexPath, tikaConfigPath)`
- JCR query execution
- Tika config XML generation

**Complexity:** MEDIUM (JCR query, XML, NodeStore writes)

---

### Milestone 5: Phase 3 (1-2 days) 🔥 HIGH COMPLEXITY
**Goal:** Populate pre-extracted cache

**Deliverables:**
- `populatePreExtractedCache(storePath, indexPath)`
- Oak cache format compatibility
- File copying logic
- Integrity verification

**Complexity:** HIGH (Oak cache internals, validation)

---

## Recommended Approach

### Option A: Implement Phases 1, 2, 4, 5 (Skip Phase 3)
**Rationale:**
- Phase 3 (populate cache) can be done via `oak-run tika --populate` offline
- Phases 1, 2, 4, 5 provide 80% of the value with 50% of the complexity

**Workflow:**
1. JMX: Backup fulltext to `/opt/aem/fulltext-store` (Phase 2)
2. JMX: Configure OSGi for pre-extracted cache (Phase 4)
3. JMX: Generate EmptyParser config (Phase 5)
4. **Offline:** Run `oak-run tika --populate` during maintenance window
5. Re-index (fast!)

**Effort:** 2-3 days

---

### Option B: Full Implementation (All Phases)
**Rationale:**
- Zero-downtime solution
- No offline maintenance windows
- Best-in-class for Apache Oak contribution

**Workflow:**
- All steps via JMX, no offline operations required

**Effort:** 4-5 days

---

### Option C: Just Phase 1 (Track Size Only)
**Rationale:**
- Quick win, low risk
- Provides visibility, customers do manual extraction

**Effort:** 1 hour

---

## My Recommendation

### **Start with Option A (Phases 1, 2, 4, 5)**

**Why:**
1. ✅ Provides 80% of value (backup + config)
2. ✅ Lower complexity (skip Phase 3 cache population)
3. ✅ Faster implementation (2-3 days vs 4-5 days)
4. ✅ Customers can use `oak-run tika --populate` for final step
5. ✅ Still best-in-class for Apache Oak

**Later:** If there's demand, add Phase 3 for zero-downtime solution.

---

## Next Steps

1. ✅ **You approve** the design (Option A, B, or C)
2. ✅ **I implement** Phase 1 (getFulltextStats) - 1 hour
3. ✅ **Test** on your AEM instance
4. ✅ **Iterate** on Phases 2, 4, 5 if approved

**Ready to proceed?**

