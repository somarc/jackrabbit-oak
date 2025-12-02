# LUKE Integration for Apache Jackrabbit Oak

Complete integration of LUKE (Lucene Index Toolbox) functionality into Apache Jackrabbit Oak, providing deep index inspection capabilities for AEM environments.

## 🎯 What Was Built

### Two Complementary Artifacts

#### 1. **oak-luke-bundle** (OSGi Bundle for AEM) - **RECOMMENDED**
- **Purpose:** Deploy directly to AEM 6.5.x
- **Size:** 21 KB
- **Type:** OSGi bundle with Felix SCR
- **Usage:** JMX MBean for live index inspection
- **Target:** Production AEM instances

#### 2. **oak-run-luke** (Standalone CLI Tool)
- **Purpose:** Offline index analysis and utilities
- **Size:** 38 MB  
- **Type:** Runnable JAR
- **Usage:** Command-line interface
- **Target:** Development and troubleshooting

## 🚀 Quick Start for AEM 6.5.x

### Deploy to AEM

```bash
# 1. Copy bundle to AEM
cp oak-luke-bundle/target/oak-luke-bundle-1.22.24-SNAPSHOT.jar \
   <AEM>/crx-quickstart/install/

# 2. Verify in Felix Console
open http://localhost:4502/system/console/bundles

# 3. Access JMX Console
open http://localhost:4502/system/console/jmx
```

### First Test

In JMX Console (`org.apache.jackrabbit.oak:name=LukeIndexStats`):

```java
// List all local indexes
getLocalIndexDirectories()

// Inspect damAssetLucene
getLukeBasicStats("/oak:index/damAssetLucene")

// Analyze fields
getLukeFieldInfo("/oak:index/damAssetLucene", 50)
```

## 📁 Project Structure

```
jackrabbit-oak/
├── oak-luke-bundle/                    # OSGi bundle for AEM
│   ├── pom.xml                         # Bundle packaging, Felix SCR
│   ├── README.md                       # Bundle documentation
│   ├── DEPLOYMENT.md                   # Detailed deployment guide
│   └── src/main/java/
│       └── org/apache/jackrabbit/oak/plugins/index/lucene/luke/
│           ├── LukeIndexStatsMBean.java              # JMX interface
│           ├── LukeIndexStatsMBeanImpl.java          # Implementation
│           └── osgi/
│               ├── LukeBundleActivator.java          # Bundle lifecycle
│               └── LukeIndexStatsService.java        # OSGi component
│
└── oak-run-luke/                       # Standalone CLI tool
    ├── pom.xml                         # Runnable JAR packaging
    ├── README.md                       # Tool documentation
    └── src/main/java/
        ├── org/apache/jackrabbit/oak/plugins/index/lucene/luke/
        │   ├── LukeIndexStatsMBean.java              # Same MBean interface
        │   └── LukeIndexStatsMBeanImpl.java          # Same implementation
        └── org/apache/jackrabbit/oak/run/luke/
            ├── Main.java                              # CLI entry point
            ├── AvailableLukeModes.java                # Command registry
            └── *Command.java                          # Individual commands
```

## 🎯 Key Features

### What Makes This Different

✅ **No Index Dumping** - Works with existing `IndexCopier` local cache  
✅ **100GB+ Index Support** - Handles massive AEM indexes without export  
✅ **Production Safe** - All operations read-only  
✅ **Real-Time Analysis** - Inspect live repository indexes  
✅ **JMX Native** - Natural extension of Oak's management tools  
✅ **AEM 6.5.x Compatible** - Built on Oak 1.22.24  

### JMX MBean Operations

| Operation | Description | Parameters |
|-----------|-------------|------------|
| `getLocalIndexDirectories()` | List all cached indexes | None |
| `getLukeIndexStats(indexPath)` | Get detailed index statistics | indexPath |
| `getLukeFieldInfo(indexPath, maxFields)` | Analyze indexed fields | indexPath, maxFields |
| `getLukeTermStats(indexPath, field, maxTerms)` | Analyze term distribution | indexPath, fieldName, maxTerms |
| `getLukeBasicStats(indexPath)` | Quick index overview | indexPath |
| `validateLocalIndex(indexPath)` | Verify index integrity | indexPath |
| `getLocalIndexPath(indexPath)` | Get filesystem path | indexPath |
| `getIndexCacheStatus()` | Overview of cached indexes | None |

## 💡 Use Cases

### 1. Debug Query Performance Issues

**Scenario:** Slow DAM asset queries

```java
// 1. Check what's indexed
getLukeFieldInfo("/oak:index/damAssetLucene", 100)

// 2. Analyze problematic field
getLukeTermStats("/oak:index/damAssetLucene", "jcr:content/metadata/dc:title", 100)

// 3. Look for:
//    - Too many unique terms (high cardinality)
//    - Uneven term distribution
//    - Missing expected fields
```

### 2. Validate Reindexing Results

**Scenario:** After reindexing damAssetLucene

```java
// Before reindex
String oldStats = getLukeBasicStats("/oak:index/damAssetLucene");
// Documents: 1,234,567

// After reindex
String newStats = getLukeBasicStats("/oak:index/damAssetLucene");
// Documents: 1,234,890 ✅ Increased

// Validate
validateLocalIndex("/oak:index/damAssetLucene")
// OK: Valid Lucene index
```

### 3. Plan Index Definition Changes

**Scenario:** Optimizing custom index

```java
// Analyze current index structure
getLukeIndexStats("/oak:index/myCustomIndex")
// Note: 500 fields, 50GB size

// Identify unused fields
String[] fields = getLukeFieldInfo("/oak:index/myCustomIndex", 500);
// Review: Many fields with 0-1 documents

// Plan: Remove unused fields from index definition
```

### 4. Troubleshoot Missing Search Results

**Scenario:** Assets not appearing in search

```java
// Check if field is indexed
getLukeFieldInfo("/oak:index/damAssetLucene", 1000)
// Search for expected field name

// Check term exists
getLukeTermStats("/oak:index/damAssetLucene", "jcr:content/metadata/dam:assetState", 10)
// Verify expected term values are present
```

## 🏗️ Architecture

### How It Works

```
AEM Instance (Oak 1.22)
  │
  ├─→ IndexTracker (Oak service)
  │     └─→ Manages active index readers
  │
  ├─→ IndexCopier (Oak service)  
  │     └─→ Local cache: crx-quickstart/repository/index/
  │           ├── abc123_damAssetLucene/
  │           ├── def456_cqPageLucene/
  │           └── ...
  │
  └─→ oak-luke-bundle (OSGi bundle)
        └─→ LukeIndexStatsService
              ├─→ References: IndexTracker, IndexCopier
              └─→ Registers: LukeIndexStatsMBean (JMX)
                    └─→ Direct Lucene API access
                          └─→ Fields, Terms, Stats
```

### Design Principles

1. **Minimal Dependencies** - Only uses Oak's existing Lucene integration
2. **No Data Movement** - Works with in-place index cache
3. **Read-Only** - Never modifies indexes
4. **Fail-Safe** - Graceful handling of missing indexes
5. **OSGi Native** - Proper service references and lifecycle

## 📊 Technical Details

### Bundle Manifest

```
Bundle-SymbolicName: oak-luke-bundle
Bundle-Version: 1.22.24.SNAPSHOT
Bundle-Activator: ...LukeBundleActivator

Export-Package:
  org.apache.jackrabbit.oak.plugins.index.lucene.luke

Import-Package:
  org.apache.jackrabbit.oak.plugins.index.lucene (IndexTracker, IndexCopier)
  org.apache.lucene.index (DirectoryReader, Terms, etc.)
  javax.management.openmbean (JMX)
```

### Service Dependencies

```xml
<scr:component immediate="true">
  <reference name="indexTracker"
             interface="...IndexTracker"
             cardinality="1..1"    <!-- Mandatory -->
             policy="static"/>
  
  <reference name="indexCopier"
             interface="...IndexCopier"
             cardinality="0..1"    <!-- Optional -->
             policy="dynamic"/>
</scr:component>
```

### JMX Registration

```java
ObjectName: org.apache.jackrabbit.oak:name=LukeIndexStats,type=LukeIndexStats
Interface: LukeIndexStatsMBean
Implementation: LukeIndexStatsMBeanImpl
```

## 🔄 Build Instructions

### Prerequisites

- Java 11
- Maven 3.6+
- Oak 1.22 source code

### Build Commands

```bash
# Set Java 11
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-11.jdk/Contents/Home

# Build entire Oak project (first time)
cd jackrabbit-oak
mvn clean install -DskipTests

# Or build only LUKE modules
mvn clean install -DskipTests -pl oak-run-luke,oak-luke-bundle -am
```

### Build Results

```
oak-run-luke/target/
└── oak-run-luke-1.22.24-SNAPSHOT.jar         # 38 MB - CLI tool

oak-luke-bundle/target/
└── oak-luke-bundle-1.22.24-SNAPSHOT.jar      # 21 KB - OSGi bundle
```

## 📝 Version History

### Branch: feature/oak-run-luke-integration-1.22

| Commit | Description |
|--------|-------------|
| efc67e9 | Add comprehensive deployment guide |
| 7d3a4c5 | Add oak-luke-bundle OSGi implementation |
| 7ca5ecd | Fix Oak 1.22 API compatibility |
| 03f35e3 | Update to Oak 1.22.24-SNAPSHOT |
| abdf02f | Initial oak-run-luke implementation |

## 🔗 Related Oak Features

- **LuceneIndexMBean** - High-level index management
- **IndexTracker** - Index reader management
- **IndexCopier** - Local index caching
- **Oak Console** - Interactive repository browser
- **oak-run index** - Index management utilities

## 🎓 Learning Resources

### Understanding Oak Indexes

1. [Oak Lucene Documentation](https://jackrabbit.apache.org/oak/docs/query/lucene.html)
2. [Index Management Guide](https://jackrabbit.apache.org/oak/docs/query/indexing.html)
3. [Query Performance Tuning](https://jackrabbit.apache.org/oak/docs/query/query-engine.html)

### LUKE Tool

- Original LUKE tool by Andrzej Bialecki
- Now part of Apache Lucene project
- Our integration adapts LUKE concepts for Oak's architecture

## 🚧 Known Limitations

1. **Local Cache Only** - Only inspects indexes in IndexCopier local cache
2. **Oak 1.22 Focus** - Optimized for AEM 6.5.x (Oak 1.22.x)
3. **Read-Only** - Cannot modify indexes (by design)
4. **Single Instance** - Not designed for distributed/cluster analysis
5. **No GUI Yet** - JMX-based inspection only (GUI launcher is TODO)

## 🔮 Future Enhancements

- [ ] LUKE GUI integration
- [ ] Oak 1.40+ support for AEM Cloud Service
- [ ] Index comparison tools (before/after reindex)
- [ ] CSV/JSON export of statistics
- [ ] Integration with Oak Console
- [ ] Remote index analysis across cluster
- [ ] Automated index health checks
- [ ] Integration with AEM Health Check framework

## 📞 Support

### For Issues

1. Check AEM logs: `crx-quickstart/logs/error.log`
2. Verify bundle status: `http://localhost:4502/system/console/bundles`
3. Review component: `http://localhost:4502/system/console/components`
4. Check JMX Console: `http://localhost:4502/system/console/jmx`

### Contributing

This is a feature branch for potential contribution to Apache Jackrabbit Oak:
- Branch: `feature/oak-run-luke-integration-1.22`
- Target: Oak 1.22.x maintenance branch
- Compatible: AEM 6.5.x

## 📄 License

Apache License, Version 2.0

---

## 🎉 Summary

You now have **two powerful tools** for LUKE-based index inspection:

1. **oak-luke-bundle** (21 KB) - Deploy to AEM, instant JMX access ✨
2. **oak-run-luke** (38 MB) - Standalone tool for offline analysis

**Both work with Oak 1.22 / AEM 6.5.x and handle 100GB+ indexes without dumping!**

Ready to deploy? See [oak-luke-bundle/DEPLOYMENT.md](oak-luke-bundle/DEPLOYMENT.md) for step-by-step instructions.

