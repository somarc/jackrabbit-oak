# Oak IPFS Cloud Blob Store

The Somarc-owned IPFS binary-storage module used by Oak Segment Consensus.
It implements Oak's DataStore backend and is built as part of this permanent
[downstream distribution](../docs/FORK-MAIN-CONTRACT.md).

Structured repository content stays in Oak Segment/TAR storage. Large binaries
can be delegated to an IPFS endpoint. `IPFSBackend` uploads and pins data and
persists Oak blob-ID-to-CID mappings and metadata in IPFS Files (MFS). A local
cache accelerates those lookups; it is not the authoritative mapping store.

## Compatibility and build

This module uses the Oak 2.x DataStore API in
`org.apache.jackrabbit.oak.spi.blob.data`, not the older Jackrabbit DataStore API.
Its version is `2.7.0-somarc-SNAPSHOT`, and its Oak dependencies use the pinned
`2.7-SNAPSHOT` reactor. It is not an official Apache artifact.

With Maven 3.6.1+ and JDK 17 or 21, from the repository root:

```sh
mvn -B -ntp -pl oak-blob-cloud-ipfs -am verify -DskipITs
```

The ordinary unit suite uses a client seam/mocks and does not require a live IPFS
daemon. Live upload, pinning, retrieval, restart, and recovery evidence must be
reported separately. CI does not publish this development artifact.

## Integration

- `IPFSDataStore`: the shared caching DataStore implementation.
- `IPFSBackend`: IPFS operations and durable MFS metadata/mappings.
- `IPFSDataStoreService`: OSGi configuration and Oak BlobStore registration.

Programmatic users wrap `IPFSDataStore` in Oak's `DataStoreBlobStore` before
passing it to `FileStoreBuilder.withBlobStore(...)`; a DataStore is not itself a
BlobStore. Configure a local cache path before initialization and close the
wrapper after closing its users.

OSGi configuration PID:
`org.apache.jackrabbit.oak.blob.cloud.ipfs.IPFSDataStore`.

| Setting | Meaning |
|---|---|
| `ipfsApiEndpoint` | Kubo HTTP API endpoint, e.g. `/ip4/127.0.0.1/tcp/5001` |
| `ipfsFilesRoot` | MFS namespace for mappings and metadata; use a distinct root per Oak repository |
| `minRecordLength` | Binary threshold, default 16 KiB; smaller values stay inline |
| `cacheSizeInMB` | Oak blob-ID cache size |
| `path` | Local shared-caching DataStore directory |
| `cacheSize` | Local cache capacity in bytes; bind it to an explicit resource budget |
| `stagingSplitPercentage` | Local staging share |
| `uploadThreads` | Background upload concurrency |

Treat the IPFS API as a trusted administrative endpoint. Do not expose it to an
untrusted network. Select cache, staging, disk, and concurrency limits before
running a campaign; the inherited cache defaults are not a laptop test budget.

## Current boundaries

- Persistent mappings survive backend restarts **against the same IPFS
  repository**. Independent IPFS repositories are not automatically coordinated.
- Pinning is local to the configured endpoint. There is no module-level guarantee
  of replica count or cluster-wide placement.
- An accepted/committed Oak mutation is not by itself proof that arbitrary remote
  IPFS peers can retrieve its binary; verify retrieval and the payload checksum.
- There is no automatic S3 fallback. IPFS availability and replication are
  operational responsibilities.
- Binary deletion and unpinning affect persistent data. Do not run garbage
  collection or delete campaigns against an existing repository without explicit
  approval and a recovery plan.

These are capability boundaries of an actively maintained product module, not a
claim of production readiness. See the
[promotion record](../docs/PRODUCT-PROMOTION.md) for evidence against the current
baseline and [Kubo documentation](https://docs.ipfs.tech/) for IPFS operations.

## License

[Apache License 2.0](../LICENSE.txt); retain [NOTICE.txt](../NOTICE.txt) when
redistributing. Somarc builds must not be represented as official Apache releases.
