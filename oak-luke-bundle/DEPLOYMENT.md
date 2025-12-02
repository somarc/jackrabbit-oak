# Oak LUKE Bundle - Deployment Guide for AEM 6.5.x

## Quick Start

### Step 1: Deploy to AEM

```bash
# Copy bundle to AEM
cp oak-luke-bundle/target/oak-luke-bundle-1.22.24-SNAPSHOT.jar \
   <AEM-INSTALL-DIR>/crx-quickstart/install/

# Or upload via Felix Console
# http://localhost:4502/system/console/bundles
```

### Step 2: Verify Installation

1. **Check Bundle Status:**
   ```
   http://localhost:4502/system/console/bundles
   ```
   
   Look for: **Oak LUKE OSGi Bundle (oak-luke-bundle)**  
   Status: **Active** ✅

2. **Check Component:**
   ```
   http://localhost:4502/system/console/components
   ```
   
   Find: **Apache Jackrabbit Oak LUKE Index Statistics**  
   Status: **satisfied** ✅

3. **Access JMX MBean:**
   ```
   http://localhost:4502/system/console/jmx
   ```
   
   Navigate to: `org.apache.jackrabbit.oak` → `LukeIndexStats`

### Step 3: Test Operations

In JMX Console, try:

```java
// List all local indexes
Operation: getLocalIndexDirectories()

// Inspect damAssetLucene
Operation: getLukeIndexStats
  indexPath: /oak:index/damAssetLucene

// Get field details
Operation: getLukeFieldInfo
  indexPath: /oak:index/damAssetLucene
  maxFields: 100
```

## Detailed Deployment Options

### Option A: Install Folder (Recommended)

```bash
cp oak-luke-bundle-1.22.24-SNAPSHOT.jar \
   <AEM>/crx-quickstart/install/
```

**Pros:**
- Automatic installation on AEM startup
- Persists across restarts
- No manual bundle management

**When to use:**
- Permanent installation
- Production environments
- Automated deployments

### Option B: Felix Console Upload

1. Navigate to: `http://localhost:4502/system/console/bundles`
2. Click **Install/Update**
3. Choose File: `oak-luke-bundle-1.22.24-SNAPSHOT.jar`
4. Check **Start Bundle**
5. Click **Install or Update**

**Pros:**
- Quick testing
- No filesystem access required
- Immediate activation

**When to use:**
- Development/testing
- Temporary installation
- Quick validation

### Option C: Package Manager

Create a content package containing the bundle:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jcr:root xmlns:jcr="http://www.jcp.org/jcr/1.0" xmlns:vlt="http://www.day.com/jcr/vault/1.0"
    jcr:primaryType="nt:file">
    <jcr:content
        jcr:primaryType="nt:resource"
        jcr:data="[binary]"/>
</jcr:root>
```

Package structure:
```
my-luke-package/
└── jcr_root/
    └── apps/
        └── myproject/
            └── install/
                └── oak-luke-bundle-1.22.24-SNAPSHOT.jar
```

**Pros:**
- Version controlled deployment
- Can be included in application packages
- Works with Cloud Manager

**When to use:**
- AEM as a Cloud Service (future)
- CI/CD pipelines
- Multi-instance deployments

## Verification Steps

### 1. Bundle Health Check

```bash
# Via curl
curl -u admin:admin http://localhost:4502/system/console/bundles/oak-luke-bundle.json | python -m json.tool

# Expected output:
{
  "status": "Bundle information: 123 bundles in total - all 123 bundles active.",
  "s": [123, 123, 0, 0],
  "data": [
    {
      "id": 123,
      "name": "Oak LUKE OSGi Bundle",
      "fragment": false,
      "stateRaw": 32,
      "state": "Active",
      "version": "1.22.24.SNAPSHOT",
      "symbolicName": "oak-luke-bundle"
    }
  ]
}
```

### 2. Service Reference Check

The bundle requires these services:

✅ **IndexTracker** (mandatory)
- Provided by: `org.apache.jackrabbit.oak-lucene`
- Check: Should be present in Oak/AEM by default

⚠️ **IndexCopier** (optional)
- Provided by: `org.apache.jackrabbit.oak-lucene`
- Check: May not be active if `enableCopyOnRead=false`
- Impact: MBean will still work, but may have limited functionality

### 3. JMX Registration Check

```bash
# List all Oak MBeans
curl -u admin:admin http://localhost:4502/system/console/jmx/org.apache.jackrabbit.oak

# Should include:
# - LukeIndexStats
```

## Usage Examples

### Example 1: Analyze damAssetLucene Index

```java
// JMX Console: http://localhost:4502/system/console/jmx
// Navigate to: org.apache.jackrabbit.oak:name=LukeIndexStats

// 1. Check if index is cached locally
Operation: getLocalIndexPath
Parameters:
  indexPath: /oak:index/damAssetLucene
Result:
  /path/to/crx-quickstart/repository/index/abc123_damAssetLucene

// 2. Get basic statistics
Operation: getLukeBasicStats
Parameters:
  indexPath: /oak:index/damAssetLucene
Result:
  Index: /oak:index/damAssetLucene
  Local Path: /..../repository/index/abc123_damAssetLucene
  Documents: 1,234,567
  Max Doc: 1,234,890
  Deletions: 323
  Fields: 245
  Size: 42.5 GB

// 3. Analyze fields
Operation: getLukeFieldInfo
Parameters:
  indexPath: /oak:index/damAssetLucene
  maxFields: 50
Result: [
  "Field: jcr:content/metadata/dc:title, Terms: 123456, Docs: 234567",
  "Field: jcr:content/metadata/dam:assetState, Terms: 5, Docs: 234567",
  ...
]

// 4. Analyze term distribution
Operation: getLukeTermStats
Parameters:
  indexPath: /oak:index/damAssetLucene
  fieldName: jcr:content/metadata/dam:assetState
  maxTerms: 10
Result: [
  "Term: processed, Freq: 234000, DocFreq: 234000",
  "Term: approved, Freq: 567, DocFreq: 567",
  ...
]
```

### Example 2: Validate Index Health

```java
Operation: validateLocalIndex
Parameters:
  indexPath: /oak:index/damAssetLucene
Result:
  OK: Valid Lucene index with 1234567 documents at /path/to/index
```

### Example 3: Monitor All Indexes

```java
Operation: getLocalIndexDirectories
Result: [TabularData showing all indexes with sizes and paths]
```

## Troubleshooting

### Bundle Not Starting

**Symptom:** Bundle shows "Installed" but not "Active"

**Solutions:**

1. Check dependencies:
   ```
   http://localhost:4502/system/console/bundles
   ```
   Ensure `oak-lucene` bundle is Active

2. Check logs:
   ```bash
   tail -f crx-quickstart/logs/error.log | grep -i luke
   ```

3. Verify OSGi config:
   ```
   http://localhost:4502/system/console/configMgr
   ```

### MBean Not Visible

**Symptom:** Bundle Active, but JMX MBean not appearing

**Solutions:**

1. Check component status:
   ```
   http://localhost:4502/system/console/components
   ```
   
   Component should be **satisfied**

2. Verify JMX registration in logs:
   ```
   Successfully registered LUKE Index Statistics MBean at: org.apache.jackrabbit.oak:name=LukeIndexStats,type=LukeIndexStats
   ```

3. Try restarting bundle:
   ```
   http://localhost:4502/system/console/bundles
   → Find oak-luke-bundle → Stop → Start
   ```

### IndexCopier Not Available Warning

**Symptom:** Log warnings about "IndexCopier not available"

**Impact:** 
- MBean will still function
- May have limited ability to locate index directories
- Some operations may return "Not found in local cache"

**Cause:**
- IndexCopier disabled: `enableCopyOnRead=false` in Lucene configuration
- Index not yet cached locally

**Solution:**
- Enable CopyOnRead in Lucene Index configuration
- Wait for indexes to be downloaded to local cache
- Or use alternative index access methods (already implemented)

### Empty Results

**Symptom:** `getLocalIndexDirectories()` returns no data

**Possible Causes:**

1. **Indexes not cached yet:**
   - Trigger query to force index download
   - Wait for IndexCopier to download indexes

2. **IndexCopier disabled:**
   - Check Lucene Index configuration
   - Verify `copyOnRead` is enabled

3. **Wrong index path:**
   - Use exact path from Oak Index Manager
   - Check `/oak:index` for available indexes

**Verify Index Exists:**
```bash
curl -u admin:admin http://localhost:4502/oak:index.tidy.json | grep -A 5 damAssetLucene
```

## Performance & Production Considerations

### Resource Usage

- **Memory:** Minimal (~10MB overhead)
- **CPU:** Read operations only, negligible impact
- **Disk I/O:** Reads from local cache, no writes
- **Network:** None (local access only)

### Safety

✅ **Production Safe:**
- All operations are read-only
- No index modifications
- No repository writes
- Uses existing IndexTracker readers
- Respects Oak's read locking

⚠️ **Considerations:**
- Large index inspection (100GB+) may take seconds
- Field/term enumeration on massive indexes can be CPU-intensive
- Limit `maxFields` and `maxTerms` parameters for responsiveness

### Recommended Settings

For production use:

```java
// Start with small limits
getLukeFieldInfo(indexPath, 50)   // Not 1000
getLukeTermStats(indexPath, field, 100)  // Not 10000

// For large indexes, use basic stats first
getLukeBasicStats(indexPath)  // Fast overview
```

## Integration with Existing Tools

### With Oak Index Manager

```
http://localhost:4502/libs/granite/operations/content/diagnosistools/indexManager.html
```

1. Use Index Manager to see index status
2. Use LUKE MBean to inspect index contents
3. Combine insights for complete picture

### With Query Performance Tool

```
http://localhost:4502/libs/granite/operations/content/diagnosistools/queryPerformance.html
```

1. Identify slow queries
2. Use LUKE to analyze relevant indexes
3. Optimize index definitions based on findings

### With oak-run Console

For offline analysis:

```bash
java -jar oak-run-1.22.24.jar console crx-quickstart/repository/segmentstore
```

LUKE bundle focuses on online/live inspection.

## Uninstallation

### Remove Bundle

```bash
rm <AEM>/crx-quickstart/install/oak-luke-bundle-1.22.24-SNAPSHOT.jar
```

Or via Felix Console:
```
http://localhost:4502/system/console/bundles
→ Find oak-luke-bundle → Uninstall
```

### Verify Removal

JMX MBean should disappear from:
```
http://localhost:4502/system/console/jmx
```

## Support & Resources

- **AEM Logs:** `crx-quickstart/logs/error.log`
- **Felix Console:** `http://localhost:4502/system/console`
- **Oak Documentation:** https://jackrabbit.apache.org/oak/
- **JMX Documentation:** Oak Index Statistics

## Version Compatibility

| AEM Version | Oak Version | Bundle Version | Status |
|-------------|-------------|----------------|--------|
| AEM 6.5.0-6.5.21 | Oak 1.22.x | 1.22.24-SNAPSHOT | ✅ Tested |
| AEM 6.4.x | Oak 1.8.x | Not tested | ⚠️ May work |
| AEM Cloud | Oak 1.42+ | TBD | ❌ Not compatible |

## Advanced Configuration

### Custom JMX Object Name

To change the JMX object name, modify `LukeIndexStatsService.java`:

```java
private static final String MBEAN_NAME = "com.mycompany:name=CustomLuke,type=LukeIndexStats";
```

### Multiple AEM Instances

For author/publish clusters:

- Deploy to all instances
- Each registers its own JMX MBean
- Inspect indexes on each node independently

### Log Level Configuration

Enable DEBUG logging:

```
http://localhost:4502/system/console/slinglog
```

Add logger configuration:
- **Logger:** `org.apache.jackrabbit.oak.plugins.index.lucene.luke`
- **Level:** DEBUG
- **File:** luke-index-stats.log

## Example: Complete Index Analysis Workflow

```bash
# 1. Check what indexes exist locally
getLocalIndexDirectories()
→ Returns list of all cached indexes

# 2. Pick an index to analyze
getLukeBasicStats("/oak:index/damAssetLucene")
→ Quick overview: 1.2M docs, 245 fields, 42GB

# 3. Analyze fields
getLukeFieldInfo("/oak:index/damAssetLucene", 100)
→ See all indexed fields

# 4. Focus on specific field
getLukeTermStats("/oak:index/damAssetLucene", "jcr:content/metadata/dc:title", 100)
→ See term distribution

# 5. Validate before reindex
validateLocalIndex("/oak:index/damAssetLucene")
→ Ensure index is healthy

# 6. Get filesystem path for manual inspection
getLocalIndexPath("/oak:index/damAssetLucene")
→ /path/to/repository/index/abc123_damAssetLucene
```

## FAQ

**Q: Can I inspect indexes while AEM is running?**  
A: Yes! All operations are read-only and safe for production.

**Q: Will this work with 100GB+ indexes?**  
A: Yes! It uses the local IndexCopier cache, no dumping needed.

**Q: Do I need to stop AEM to deploy?**  
A: No. Hot deployment is supported.

**Q: Can I use this on AEM Cloud?**  
A: Not yet. AEM Cloud runs newer Oak versions. Stay tuned.

**Q: What if IndexCopier is disabled?**  
A: MBean will still work using alternative index access methods.

**Q: Is this safe for production?**  
A: Yes. All operations are read-only with minimal performance impact.

## Next Steps

1. ✅ Deploy bundle to AEM dev instance
2. ✅ Verify JMX MBean registration
3. ✅ Test on a small index first
4. ✅ Analyze your large damAssetLucene indexes
5. 🚀 Use insights to optimize query performance!

## Support

For issues or questions:
- Check `error.log` for detailed error messages
- Verify bundle and component status in Felix Console
- Review JMX Console for MBean registration
- Check IndexCopier and IndexTracker service availability

