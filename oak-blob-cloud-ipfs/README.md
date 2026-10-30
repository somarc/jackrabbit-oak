# Oak IPFS Cloud Blob Store

The Somarc-owned IPFS binary-storage module used by Oak Segment Consensus.
It implements Oak's DataStore backend and is built as part of this permanent
[downstream distribution](../docs/FORK-MAIN-CONTRACT.md).

Structured repository content stays in Oak Segment/TAR storage. Large binaries
can be delegated to an IPFS endpoint. `IPFSBackend` imports and pins data and
keeps its durable state in IPFS Files (MFS). A local cache accelerates lookups;
it is not the authoritative store.

## Compatibility and build

This module uses the Oak 2.x DataStore API in
`org.apache.jackrabbit.oak.spi.blob.data`, not the older Jackrabbit DataStore API.
Its version is `2.7.0-somarc-SNAPSHOT`, and its Oak dependencies use the pinned
`2.7-SNAPSHOT` reactor. It is not an official Apache artifact.

With Maven 3.6.1+ and JDK 17 or 21, from the repository root:

```sh
mvn -B -ntp -pl oak-blob-cloud-ipfs -am verify -DskipITs
```

Most unit tests use a client seam and need no IPFS daemon. `IPFSKuboTest` runs
the backend, the DataStore and Oak's `MarkSweepGarbageCollector` against a real
Kubo daemon (`ipfs/kubo:v0.43.1`) in Docker, and is skipped when Docker is not
available. Live cluster evidence must still be reported separately. CI does not
publish this development artifact.

## Integration

- `IPFSDataStore`: the shared caching DataStore implementation.
- `IPFSBackend`: IPFS operations and durable MFS metadata/mappings.
- `IPFSDataStoreService`: OSGi configuration and Oak BlobStore registration.

### Storage layout

Under `ipfsFilesRoot`, keyed by the base64url-encoded Oak identifier (the
SHA-256 of the content):

| Path | Holds |
|---|---|
| `blob-index/<id>` | The CID. The format is unchanged, so older builds can still read it. |
| `blob-attrs/<id>` | `length` and `lastModified`, which DataStore GC compares against. |
| `content/<id>` | A link to the CID that keeps the blocks retained independently of pins. |
| `metadata/<name>`, `metadata-attrs/<name>` | Shared DataStore metadata records and their write time. |

Content is imported with Kubo's legacy profile set explicitly (CIDv0,
SHA2-256, 256 KiB chunks, no raw leaves, 174 links per node, balanced layout),
so a node's own `Import.*` settings cannot change the CIDs Oak records;
`IPFSKuboImportProfileTest` checks this against a node with skewed settings
and a golden CID. Each import is confirmed by its recursive pin, because Kubo
reports late import failures only in an HTTP trailer. Reads are streamed and checked against the
SHA-256 identifier, so a truncated or wrong payload fails instead of being
cached. Writing an existing blob refreshes its `lastModified`, and a different
length for the same identifier is rejected as a collision, as with S3 and Azure.

Entries written before `blob-attrs` existed report their UnixFS file size and,
the first time they are read, record the current time as their last-modified
time. They therefore become eligible for GC one full safety window after the
upgrade, never earlier. Failing to reach IPFS is reported as an error rather
than as missing data, and enumerating records for GC fails instead of reporting
an unknown length.

Programmatic users wrap `IPFSDataStore` in Oak's `DataStoreBlobStore` before
passing it to `FileStoreBuilder.withBlobStore(...)`; a DataStore is not itself a
BlobStore. Configure a local cache path before initialization and close the
wrapper after closing its users.

OSGi configuration PID:
`org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore`.

| Setting | Meaning |
|---|---|
| `ipfsApiEndpoint` | Kubo RPC endpoint: a multiaddr such as `/ip4/127.0.0.1/tcp/5001` or `/dns4/kubo/tcp/443/https`, or an `http(s)://` URL |
| `ipfsApiAuthorization` | `Authorization` header for the RPC API, e.g. `Bearer <token>` for Kubo's `API.Authorizations`; never logged |
| `ipfsConnectTimeoutMillis`, `ipfsRequestTimeoutMillis`, `ipfsMaxAttempts` | Defaults 10 s, 60 s and 3. Transport failures and HTTP 502/503/504 are retried with exponential backoff; Kubo command errors are not. Uploads get one extra second per MiB |
| `ipfsFilesRoot` | MFS namespace for mappings and metadata; use a distinct root per Oak repository |
| `minRecordLength` | Binary threshold, default 16 KiB; smaller values stay inline |
| `cacheSizeInMB` | Oak blob-ID cache size |
| `path` | Local shared-caching DataStore directory |
| `cacheSize` | Local cache capacity in bytes; bind it to an explicit resource budget |
| `stagingSplitPercentage` | Local staging share |
| `uploadThreads` | Background upload concurrency |

The client is the JDK HTTP client: it reuses connections, streams uploads and
downloads, and uses the JVM trust store for TLS. The request timeout also bounds
any pause while a response body is read, so a stalled download fails instead of
hanging. Endpoints that do not clearly select plain HTTP or TLS are rejected, and
credentials sent over plain HTTP to a non-loopback host are logged as a warning.
The module has no third-party IPFS dependencies.

Treat the IPFS API as an administrative endpoint: require a token with Kubo's
`API.Authorizations`, and put TLS in front of it when it leaves the host. Select cache, staging, disk, and concurrency limits before
running a campaign; the inherited cache defaults are not a laptop test budget.

## Current boundaries

- Persistent mappings survive backend restarts against the same IPFS
  repository. Independent IPFS repositories share blobs only through a
  `CidResolver` (`IPFSDataStore.setCidResolver`): a blob missing from the local
  index is looked up there, recorded locally (mapping and attributes, no
  retention link) and read through the local node, which fetches it from its
  peers. `IPFSReplicatedReadTest` shows this across three private-swarm nodes.
  The reading node does not pin what it fetches, so the binary remains as
  available as the node that stored it.
  Without a resolver, a blob can only be resolved through the repository and
  files root that stored it.
- Pinning is local to the configured endpoint. There is no module-level guarantee
  of replica count or cluster-wide placement.
- An accepted/committed Oak mutation is not by itself proof that arbitrary remote
  IPFS peers can retrieve its binary; verify retrieval and the payload checksum.
- There is no automatic S3 fallback. IPFS availability and replication are
  operational responsibilities.
- Records carry real lengths and write times. Against a real Kubo node,
  `IPFSKuboTest` shows `MarkSweepGarbageCollector` deleting an unreferenced blob
  older than its safety window while keeping referenced, fresh and legacy blobs.
  Earlier builds reported the size of the root IPFS block and the backend start
  time instead, which made every IPFS blob a GC candidate; do not run GC with
  those builds, and do not run it while old and new builds share one files root.
- Binary deletion and unpinning affect persistent data. Do not run garbage
  collection or delete campaigns against an existing repository without explicit
  approval and a recovery plan.
- Content is not encrypted, and a Kubo node with default settings announces
  what it stores to the public IPFS network. Use a private network
  (`swarm.key`) for content that must stay private.

These are capability boundaries of an actively maintained product module, not a
claim of production readiness. See the
[promotion record](../docs/PRODUCT-PROMOTION.md) for evidence against the current
baseline and [Kubo documentation](https://docs.ipfs.tech/) for IPFS operations.

## License

[Apache License 2.0](../LICENSE.txt); retain [NOTICE.txt](../NOTICE.txt) when
redistributing. Somarc builds must not be represented as official Apache releases.
