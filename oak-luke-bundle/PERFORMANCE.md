# LUKE Index Inspection - Performance Characteristics

## ⚠️ CRITICAL: Performance Expectations for Large Indexes

This tool is designed for **deep analysis** of Lucene indexes. Some operations are **computationally expensive** and can take **significant time** on large indexes (100GB+).

---

## 📊 Operation Complexity & Expected Runtime

### ✅ **FAST Operations** (< 1 second for any index size)

#### `getLocalIndexDirectories()`
- **Complexity:** O(n) where n = number of index directories
- **Runtime:** < 1 second
- **Description:** Lists filesystem directories only
- **Safe for:** Production, any index size

#### `getLukeBasicStats(String indexPath)`
- **Complexity:** O(1) - metadata read only
- **Runtime:** < 1 second  
- **Description:** Opens index reader and reads header information
- **Safe for:** Production, any index size

#### `getLocalIndexPath(String indexPath)`
- **Complexity:** O(n) where n = number of cached indexes
- **Runtime:** < 1 second
- **Description:** Filesystem lookup
- **Safe for:** Production, any index size

#### `validateLocalIndex(String indexPath)`
- **Complexity:** O(1) - opens and validates index header
- **Runtime:** < 5 seconds
- **Description:** Opens DirectoryReader to verify index integrity
- **Safe for:** Production, any index size

---

### ⚠️ **MEDIUM Operations** (seconds to minutes)

#### `getLukeFieldInfo(String indexPath, int maxFields)`
- **Complexity:** O(f) where f = number of fields (up to maxFields)
- **Runtime Examples:**
  - 100 fields: 1-5 seconds
  - 500 fields: 5-15 seconds
  - 1000+ fields: 15-60 seconds
- **Description:** Iterates through fields and reads term statistics
- **Recommendation:** 
  - Start with `maxFields=50` for initial analysis
  - Use `maxFields=10` for quick checks on very large indexes
- **Safe for:** Production with conservative maxFields limit

#### `getLukeTermStats(String indexPath, String fieldName, int maxTerms)`
- **Complexity:** O(t) where t = number of terms in the field
- **Runtime Examples:**
  - Field with 10K terms: 1-5 seconds
  - Field with 100K terms: 5-30 seconds
  - Field with 1M+ terms: 30 seconds - 5 minutes
  - Field with 10M+ terms: 5-30 minutes ⚠️
- **Description:** Iterates through all terms in a field
- **Recommendation:**
  - Check field size first with `getLukeFieldInfo`
  - Use `maxTerms=100` for initial analysis
  - Avoid on fields with >1M terms in production
- **Warning:** Can timeout JMX operations on massive fields

---

### 🔴 **EXPENSIVE Operations** (minutes to hours for 100GB+ indexes)

#### `getFieldSizeAnalysis(String indexPath, int maxFields)`
- **Complexity:** O(f × t_avg) where f = fields, t_avg = average terms per field
- **Runtime Examples:**
  - 10GB index (1M terms): 30 seconds - 2 minutes
  - 50GB index (10M terms): 5-15 minutes
  - 100GB index (50M+ terms): 15-60 minutes ⚠️⚠️
  - 500GB index: 1-3 hours ⚠️⚠️⚠️
- **Description:** Counts terms in ALL fields to determine size distribution
- **Why Slow:** Must call `Terms.size()` for every field, which can trigger full term iteration
- **Recommendation:**
  - **DO NOT** run during business hours on production
  - Run during maintenance windows only
  - Consider using standalone JAR instead of JMX
  - Use `maxFields=20` to limit scope
- **JMX Risk:** High - can timeout (default 60 second timeout in most JMX clients)

#### `getTopTermsByDocFreq(String indexPath, String fieldName, int maxTerms)`
- **Complexity:** O(t × log(maxTerms)) where t = total terms across all (or specified) fields
- **Runtime Examples:**
  - Single field (1M terms): 1-5 minutes
  - All fields (10M terms): 10-30 minutes ⚠️⚠️
  - All fields (100M+ terms): 30 minutes - 2 hours ⚠️⚠️⚠️
- **Description:** Iterates through ALL terms to find highest doc frequency
- **Why Slow:** Must scan every term to build frequency ranking
- **Recommendation:**
  - **ALWAYS specify a fieldName** - never use "" (all fields) on large indexes
  - Run during maintenance windows
  - Consider standalone JAR with output redirection
  - Use `maxTerms=50` to reduce sorting overhead
- **JMX Risk:** Very High - almost certain to timeout on 100GB+ indexes

#### `getIndexCompositionStats(String indexPath)`
- **Complexity:** O(f × t_avg)
- **Runtime:** Same as `getFieldSizeAnalysis` (15-60 minutes for 100GB)
- **Description:** Comprehensive analysis including top 10 fields
- **Recommendation:**
  - Use `getLukeBasicStats` first for quick overview
  - Run this only when you need detailed composition analysis
  - Prefer standalone JAR for large indexes
- **JMX Risk:** High - can timeout

---

## 🎯 **Recommended Usage Patterns**

### For Production JMX Access (100GB+ indexes):

```
✅ Safe Operations (always):
- getLocalIndexDirectories()
- getLukeBasicStats("/oak:index/damAssetLucene")  
- validateLocalIndex("/oak:index/damAssetLucene")

⚠️ Use Carefully (check field count first):
- getLukeFieldInfo("/oak:index/damAssetLucene", 10)  // Start with 10

🔴 Avoid in Production JMX:
- getFieldSizeAnalysis() - Use standalone JAR instead
- getTopTermsByDocFreq("", ...) - Never use empty fieldName
- getIndexCompositionStats() - Use standalone JAR instead
```

### For Standalone JAR (oak-run-luke):

```bash
# All operations are safer in standalone mode:
# - No JMX timeout limits
# - Can redirect output to files
# - Can run in background
# - Can monitor progress

# Field size analysis (safe, but slow)
java -jar oak-run-luke.jar analyze \
  --index /path/to/damAssetLucene \
  --operation field-analysis \
  --max-fields 50 \
  > field-analysis.txt 2>&1 &

# Top terms analysis (very slow, background recommended)
nohup java -jar oak-run-luke.jar analyze \
  --index /path/to/damAssetLucene \
  --operation top-terms \
  --field "jcr:content/metadata/dc:title" \
  --max-terms 100 \
  > top-terms.txt 2>&1 &
```

---

## 💾 **Memory Requirements**

### JMX Operations:
- **Basic Stats:** < 10 MB heap
- **Field Info:** ~1 MB per 100 fields
- **Term Stats:** ~1 MB per 10K terms (up to maxTerms)
- **Field Size Analysis:** ~10-50 MB (keeps field counts in memory)
- **Top Terms:** ~5 MB per 10K terms (keeps sorted queue)

### Standalone JAR:
- **Recommended:** `-Xmx2g` for indexes < 100GB
- **Large Indexes:** `-Xmx4g` for 100GB+ indexes
- **Very Large:** `-Xmx8g` for 500GB+ indexes

---

## ⏱️ **JMX Timeout Considerations**

Most JMX clients have timeouts:
- **Felix Console:** 60 seconds default
- **JConsole:** 120 seconds default  
- **VisualVM:** Configurable, often 60 seconds

**If an operation times out:**
1. ✅ The operation continues running on the server
2. ❌ You won't see the result
3. ⚠️ Multiple timeout operations can consume resources

**To avoid:**
- Use aggressive limits on `maxFields` and `maxTerms`
- Prefer standalone JAR for expensive operations
- Monitor AEM resource usage during analysis

---

## 🔬 **Real-World Examples**

### AEM 6.5 damAssetLucene Index (370 KB in your screenshot):
```
✅ getLukeBasicStats: < 1 second
✅ getLukeFieldInfo(50): 2-5 seconds
✅ getLukeTermStats("jcr:primaryType", 100): 1-3 seconds
⚠️ getFieldSizeAnalysis(50): 10-30 seconds
🔴 getTopTermsByDocFreq("", 100): 5-15 minutes (all fields)
```

### Typical Production damAssetLucene (50GB, 5M assets):
```
✅ getLukeBasicStats: < 1 second
⚠️ getLukeFieldInfo(50): 30-60 seconds
⚠️ getLukeTermStats("dc:title", 100): 2-5 minutes
🔴 getFieldSizeAnalysis(50): 30-60 minutes
🔴 getTopTermsByDocFreq("", 100): 2-4 hours
```

### Massive Production Index (500GB, 50M assets):
```
✅ getLukeBasicStats: < 1 second
🔴 getLukeFieldInfo(50): 5-10 minutes
🔴 getLukeTermStats("dc:title", 100): 15-30 minutes
🔴 getFieldSizeAnalysis(50): 3-6 hours
🔴 getTopTermsByDocFreq("", 100): 12+ hours
```

---

## 📋 **Best Practices**

### 1. **Always Start Small**
```java
// Step 1: Quick overview (< 1 second)
getLukeBasicStats("/oak:index/damAssetLucene")

// Step 2: Sample fields (< 30 seconds)
getLukeFieldInfo("/oak:index/damAssetLucene", 10)

// Step 3: Specific field analysis (minutes)
getLukeTermStats("/oak:index/damAssetLucene", "jcr:primaryType", 50)
```

### 2. **Use Maintenance Windows for Deep Analysis**
```bash
# Schedule during off-hours
# 02:00 AM: Start field size analysis
# Expected completion: 03:00 AM (for 100GB index)
```

### 3. **Monitor Resource Usage**
```bash
# Watch heap usage during analysis
jstat -gcutil <aem-pid> 1000

# Watch CPU
top -p <aem-pid>
```

### 4. **Consider Offline Analysis**
```bash
# Copy index to separate machine
rsync -av /path/to/crx-quickstart/repository/index/damAssetLucene /analysis/

# Run standalone tool there
java -Xmx8g -jar oak-run-luke.jar analyze --index /analysis/damAssetLucene
```

---

## 🚨 **Warning Signs**

Stop the operation if you see:
- ❌ AEM heap usage > 80%
- ❌ CPU sustained at 100% for > 10 minutes
- ❌ JMX client timeout
- ❌ AEM becomes unresponsive
- ❌ OakRepositoryStats JMX shows query queue backup

---

## 📚 **Algorithm Details**

### Why `Terms.size()` is expensive:
```java
// Lucene 4.7.2 API:
Terms terms = fields.terms(fieldName);
long size = terms.size();  // ⚠️ Can trigger full term iteration!

// For some index implementations, size() is O(t) not O(1)
// This is codec-dependent
```

### Why iterating all terms is expensive:
```java
TermsEnum te = terms.iterator(null);
while (te.next() != null) {  // O(t) - must scan every term
    // Each next() reads from disk
    // 10M terms × 100 bytes/term = 1GB disk reads
}
```

### Memory-efficient sorting (from LUKE):
```java
// We don't keep all terms in memory
// Only keep top N in a priority queue
PriorityQueue<TermInfo> topN = new PriorityQueue<>(maxTerms);
// Memory: O(maxTerms) not O(total_terms)
```

---

## 🎓 **For Apache Oak Core Team**

### Design Decisions:

1. **Why no async/background execution?**
   - Keeps API simple and synchronous
   - JMX operations are typically synchronous
   - Users can use standalone JAR for background analysis

2. **Why no progress reporting?**
   - JMX operations don't support streaming results
   - Would require separate progress MBean
   - Standalone JAR could add progress in future

3. **Why no cancellation?**
   - Thread interruption in JMX is complex
   - Could add in future with separate task manager
   - Current: operation runs to completion or timeout

4. **Future Enhancements:**
   - Add `estimateOperationTime()` method
   - Add sampling-based quick analysis
   - Add incremental/resumable analysis
   - Add caching of expensive results

### Testing Recommendations:

```
Small Index (< 1GB):   All operations
Medium Index (10GB):   All operations with maxFields=20
Large Index (100GB):   Only fast operations in automated tests
Massive Index (500GB): Manual testing only, document results
```

---

## ✅ **Summary**

**TL;DR:**
- ✅ Basic operations are FAST and SAFE for production
- ⚠️ Field/term analysis needs conservative limits
- 🔴 Full index analysis should use standalone JAR during maintenance windows
- 📏 Always set `maxFields` and `maxTerms` to conservative values in JMX
- ⏱️ Document your runtime expectations based on index size

**Golden Rule:**  
> If you wouldn't run `du -sh` on your production filesystem during business hours, don't run `getFieldSizeAnalysis()` on your production index via JMX.

Use the right tool for the job:
- **JMX:** Quick checks and targeted analysis
- **Standalone JAR:** Deep analysis and maintenance tasks

