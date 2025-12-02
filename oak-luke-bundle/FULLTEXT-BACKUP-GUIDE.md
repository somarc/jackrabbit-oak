# Fulltext Backup & Pre-Extracted Cache - Complete Guide

**Last Updated**: 2025-12-02  
**Target**: AEM 6.5.x (Oak 1.22.x) on-prem  
**Use Case**: Accelerate re-indexing by 100x (3-4 days → 3-4 hours)

---

## Table of Contents

1. [Overview](#overview)
2. [How Pre-Extracted Cache Works](#how-pre-extracted-cache-works)
3. [Phase 1: Assess Fulltext Size](#phase-1-assess-fulltext-size)
4. [Phase 2: Backup Fulltext Data](#phase-2-backup-fulltext-data)
5. [Phase 3: Configure Pre-Extracted Cache](#phase-3-configure-pre-extracted-cache)
6. [Phase 4: Re-Indexing](#phase-4-re-indexing)
7. [Index Corruption Recovery](#index-corruption-recovery)
8. [Troubleshooting](#troubleshooting)

---

## Overview

### The Problem

When re-indexing large AEM repositories:
- **Node traversal**: 3-4 hours (250M nodes) ← Unavoidable
- **Binary extraction**: 3-4 **DAYS** (Tika extraction) ← Can be eliminated!
- **Total**: ~4 days downtime

### The Solution

**Pre-extracted cache** stores already-extracted fulltext, eliminating binary extraction:
- **Node traversal**: 3-4 hours (unchanged)
- **Binary extraction**: ~0 seconds (using cache!)
- **Total**: 3-4 hours (100x faster!)

### Prerequisites

- AEM 6.5.x (Oak 1.22.x)
- oak-luke-bundle v11+ deployed
- Sufficient disk space (~1.2x fulltext size)
- JMX console access

---

## How Pre-Extracted Cache Works

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    AEM Re-Indexing Flow                     │
└─────────────────────────────────────────────────────────────┘

WITHOUT Pre-Extracted Cache:
───────────────────────────────────────────────────────────────
1. Traverse repository (3-4 hours)
   └─> For each binary:
       ├─> Read from DataStore (slow!)
       ├─> Extract text with Tika (CPU intensive!)
       └─> Index fulltext
   TOTAL: 3-4 DAYS

WITH Pre-Extracted Cache:
───────────────────────────────────────────────────────────────
1. Traverse repository (3-4 hours)
   └─> For each binary:
       ├─> Check cache: /opt/aem/fulltext-store/<blobId>.txt
       ├─> If found: Read text from cache (instant!)
       ├─> If not found: Extract with Tika (fallback)
       └─> Index fulltext
   TOTAL: 3-4 HOURS (100x faster!)
```

### Key Components

#### 1. DataStoreTextProviderService

**OSGi PID**: `org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreTextProviderService`

**Purpose**: Provides extracted text from a filesystem cache instead of extracting from binaries

**Configuration**:
```
dir = /opt/aem/fulltext-store
```

**What it does**:
- When Oak needs fulltext for blob ID `abc123def456`
- Looks for `/opt/aem/fulltext-store/abc123def456.txt`
- If found: Returns cached text (instant)
- If not found: Returns null (Oak extracts with Tika)

**File Format**:
```
/opt/aem/fulltext-store/
├── abc123def456.txt       # Plain text, UTF-8
├── 789ghi012jkl.txt
├── def456abc123.txt
└── manifest.csv           # Optional: blobId, size, path
```

---

#### 2. LuceneIndexProviderService

**OSGi PID**: `org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexProviderService`

**Purpose**: Controls Lucene indexing behavior, including pre-extracted cache usage

**Key Property**:
```
alwaysUsePreExtractedCache = true
```

**What it does**:
- **`false` (default)**: Try cache first, fall back to Tika extraction
- **`true` (recommended)**: ONLY use cache, skip Tika extraction entirely
  - If text not in cache: Skip fulltext indexing for that binary
  - Use this when you're confident cache is complete

**Why `alwaysUsePreExtractedCache=true`?**
- **Faster**: No fallback to Tika (no extraction overhead)
- **Predictable**: Re-indexing time is consistent
- **Safe**: If cache is complete, nothing is missed
- **Faster failure**: If cache is incomplete, you know immediately

---

### Data Flow During Re-Indexing

```
┌─────────────────────────────────────────────────────────────┐
│         Re-Indexing with Pre-Extracted Cache                │
└─────────────────────────────────────────────────────────────┘

1. Oak async indexer traverses repository
   └─> Finds node: /content/dam/image.jpg

2. Node has binary property: jcr:data (blob ID: abc123def456)

3. Index definition requires fulltext extraction

4. LuceneIndexProviderService asks DataStoreTextProviderService:
   "Do you have text for blob abc123def456?"

5. DataStoreTextProviderService checks:
   /opt/aem/fulltext-store/abc123def456.txt
   
   ┌─ Found? ──────────────────────────────────────┐
   │ YES:                                          │
   │   └─> Read text from file                    │
   │   └─> Return to indexer                      │
   │   └─> Index fulltext (instant!)              │
   │                                               │
   │ NO (and alwaysUsePreExtractedCache=false):   │
   │   └─> Fall back to Tika extraction           │
   │   └─> Extract from binary (slow!)            │
   │   └─> Index fulltext                         │
   │                                               │
   │ NO (and alwaysUsePreExtractedCache=true):    │
   │   └─> Skip fulltext for this binary          │
   │   └─> Continue to next node                  │
   └───────────────────────────────────────────────┘

6. Repeat for all nodes with binaries

7. Re-indexing completes in 3-4 hours (vs. 3-4 days!)
```

---

## Phase 1: Assess Fulltext Size

**Goal**: Determine if fulltext backup is worthwhile

### Step 1.1: Run Fulltext Stats

**JMX Console**: http://localhost:4502/system/console/jmx

**Navigate to**:
```
org.apache.jackrabbit.oak
└─> LukeIndexStats
    └─> getFulltextStats(indexPath)
```

**Parameters**:
```
indexPath: /oak:index/damAssetLucene
```

**Example Output**:
```
═══════════════════════════════════════════════════════════════
FULLTEXT INDEX STATISTICS
═══════════════════════════════════════════════════════════════
Index: /oak:index/damAssetLucene
Location: .../index/damAssetLucene-1747319650363

FULLTEXT FIELD ANALYSIS:
  Field: :fulltext
  Total Terms: 1,234,567
  Estimated Stored Text Size: 123.5 MB

INDEX SIZE:
  Total Index Size on Disk: 152.3 MB

BACKUP ESTIMATE:
  Time to backup: ~4 minutes (read from Lucene index)
  Disk space needed: ~148.2 MB (stored text + overhead)

RECOMMENDATION:
  ✅ Backup RECOMMENDED - will save 100x time during re-indexing
  ⚠️  Large fulltext index detected. Pre-extracted cache will
     significantly accelerate re-indexing.
═══════════════════════════════════════════════════════════════
```

### Step 1.2: Decision

**If recommendation is ✅ RECOMMENDED:**
- Proceed to Phase 2 (backup)
- Typical thresholds:
  - 100K+ terms: Worth it
  - 1M+ terms: Critical for performance
  - 10M+ terms: Mandatory for reasonable re-index times

**If recommendation is ℹ️ Small index:**
- Backup may not be necessary
- Re-indexing already fast enough without cache

---

## Phase 2: Backup Fulltext Data

**Goal**: Copy already-extracted fulltext from Lucene index to filesystem

### Step 2.1: Prepare Storage

Create backup directory on AEM server:

```bash
# Create directory (must be writable by AEM process)
mkdir -p /opt/aem/fulltext-store
chown aem:aem /opt/aem/fulltext-store
chmod 755 /opt/aem/fulltext-store

# Verify disk space (need ~1.2x fulltext size)
df -h /opt/aem
```

### Step 2.2: Start Backup Job

**JMX Console**: http://localhost:4502/system/console/jmx

**Navigate to**:
```
org.apache.jackrabbit.oak
└─> LukeIndexStats
    └─> startFulltextBackup(storePath, indexPath)
```

**Parameters**:
```
storePath: /opt/aem/fulltext-store
indexPath: /oak:index/damAssetLucene
```

**Returns**:
```
Job ID: backup-1701532800000
Started background fulltext backup
Monitor progress with: getFulltextBackupProgress("backup-1701532800000")
```

### Step 2.3: Monitor Progress

**JMX Operation**: `getFulltextBackupProgress(jobId)`

**Parameters**:
```
jobId: backup-1701532800000
```

**Example Output**:
```
═══════════════════════════════════════════════════════════════
FULLTEXT BACKUP PROGRESS
═══════════════════════════════════════════════════════════════
Job ID: backup-1701532800000
Status: RUNNING

Progress:
  Documents Processed: 125,000 / 250,000 (50.0%)
  Binaries Extracted: 85,000
  Files Written: 85,000
  Skipped (no fulltext): 40,000
  
Timing:
  Started: 2025-12-02 13:00:00
  Elapsed: 12 minutes
  Estimated Remaining: 12 minutes
  
Output:
  Store Path: /opt/aem/fulltext-store
  Files Created: 85,000
  Total Size: 42.3 GB
  
Rate:
  ~10,000 docs/minute
  ~3.5 GB/minute
═══════════════════════════════════════════════════════════════
```

**Check periodically** (every 5-10 minutes) until status shows `COMPLETED`.

### Step 2.4: Verify Backup

**Check filesystem**:
```bash
# Count files
ls -1 /opt/aem/fulltext-store/*.txt | wc -l
# Should match "Files Created" count

# Check total size
du -sh /opt/aem/fulltext-store
# Should match "Total Size"

# Verify manifest
cat /opt/aem/fulltext-store/manifest.csv | head -10
# Format: blobId,size,path
# Example: abc123def456,15234,/content/dam/assets/image.jpg/jcr:content/renditions/original/jcr:content
```

### Step 2.5: Completion

**When complete, JMX shows**:
```
Status: COMPLETED
Documents Processed: 250,000 / 250,000 (100.0%)
Files Written: 125,000
Skipped: 125,000 (no fulltext or non-binary nodes)
Total Size: 85.6 GB
Duration: 24 minutes
```

**Files created**:
```
/opt/aem/fulltext-store/
├── abc123def456.txt       (85,000+ files)
├── 789ghi012jkl.txt
├── ...
├── manifest.csv           (metadata)
└── backup-status.json     (job details)
```

---

## Phase 3: Configure Pre-Extracted Cache

**Goal**: Point Oak to the fulltext backup for fast re-indexing

### Step 3.1: Configure DataStoreTextProviderService

**Felix Console**: http://localhost:4502/system/console/configMgr

**Find Configuration**:
```
org.apache.jackrabbit.oak.plugins.blob.datastore.DataStoreTextProviderService
```

**Settings**:
```
dir = /opt/aem/fulltext-store
```

**Click**: Save

**Verify** (check logs):
```bash
tail -f crx-quickstart/logs/error.log | grep DataStoreTextProvider
```

Expected:
```
INFO  DataStoreTextProviderService - Configured pre-extracted text directory: /opt/aem/fulltext-store
INFO  DataStoreTextProviderService - Found 125,000 pre-extracted text files
```

---

### Step 3.2: Configure LuceneIndexProviderService

**Felix Console**: http://localhost:4502/system/console/configMgr

**Find Configuration**:
```
org.apache.jackrabbit.oak.plugins.index.lucene.LuceneIndexProviderService
```

**Settings**:
```
alwaysUsePreExtractedCache = true   (checkbox: checked)
```

**What this means**:
- ✅ **Checked (true)**: Use ONLY pre-extracted cache (no Tika fallback)
  - **Pro**: Fastest re-indexing, predictable time
  - **Con**: If cache incomplete, some binaries won't have fulltext
  - **Use when**: Cache is complete and verified

- ❌ **Unchecked (false)**: Try cache first, fall back to Tika
  - **Pro**: No binaries missed (Tika extracts if cache missing)
  - **Con**: Slower (some Tika extractions), unpredictable time
  - **Use when**: Cache may be incomplete, safety over speed

**Recommendation**: Use **`true`** if cache is complete (verified in Step 2.5)

**Click**: Save

---

### Step 3.3: Verify Configuration

**JMX Console**: http://localhost:4502/system/console/jmx

**Check**:
```
org.apache.jackrabbit.oak
└─> LuceneIndexStats
    └─> getPreExtractedCacheConfig()   (if implemented)
```

**OR check logs**:
```bash
tail -f crx-quickstart/logs/error.log | grep -E "(DataStoreTextProvider|LuceneIndexProvider)"
```

Expected:
```
INFO  DataStoreTextProviderService - Pre-extracted cache enabled: /opt/aem/fulltext-store
INFO  LuceneIndexProviderService - alwaysUsePreExtractedCache: true
```

---

## Phase 4: Re-Indexing

**Goal**: Trigger re-index and observe 100x speedup

### Step 4.1: Trigger Re-Index

**Option A: Via JMX** (Recommended)
```
org.apache.jackrabbit.oak
└─> IndexStats
    └─> damAssetLucene
        └─> abortAndReindex()
```

**Option B: Via async mbean**
```
org.apache.jackrabbit.oak
└─> SegmentNodeStore
    └─> Repository Maintenance
        └─> startReindex()
```

**Option C: Via CRXDE Lite** (Manual)
```
1. Navigate to: /oak:index/damAssetLucene
2. Set property: reindex = true (Boolean)
3. Set property: reindexCount = <increment by 1>
4. Click: Save All
```

### Step 4.2: Monitor Re-Indexing

**async-index-update.log**:
```bash
tail -f crx-quickstart/logs/async-index-update.log
```

**Look for**:
```
INFO  AsyncIndexUpdate - Starting reindex for damAssetLucene
INFO  LuceneIndexEditor - Using pre-extracted text cache: /opt/aem/fulltext-store
INFO  LuceneIndexEditor - Indexed 10,000 nodes (cache hits: 8,500, misses: 1,500)
...
INFO  AsyncIndexUpdate - Reindexing completed for damAssetLucene in 3h 45m
```

**JMX Monitoring**:
```
org.apache.jackrabbit.oak
└─> IndexStats
    └─> damAssetLucene
        └─> status: "running"
        └─> estimatedCompletion: "2025-12-02 17:00:00"
```

### Step 4.3: Verify Results

**Check index status**:
```
org.apache.jackrabbit.oak
└─> IndexStats
    └─> damAssetLucene
        └─> status: "done"
        └─> lastIndexedTime: "2025-12-02 16:45:00"
```

**Query test**:
```
http://localhost:4502/bin/querybuilder.json?type=dam:Asset&path=/content/dam&fulltext=test&p.limit=10
```

**Performance comparison**:
| Metric | Without Cache | With Cache | Improvement |
|--------|--------------|------------|-------------|
| Node traversal | 3-4 hours | 3-4 hours | Same |
| Binary extraction | 3-4 **DAYS** | ~0 seconds | **100x** |
| **Total time** | **3-4 days** | **3-4 hours** | **100x faster!** |

---

## Index Corruption Recovery

**Scenario**: Index lane has been corrupted or stale for a very long time

### The Problem

When an async index lane (e.g., `async`) has been failing for weeks/months:
- Checkpoint is very old
- Oak tries to "catch up" by processing millions of changes
- This can take days or weeks
- May cause OOM errors or performance issues

### The Solution: Future Checkpoint

**Concept**: Create a checkpoint in the future to "pretend everything is fine"

**Effect**:
- Index lane starts from future checkpoint
- Skips trying to catch up with old changes
- Begins re-indexing cleanly from current state

---

### Step 1: Create Future Checkpoint

**JMX Console**: http://localhost:4502/system/console/jmx

**Navigate to**:
```
org.apache.jackrabbit.oak
└─> SegmentNodeStore
    └─> CheckpointManager
        └─> createCheckpoint(long lifetime)
```

**Parameters**:
```
lifetime: 8640000000   (100 days in milliseconds)
```

**Returns**:
```
Checkpoint ID: r16d2f8e7f4e-0-1
```

**Copy this checkpoint ID** - you'll need it in Step 2!

---

### Step 2: Update async Index Lane Checkpoint

**CRXDE Lite**: http://localhost:4502/crx/de/index.jsp

**Navigate to**:
```
/oak:index/:async
```

**Properties to update**:
| Property | Old Value | New Value |
|----------|-----------|-----------|
| `async` | `async` | `async` (unchanged) |
| `checkpoint` | `r15a1b2c3d4e-0-1` (old) | `r16d2f8e7f4e-0-1` (new, from Step 1) |
| `reindex` | `false` | `false` (unchanged) |

**Steps**:
1. Click on `/oak:index/:async` node
2. Double-click `checkpoint` property value
3. Replace with new checkpoint ID from Step 1
4. Click: Save All

**Verification**:
```bash
# Check async index log
tail -f crx-quickstart/logs/async-index-update.log
```

Expected:
```
INFO  AsyncIndexUpdate - Async index lane 'async' using checkpoint: r16d2f8e7f4e-0-1
INFO  AsyncIndexUpdate - Starting indexing from checkpoint (future)
INFO  AsyncIndexUpdate - No backlog detected, resuming normal operation
```

---

### Step 3: Trigger Re-Index (If Needed)

If index is corrupted (not just stale), also trigger re-index:

**CRXDE Lite**:
```
/oak:index/damAssetLucene
├─> reindex: true
└─> reindexCount: <increment by 1>
```

**Save All**

---

### When to Use This Technique

**Use future checkpoint when**:
- ✅ Index lane has been failing for weeks/months
- ✅ Checkpoint is very old (>30 days)
- ✅ Oak tries to process millions of backlog changes
- ✅ Re-indexing would be faster than catching up
- ✅ You're confident you want to skip old changes

**Don't use if**:
- ❌ Index lane is only slightly behind (< 1 day)
- ❌ You need to preserve all changes (can't skip)
- ❌ Index is already healthy

**Risk Assessment**:
- **Low risk**: If you're doing a full re-index anyway (all content will be indexed)
- **Medium risk**: If you're only updating checkpoint (some changes may be missed)
- **Mitigation**: Always do full re-index after setting future checkpoint

---

## Troubleshooting

### Issue: Backup Job Hangs

**Symptoms**:
```
getFulltextBackupProgress() shows same percentage for 30+ minutes
```

**Causes**:
- Large document taking long time to process
- Disk I/O bottleneck
- JMX timeout

**Solutions**:
1. Check logs for errors:
   ```bash
   tail -f crx-quickstart/logs/error.log | grep FulltextBackup
   ```

2. Check disk I/O:
   ```bash
   iostat -x 5
   ```

3. Cancel and restart:
   ```
   JMX: cancelFulltextBackup(jobId)
   JMX: startFulltextBackup(...) with resume=true
   ```

---

### Issue: Cache Not Being Used

**Symptoms**:
```
async-index-update.log shows Tika extraction happening
```

**Verification**:
```bash
grep -E "(Tika|extraction)" crx-quickstart/logs/async-index-update.log
```

If you see:
```
INFO  TikaParser - Extracting text from blob: abc123def456
```

**Causes**:
1. DataStoreTextProviderService not configured
2. Path to cache directory wrong
3. Files don't match blob IDs

**Solutions**:

**1. Verify service is active**:
```
Felix Console → Components → DataStoreTextProviderService
Status should be: Active
```

**2. Check configuration**:
```
Felix Console → Configuration → DataStoreTextProviderService
dir: /opt/aem/fulltext-store   (verify path is correct)
```

**3. Check file exists**:
```bash
# Get blob ID from log
BLOB_ID="abc123def456"

# Check if file exists
ls -lh /opt/aem/fulltext-store/${BLOB_ID}.txt

# If not found, check manifest
grep ${BLOB_ID} /opt/aem/fulltext-store/manifest.csv
```

---

### Issue: Re-Index Still Slow

**Symptoms**:
```
Re-indexing taking 3-4 days despite pre-extracted cache
```

**Diagnosis**:

**Check cache hit rate**:
```bash
grep "cache hits" crx-quickstart/logs/async-index-update.log
```

**If cache hit rate < 80%**:
- Cache is incomplete
- Many binaries missing from cache
- Oak falling back to Tika extraction

**Solutions**:

**1. Verify backup completed**:
```
JMX: getFulltextBackupProgress(jobId)
Status should be: COMPLETED
Files Written should be: > 0
```

**2. Check alwaysUsePreExtractedCache setting**:
```
Felix Console → Configuration → LuceneIndexProviderService
alwaysUsePreExtractedCache: false (cache + Tika fallback)
                           vs
alwaysUsePreExtractedCache: true (cache only, no Tika)
```

**If false**: Oak is falling back to Tika (slower)
**If true**: Oak skips missing binaries (faster but incomplete)

**3. Re-run backup**:
```
JMX: startFulltextBackup() with different indexPath
      (may have backed up wrong index)
```

---

### Issue: Out of Disk Space

**Symptoms**:
```
IOException: No space left on device
```

**During backup**:

**1. Check disk space**:
```bash
df -h /opt/aem
```

**2. Estimate needed space**:
```
JMX: getFulltextStats(indexPath)
→ "Disk space needed: ~148.2 MB"
```

**3. Free up space or use different directory**:
```bash
# Use larger volume
mkdir -p /data/aem/fulltext-store
chown aem:aem /data/aem/fulltext-store

# Update JMX parameter
storePath: /data/aem/fulltext-store
```

---

### Issue: Cannot Access JMX

**Symptoms**:
```
Connection refused: localhost:8686
```

**Solutions**:

**1. Check JMX is enabled**:
```bash
ps aux | grep java | grep -E "(jmxremote|com.sun.management)"
```

**2. AEM JMX console**:
```
Use HTTP-based JMX console instead:
http://localhost:4502/system/console/jmx
```

**3. Enable JMX**:
```bash
# Add to start script
CQ_JVM_OPTS="$CQ_JVM_OPTS \
  -Dcom.sun.management.jmxremote \
  -Dcom.sun.management.jmxremote.port=8686 \
  -Dcom.sun.management.jmxremote.authenticate=false \
  -Dcom.sun.management.jmxremote.ssl=false"
```

---

## Performance Tips

### 1. Parallel Backup (Future Enhancement)

Currently backup is single-threaded. For very large indexes:

**Option**: Run multiple backup jobs for different indexes simultaneously
```
Terminal 1: JMX → startFulltextBackup(..., "/oak:index/damAssetLucene")
Terminal 2: JMX → startFulltextBackup(..., "/oak:index/lucene")
Terminal 3: JMX → startFulltextBackup(..., "/oak:index/cqPageLucene")
```

**Risk**: May impact live AEM performance (CPU/disk I/O)

---

### 2. Incremental Backup (Future Enhancement)

For indexes that change frequently:

**Pattern**:
```
1. Full backup: startFulltextBackup() → 10 hours
2. Wait 1 month
3. Incremental: startFulltextBackup(resume=true, since=lastBackupDate)
   → Only backs up new/changed binaries → 30 minutes
```

**Not yet implemented** - use full backup for now

---

### 3. Compression

For long-term storage:

```bash
# Compress backup (saves ~70% space)
cd /opt/aem
tar -czf fulltext-store-2025-12-02.tar.gz fulltext-store/

# Restore when needed
tar -xzf fulltext-store-2025-12-02.tar.gz
```

---

## Appendix: File Formats

### manifest.csv

**Format**: CSV with headers
```csv
blobId,size,path
abc123def456,15234,/content/dam/assets/image.jpg/jcr:content/renditions/original/jcr:content
789ghi012jkl,8921,/content/dam/documents/file.pdf/jcr:content
...
```

**Columns**:
- `blobId`: Oak blob identifier
- `size`: Text size in bytes
- `path`: JCR path where binary is referenced

---

### backup-status.json

**Format**: JSON
```json
{
  "jobId": "backup-1701532800000",
  "status": "COMPLETED",
  "startTime": "2025-12-02T13:00:00Z",
  "endTime": "2025-12-02T13:24:00Z",
  "duration": "24 minutes",
  "statistics": {
    "documentsProcessed": 250000,
    "binariesExtracted": 125000,
    "filesWritten": 125000,
    "skipped": 125000,
    "totalSize": 85600000000,
    "errors": 0
  },
  "configuration": {
    "storePath": "/opt/aem/fulltext-store",
    "indexPath": "/oak:index/damAssetLucene"
  }
}
```

---

## References

- **Oak Pre-Extracted Text**: https://jackrabbit.apache.org/oak/docs/query/pre-extract-text.html
- **oak-run tika command**: `oak-run/README.md`
- **ADR-0006**: Fulltext Backup Ultra-Simplified Approach
- **FULLTEXT-BACKUP-DESIGN.md**: Technical design document

---

**Questions? Issues?** Check [TROUBLESHOOTING](#troubleshooting) or open a GitHub issue.

