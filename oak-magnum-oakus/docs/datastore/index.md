# 💾 DataStore Tools

The DataStore stores binary content (images, PDFs, videos) separately from the segment store.

## DataStore Types

| Type | Storage | Use Case |
|------|---------|----------|
| **FileDataStore** | Local filesystem | On-premise, single instance |
| **S3DataStore** | Amazon S3 | Cloud, scalable |
| **AzureDataStore** | Azure Blob Storage | Azure deployments |
| **SharedS3DataStore** | S3 with shared access | Multi-instance clusters |

## Common Operations

### Consistency Check

Verify all blob references in the repository point to existing blobs:

```bash
java -jar oak-run-*.jar datastorecheck \
    --fds /path/to/datastore \
    --store /path/to/segmentstore \
    --dump /tmp/datastore-check
```

### Garbage Collection

Remove unreferenced blobs from the DataStore:

```bash
# Mark phase (safe, read-only)
java -jar oak-run-*.jar datastore \
    --fds /path/to/datastore \
    --store /path/to/segmentstore \
    mark

# Sweep phase (deletes unreferenced blobs)
java -jar oak-run-*.jar datastore \
    --fds /path/to/datastore \
    --store /path/to/segmentstore \
    sweep
```

::: warning ⚠️ DataStore GC Timing
- Run mark phase while AEM is running
- Run sweep phase during maintenance window
- Allow 24+ hours between mark and sweep
- Never run sweep without recent mark
:::

## Detailed Guides

- [Consistency Check](/datastore/consistency) - Verify blob integrity
- [Garbage Collection](/datastore/gc) - Reclaim blob storage space
