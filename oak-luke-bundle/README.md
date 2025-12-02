# Oak LUKE OSGi Bundle

OSGi bundle for deploying LUKE index inspection capabilities to Adobe Experience Manager (AEM) 6.5.x.

## Overview

This bundle provides a JMX MBean (`LukeIndexStatsMBean`) that enables deep inspection and analysis of Lucene indexes directly within a running AEM instance, without the need to dump or export index data.

## Deployment

### Prerequisites

- AEM 6.5.x (Oak 1.22.x)
- Java 11
- Access to Felix Web Console and JMX Console

### Installation Steps

1. **Build the bundle:**

```bash
cd jackrabbit-oak
mvn clean install -DskipTests -pl oak-luke-bundle
```

2. **Deploy to AEM:**

Upload the bundle via Felix Web Console:
```
http://localhost:4502/system/console/bundles
```

Or copy directly to:
```
crx-quickstart/install/oak-luke-bundle-1.22.24-SNAPSHOT.jar
```

3. **Verify installation:**

Check bundle status in Felix Console:
```
http://localhost:4502/system/console/bundles
```

Look for: **Apache Jackrabbit Oak LUKE Index Statistics**  
Status should be: **Active**

## Usage

### Access JMX MBean

Navigate to JMX Console:
```
http://localhost:4502/system/console/jmx
```

Find MBean:
```
org.apache.jackrabbit.oak → LukeIndexStats
```

### Available Operations

#### 1. List All Local Index Directories

```java
getLocalIndexDirectories()
```

Returns: TabularData with index paths, local paths, sizes, and existence status

#### 2. Get Index Statistics

```java
getLukeIndexStats("/oak:index/damAssetLucene")
```

Returns: TabularData with:
- Number of documents
- Max doc
- Has deletions
- Number of fields
- Index size
- Local filesystem path

#### 3. Get Field Information

```java
getLukeFieldInfo("/oak:index/damAssetLucene", 100)
```

Parameters:
- `indexPath`: Repository index path (e.g., `/oak:index/damAssetLucene`)
- `maxFields`: Maximum number of fields to return

Returns: String[] with field names, term counts, and document counts

#### 4. Get Term Statistics

```java
getLukeTermStats("/oak:index/damAssetLucene", "jcr:content/metadata/dc:title", 50)
```

Parameters:
- `indexPath`: Repository index path
- `fieldName`: Field to analyze
- `maxTerms`: Maximum number of terms to return

Returns: String[] with term values, frequencies, and document frequencies

#### 5. Get Basic Stats

```java
getLukeBasicStats("/oak:index/damAssetLucene")
```

Returns: Formatted string with:
- Index path
- Local filesystem path
- Document counts
- Field count
- Index size

#### 6. Validate Index

```java
validateLocalIndex("/oak:index/damAssetLucene")
```

Returns: Validation result string (OK or ERROR with details)

#### 7. Get Local Index Path

```java
getLocalIndexPath("/oak:index/damAssetLucene")
```

Returns: Absolute filesystem path to the local index directory

#### 8. Get Index Cache Status

```java
getIndexCacheStatus()
```

Returns: Same as `getLocalIndexDirectories()` - overview of all cached indexes

## Use Cases

### 1. Debug Query Performance

Analyze which fields are indexed and their term distributions:

```java
// Check what fields exist
getLukeFieldInfo("/oak:index/damAssetLucene", 100)

// Analyze specific field
getLukeTermStats("/oak:index/damAssetLucene", "jcr:content/metadata/dam:assetState", 100)
```

### 2. Validate Index Health

Check index integrity before/after reindexing:

```java
validateLocalIndex("/oak:index/damAssetLucene")
```

### 3. Plan Index Migrations

Understand current index structure:

```java
getLukeIndexStats("/oak:index/oldIndex")
getLukeFieldInfo("/oak:index/oldIndex", 1000)
```

### 4. Monitor Index Sizes

Track index growth over time:

```java
getLocalIndexDirectories()
```

## Architecture

### Components

1. **LukeIndexStatsMBean** - JMX MBean interface
2. **LukeIndexStatsMBeanImpl** - Implementation with Lucene access
3. **LukeIndexStatsService** - OSGi component (Felix SCR)
4. **LukeBundleActivator** - Bundle lifecycle management

### Dependencies

The bundle requires these OSGi services:

- **IndexTracker** (mandatory) - Tracks available Lucene indexes
- **IndexCopier** (optional) - Provides access to local index cache

If IndexCopier is not available, the MBean will use alternative methods to locate index directories.

### Service References

```
LukeIndexStatsService
├── References: IndexTracker (mandatory, static)
├── References: IndexCopier (optional, dynamic)
└── Registers: LukeIndexStatsMBean (JMX)
```

## Troubleshooting

### Bundle Not Active

Check OSGi console for errors:
```
http://localhost:4502/system/console/components
```

Common issues:
- Missing IndexTracker service
- Unsatisfied dependencies
- Version conflicts

### MBean Not Visible

1. Check bundle is Active
2. Verify in JMX Console
3. Check logs: `error.log` for registration errors

### "IndexCopier not available" Warnings

This is normal if:
- Repository is not using IndexCopier
- Index data is not cached locally

The MBean will still function using alternative access methods.

### Empty Results

If `getLocalIndexDirectories()` returns no data:
- Indexes may not be cached yet
- IndexCopier may not be active
- Check index definitions are valid

## Performance Considerations

- **Read-Only**: All operations are read-only, safe for production
- **No Dumping**: Works with existing IndexCopier cache, no export overhead
- **Minimal Impact**: Direct Lucene access, no repository traversal
- **Caching**: Uses IndexTracker's cached readers when possible

## Compatibility

- **AEM 6.5.0+** - Full support
- **Oak 1.22.x** - Tested and verified
- **Oak 1.8.x+** - Should work (untested)

## Security

The MBean respects JMX security settings:
- Requires JMX access permissions
- No write operations exposed
- Safe for production environments

## Logging

The bundle logs to Oak logger namespace:

```
org.apache.jackrabbit.oak.plugins.index.lucene.luke
```

Enable DEBUG logging in Felix Console:
```
http://localhost:4502/system/console/slinglog
```

## Known Limitations

1. **Local Cache Only**: Only inspects indexes in local cache
2. **No GUI**: Command-line LUKE GUI not included (JMX only)
3. **Read-Only**: Cannot modify indexes via this MBean
4. **Single Instance**: Designed for single-instance AEM setups

## Future Enhancements

- [ ] LUKE GUI integration
- [ ] Index comparison tools
- [ ] Export statistics to CSV/JSON
- [ ] Remote index analysis
- [ ] Integration with Oak Console

## Support

For issues or questions:
- Check AEM error logs
- Verify bundle status in Felix Console
- Review JMX Console for MBean registration

## License

Apache License, Version 2.0

## Version History

- **1.22.24-SNAPSHOT** - Initial release for Oak 1.22.x / AEM 6.5.x

