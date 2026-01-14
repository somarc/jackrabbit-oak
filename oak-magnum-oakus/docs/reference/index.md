# 📚 Command Reference

Complete reference for oak-run commands and console operations.

## oak-run Commands

### Diagnostic Commands

| Command | Description |
|---------|-------------|
| `check` | Verify repository consistency |
| `checkpoints` | Manage checkpoints |
| `datastorecheck` | Verify DataStore consistency |
| `explore` | Interactive repository browser |

### Recovery Commands

| Command | Description |
|---------|-------------|
| `recover-journal` | Rebuild journal.log |
| `console` | Interactive console for repairs |
| `compact` | Run offline compaction |

### Maintenance Commands

| Command | Description |
|---------|-------------|
| `datastore` | DataStore garbage collection |
| `tarmkdiff` | Compare two repositories |
| `tarmkrecovery` | Low-level TAR recovery |

## Console Commands

When running `oak-run console`:

| Command | Description |
|---------|-------------|
| `:help` | Show available commands |
| `:count-nodes` | Count nodes and detect corruption |
| `:remove-nodes` | Remove corrupted paths |
| `:refresh` | Refresh repository state |
| `:exit` | Exit console |

## Detailed Guides

- [count-nodes](/reference/count-nodes) - Deep node analysis
- [Console Commands](/reference/console) - Interactive console reference
- [Troubleshooting](/reference/troubleshooting) - Common issues and solutions
