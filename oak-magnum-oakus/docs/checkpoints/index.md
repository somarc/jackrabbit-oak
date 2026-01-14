# 📋 Understanding Checkpoints

A **checkpoint** in Oak is a snapshot of the repository state at a specific point in time. Checkpoints are critical for async indexing, backup operations, and maintenance tasks.

## What is a Checkpoint?

- A snapshot of the entire repository at a specific revision
- Stored as a segment reference in the FileStore
- **Prevents garbage collection** from deleting segments reachable from that checkpoint
- Created by async indexers, backup tools, or manually

## Checkpoint Architecture

```
Segment Store Root (/)
├─ /root                          ← JCR repository tree (what users see)
│   ├─ content/
│   ├─ apps/
│   ├─ etc/
│   ├─ :async/                    ← Indexer bookmarks (inside JCR)
│   │   ├─ async = "checkpoint-uuid-1"
│   │   ├─ fulltext-async = "checkpoint-uuid-2"
│   │   └─ ... (STRING properties)
│   └─ ... (normal JCR tree)
│
└─ /checkpoints                   ← Checkpoint storage (OUTSIDE JCR!)
    ├─ checkpoint-uuid-1/         ← Actual checkpoint data
    ├─ checkpoint-uuid-2/
    └─ ... (internal Oak metadata)
```

::: tip Key Understanding
- **Checkpoints exist OUTSIDE the JCR tree** - parallel to `/root`, not inside it
- **`/:async` properties are just string pointers** - references to checkpoint UUIDs
- **The actual checkpoint data lives in `/checkpoints`** - contains revision references
:::

## How Checkpoints Pin Segments

<OakFlowGraph flow="checkpoint-pin" :height="500" />

### The Pinning Problem

When a checkpoint references a segment, that segment **cannot be deleted** during garbage collection:

```
Garbage Collection Algorithm:

1. Determine "reachable" segments:
   ├─ Start from HEAD revision
   ├─ Start from ALL checkpoints
   ├─ Traverse segment graph from these roots
   └─ Mark all reachable segments as "KEEP"

2. Mark tar files for deletion:
   ├─ For each tar file:
   │   ├─ Check if ANY segment in tar is "KEEP"
   │   ├─ If YES → tar file is KEPT (entire file)
   │   └─ If NO → tar file is DELETABLE
```

### Orphaned Checkpoints = Disk Bloat

```
Example: 3 Orphaned Checkpoints Prevent Cleanup

Day 1:  Checkpoint cp1 → references segment in data00001a.tar
Day 10: Checkpoint cp2 → references segment in data00002a.tar  
Day 20: Checkpoint cp3 → references segment in data00003a.tar
Day 30: Checkpoint cp4 → ACTIVE (indexer using this)
Day 40: Compaction runs

Cleanup Phase:
1. HEAD references: data00004a.tar, data00005a.tar
2. cp4 (active) references: data00004a.tar
3. cp3 (orphaned) references: data00003a.tar ← BLOCKS DELETION
4. cp2 (orphaned) references: data00002a.tar ← BLOCKS DELETION
5. cp1 (orphaned) references: data00001a.tar ← BLOCKS DELETION

Result: 3 tar files (potentially gigabytes) that should be deleted are kept
```

## Managing Checkpoints

### List Checkpoints

```bash
java -jar oak-run-*.jar checkpoints /path/to/segmentstore list
```

### Remove Unreferenced Checkpoints

```bash
java -jar oak-run-*.jar checkpoints /path/to/segmentstore rm-unreferenced
```

**What this does:**
1. Scans `/checkpoints` for all repository checkpoints
2. Checks which checkpoint is referenced by `/:async@async`
3. **Removes all checkpoints EXCEPT the active one**
4. Next cleanup can now delete old tar files

**Why this is safe:**
- The async indexer **actively references** the checkpoint it needs
- All other checkpoints are orphaned (no property references them)
- Removing them unpins segments → allows cleanup to reclaim space

### When to Use `rm-unreferenced`

✅ **Safe to use:**
- Disk space is running low
- Many old checkpoints exist (dozens in list)
- Normal operations (not mid-recovery)
- Regular maintenance after compaction

⚠️ **Do NOT use:**
- Mid-recovery (you might need older checkpoints)
- Corruption detected but not analyzed
- Right after backup (backup tools create checkpoints)
- Custom checkpoint usage for testing

## Checkpoint Commands Reference

| Command | Description |
|---------|-------------|
| `list` | Show all checkpoints with metadata |
| `rm-all` | ⚠️ DANGEROUS - Removes ALL checkpoints |
| `rm-unreferenced` | Safe - Removes only orphaned checkpoints |
| `rm <checkpoint>` | Remove specific checkpoint by UUID |
| `info <checkpoint>` | Show metadata for specific checkpoint |
| `set <checkpoint> <name> [<value>]` | Set/remove metadata property |

::: danger ⚠️ NEVER Use `rm-all`
Deleting all checkpoints removes the one referenced by async indexer (`/:async@async`). This causes:
- AEM won't start ("Cannot read index checkpoint")
- Must force full reindex or restore from backup
:::

## Disk Space Impact

**Real-world example:**

```
Before rm-unreferenced:
- 50 orphaned checkpoints (accumulated over 6 months)
- Each checkpoint pins ~200MB of old tar files
- Total disk bloat: ~10GB of tar files that can't be deleted

After rm-unreferenced + next cleanup:
- 1 active checkpoint (indexer's current reference)
- Old tar files no longer pinned
- Disk reclaimed: ~10GB freed
```
