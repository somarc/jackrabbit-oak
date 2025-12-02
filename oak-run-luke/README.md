# Oak LUKE Integration

LUKE (Lucene Index Toolbox) integration for Apache Jackrabbit Oak, providing deep inspection and analysis capabilities for Lucene indexes stored in Oak repositories.

## Overview

The `oak-run-luke` module provides LUKE-based index inspection tools that work directly with Oak's IndexCopier local cache, eliminating the need to dump large indexes to disk. This is particularly useful for AEM environments where indexes can be 100GB+ in size.

## Features

- **JMX MBean Integration**: Expose LUKE functionality via JMX for live repository inspection
- **Local Cache Access**: Direct access to IndexCopier cache (e.g., `crx-quickstart/repository/index/`)
- **Zero Index Dumping**: No need to export indexes - works with existing local cache
- **Field Analysis**: Inspect index fields, terms, and statistics
- **Index Validation**: Verify index integrity and structure
- **CLI Interface**: Command-line tools for offline inspection

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                    AEM / Oak Instance                        │
│  ┌────────────────────────────────────────────────────────┐ │
│  │  crx-quickstart/repository/index/                      │ │
│  │  (IndexCopier local cache)                             │ │
│  │    ├── abc123_damAssetLucene/                          │ │
│  │    ├── def456_cqPageLucene/                            │ │
│  │    └── ...                                              │ │
│  └────────────────────────────────────────────────────────┘ │
│                           ▲                                   │
│                           │                                   │
│  ┌────────────────────────┴───────────────────────────────┐ │
│  │  LukeIndexStatsMBean (JMX)                             │ │
│  │    - inspectIndex(indexPath)                           │ │
│  │    - getIndexDirectories()                             │ │
│  │    - getLukeFieldInfo(indexPath)                       │ │
│  │    - getLukeTermStats(indexPath, field)                │ │
│  └────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────┘
```

## JMX MBean Usage

### Registering the MBean

In your Oak instance, register the `LukeIndexStatsMBean`:

```java
import org.apache.jackrabbit.oak.plugins.index.lucene.luke.LukeIndexStatsMBeanImpl;

// Assuming you have IndexTracker and IndexCopier instances
LukeIndexStatsMBean lukeMBean = new LukeIndexStatsMBeanImpl(indexTracker, indexCopier);

// Register with JMX
ObjectName name = new ObjectName("org.apache.jackrabbit.oak:name=LukeIndexStats,type=LukeIndexStats");
ManagementFactory.getPlatformMBeanServer().registerMBean(lukeMBean, name);
```

### Available JMX Operations

#### 1. List Local Index Directories

```java
TabularData directories = lukeMBean.getLocalIndexDirectories();
```

Returns all indexes in the local IndexCopier cache with their paths and sizes.

#### 2. Get Index Statistics

```java
TabularData stats = lukeMBean.getLukeIndexStats("/oak:index/damAssetLucene");
```

Returns:
- Number of documents
- Number of fields
- Index size
- Has deletions
- Local filesystem path

#### 3. Get Field Information

```java
String[] fields = lukeMBean.getLukeFieldInfo("/oak:index/damAssetLucene", 100);
```

Returns detailed information about indexed fields:
- Field name
- Number of terms
- Number of documents

#### 4. Get Term Statistics

```java
String[] terms = lukeMBean.getLukeTermStats(
    "/oak:index/damAssetLucene", 
    "jcr:content/metadata/dc:title",
    50
);
```

Returns term-level statistics:
- Term value
- Term frequency
- Document frequency

#### 5. Get Basic Stats

```java
String stats = lukeMBean.getLukeBasicStats("/oak:index/damAssetLucene");
```

Returns a formatted string with basic index information.

#### 6. Validate Index

```java
String result = lukeMBean.validateLocalIndex("/oak:index/damAssetLucene");
```

Validates that the index is readable and contains valid Lucene index files.

## Command-Line Usage

Build the runnable jar:

```bash
cd jackrabbit-oak/oak-run-luke
mvn clean install
```

### Available Commands

#### Help

```bash
java -jar target/oak-run-luke-*.jar help
```

#### List Indexes

```bash
java -jar oak-run-luke-*.jar list /path/to/repository
```

#### Inspect Index

```bash
java -jar oak-run-luke-*.jar inspect /path/to/repository --index /oak:index/damAssetLucene
```

#### Launch GUI (Planned)

```bash
java -jar oak-run-luke-*.jar gui /path/to/repository
```

## Use Cases

### 1. Index Debugging in AEM

When troubleshooting query performance issues:

```java
// Get field information to see what's indexed
String[] fields = lukeMBean.getLukeFieldInfo("/oak:index/damAssetLucene", 100);

// Check specific field terms
String[] terms = lukeMBean.getLukeTermStats(
    "/oak:index/damAssetLucene",
    "jcr:content/metadata/dam:assetState",
    100
);
```

### 2. Index Health Monitoring

Monitor index size and document counts:

```java
TabularData allIndexes = lukeMBean.getLocalIndexDirectories();
// Check sizes and validate indexes
```

### 3. Index Migration Planning

Before migrating to a new index definition:

```java
// Analyze current index structure
TabularData stats = lukeMBean.getLukeIndexStats("/oak:index/oldIndex");
String[] fields = lukeMBean.getLukeFieldInfo("/oak:index/oldIndex", 1000);
```

## Integration with Existing Tools

### With LuceneIndexMBean

`LukeIndexStatsMBean` complements the existing `LuceneIndexMBean`:

- `LuceneIndexMBean`: High-level stats, consistency checks, reindexing
- `LukeIndexStatsMBean`: Deep field/term analysis, LUKE-style inspection

### With IndexCopier

The implementation relies on `IndexCopier` to access local index files:

```
IndexCopier → getIndexRootDirectory() → local index cache
                                      ↓
                            LukeIndexStatsMBean accesses index files
```

## Configuration

### OSGi Configuration (AEM)

Create a configuration for the LukeIndexStats MBean:

```
org.apache.jackrabbit.oak.plugins.index.lucene.luke.LukeIndexStatsMBeanImpl
{
  "service.ranking": 100
}
```

### Standalone Oak

```java
// Create and register during repository initialization
LukeIndexStatsMBean lukeMBean = new LukeIndexStatsMBeanImpl(
    indexTracker,
    indexCopier
);
```

## Performance Considerations

- **Local Cache Only**: Only works with indexes that have been downloaded to local cache
- **Read-Only**: All operations are read-only, no write overhead
- **No Dumping**: Avoids expensive index export operations
- **Direct Lucene Access**: Uses Lucene `DirectoryReader` for efficient access

## Future Enhancements

- [ ] GUI launcher integration
- [ ] Offline index scanning without running Oak
- [ ] Index comparison tools
- [ ] Export index statistics to JSON/CSV
- [ ] Integration with Oak Console
- [ ] Support for remote index analysis

## Dependencies

- `oak-lucene`: For index access and Lucene integration
- `oak-run-commons`: For command framework
- Lucene 4.7.2: Core Lucene functionality

## Building

```bash
# Build oak-run-luke module
cd jackrabbit-oak/oak-run-luke
mvn clean install

# Build entire Oak project including oak-run-luke
cd jackrabbit-oak
mvn clean install
```

## Testing

```bash
# Run unit tests
mvn test

# Run with integration tests
mvn verify
```

## License

Apache License, Version 2.0

## Contributing

See [CONTRIBUTING.md](../CONTRIBUTING.md) in the main Oak repository.

## Support

- [Apache Jackrabbit Oak Documentation](https://jackrabbit.apache.org/oak/)
- [Apache Jackrabbit Mailing Lists](https://jackrabbit.apache.org/mailing-lists.html)
- [JIRA Issue Tracker](https://issues.apache.org/jira/browse/OAK)

## Related Documentation

- [Oak Lucene Index](https://jackrabbit.apache.org/oak/docs/query/lucene.html)
- [Index Management](https://jackrabbit.apache.org/oak/docs/query/indexing.html)
- [Oak Console](https://jackrabbit.apache.org/oak/docs/features/oak-run-console.html)

