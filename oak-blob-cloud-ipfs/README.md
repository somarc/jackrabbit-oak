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
| `ipfsClusterEndpoint`, `ipfsClusterAuthorization` | IPFS Cluster REST API (multiaddr or URL) and its `Authorization` header; empty disables replication |
| `ipfsClusterReplicationMin`, `ipfsClusterReplicationMax`, `ipfsClusterPinTimeoutMillis` | Defaults 2, 3 and 120 s: peers that must hold a binary before its write returns, peers that should hold it, and how long to wait |
| `ipfsClusterUnpinOnDelete` | Default `false`. Set `true` only when the cluster stores this Oak repository alone; deletes then unpin across the cluster |
| `ipfsEncryptionKeys` | Keyring `current;id:base64Key;...` (32-byte keys) that encrypts every new binary before it reaches IPFS; empty stores plaintext. Never logged. See [Encryption](#encryption) |
| `ipfsNetworkPolicy`, `ipfsSwarmProbeAddress` | Default `warn` and unset. See [Private network](#private-network) |
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

## Private network

A Kubo node with default settings joins the public IPFS network: it bootstraps
to public peers, announces every block it stores to the public DHT, asks public
HTTP routers about content, and is reachable through public relays. Anyone who
learns a CID can then fetch the content. At startup the DataStore reads the
node's configuration and listeners and reports each such exposure:

| `ipfsNetworkPolicy` | Effect |
|---|---|
| `warn` (default) | Logs every exposure as a warning |
| `private` | Refuses to start unless the node is proven to use a private network key and exposes nothing |
| `off` | No check |

The configuration does not show whether the node has a private network key
(`swarm.key`), so set `ipfsSwarmProbeAddress` to the node's swarm listener as
Oak reaches it (for example `kubo:4001`). Startup then opens a connection to it:
a node with a key answers with a random nonce, one without with the plaintext
libp2p multistream header. `private` requires this proof. Delegated HTTP routers
and AutoConf are reported even on a private network, because they are plain
HTTPS calls the key does not cover.

A minimal private Kubo node (v0.43): a `swarm.key` shared by all nodes, the
daemon started with `LIBP2P_FORCE_PNET=1` so it refuses to run without one, and

```
ipfs bootstrap rm --all
ipfs config --json AutoConf.Enabled false
ipfs config Routing.Type none        # or dht for larger swarms that are not fully peered
ipfs config --json Routing.DelegatedRouters '[]'
ipfs config --json Ipns.DelegatedPublishers '[]'
ipfs config --json DNS.Resolvers '{}'
ipfs config --json Provide.Enabled false   # with Routing.Type none
ipfs config --json Swarm.RelayClient.Enabled false
ipfs config --json Swarm.Transports.Network.Websocket false
ipfs config --json Gateway.NoFetch true
```

with `Peering.Peers` listing every other node. `IPFSNetworkPostureKuboTest`
shows the DataStore starting under `private` on such a node and refusing one
without a key.

## Encryption

With `ipfsEncryptionKeys` (or `IPFSDataStore.setBlobKeyProvider` for keys held
elsewhere, such as a KMS), every new binary is encrypted on the Oak side before
it is imported, the way S3's client-side encryption works: IPFS nodes, cluster
peers, gateways and the swarm receive its ciphertext only.

- Format (`BlobEncryption`, version 1): a header (magic `0x89 'O' 'K' 'E'`,
  version, segment size, key id, random 32-byte salt, random 7-byte nonce
  prefix) followed by 64 KiB AES-256-GCM segments. The data key is
  HKDF-SHA256 of the master key and the salt. Each segment's nonce carries its
  index and a last-segment flag, and each authenticates the header and the Oak
  identifier, so tampering, truncation, reordering, appended data and swapping
  ciphertext between identifiers all fail. A segment is released only after its
  tag verifies. Overhead is the header plus 16 bytes per 64 KiB.
- Key rotation: new binaries use the `current` key; older ones are read with
  the key named in their header, so keep retired keys in the keyring until no
  binary needs them. Every repository that reads the same binaries, such as all
  validators of one consensus cluster, needs the same keyring; a repository
  without keys writes plaintext.
- Reads decrypt a binary that starts with the magic and pass other binaries
  through, so plaintext written before encryption was enabled stays readable.
  For Oak's SHA-256 identifiers the result is then checked against the
  identifier, so a store cannot substitute content by serving plaintext. A
  binary read without its key fails that check at the end of the stream. A
  plaintext binary that happens to start with the magic bytes cannot be read
  once keys are configured.
- Reported lengths are plaintext lengths. CIDs are of ciphertext: the same file
  encrypts differently each time, so IPFS does not deduplicate it. Oak still
  deduplicates by identifier within one repository, but validators that ingest
  the same content each store their own ciphertext. An IPFS gateway URL for the
  CID serves ciphertext; serve binaries through Oak.
- Not covered:
  - binaries already stored as plaintext, which stay plaintext when they are
    written again;
  - the plaintext's SHA-256 identifier, which appears in the IPFS Files index,
    IPFS Cluster pin names and the replicated binary index, so a party that can
    read those can confirm whether a known file is stored;
  - plaintext size (no padding), access patterns and the key id in the header;
  - the local staging and download caches and the temporary ciphertext file,
    which are on the repository host as with every Oak DataStore.

`IPFSEncryptionKuboTest` shows against a real Kubo node that the stored bytes do
not contain the plaintext, that a second repository with the keys reads the
binary through its CID alone, and that a wrong key or no key fails.

## Operations

**Health and metrics.** `IPFSDataStore.checkHealth()` calls the IPFS node (and
the cluster, when configured) and reports reachability, latency, the encryption
key id in use (never the key), the network check's findings and operation
counts since startup. `getStatistics()` returns the counts alone without calling
IPFS: writes, written bytes, reads, deletes, their failures, cluster pins, pin
failures and the time spent waiting for replication. The consensus server shows
them at `/v1/ops/ipfs/health`, a status summary in `/health/deep`, and as
`oak_ipfs_*_total` counters at `/metrics`.

**Retention check.** `verifyRetention(limit)` walks the local index and reports
binaries held by fewer than `ipfsClusterReplicationMin` cluster peers, or,
without a cluster, binaries stored through this node that lost their pin. It
makes one or two IPFS calls per binary; run it with a limit and repeat. The
consensus server exposes it at `/v1/ops/ipfs/verify?limit=`.

**Runbook.**

| Situation | Action |
|---|---|
| An IPFS node is lost | With a cluster, nothing is lost while `replicationMin` peers remain. Replace the node, let the cluster re-allocate (`ipfs-cluster-ctl recover --all` for pins in `pin_error`), then run the retention check. Without a cluster, binaries stored only there are gone; restore its repository. |
| Writes fail with "cannot pin … on N peers" | Fewer healthy peers than `ipfsClusterReplicationMin`. Restore peers or lower the minimum deliberately; writes are refused rather than acknowledged under-replicated. |
| Rotating the encryption key | Add the new key and make it `current` on every repository at once; keep the old key listed while any binary was written with it. There is no re-encryption of existing binaries. |
| Reclaiming space after DataStore GC | Without a cluster, deletes unpin and Kubo's own GC (`ipfs repo gc`) reclaims blocks. With a shared cluster, deleted binaries stay pinned; unpin them in the cluster once no repository needs them, or set `ipfsClusterUnpinOnDelete` for a dedicated cluster. |
| Startup refused by `ipfsNetworkPolicy=private` | The log lists each exposure. Fix the Kubo configuration (see [Private network](#private-network)) or, temporarily, use `warn`. |

**Scale.** Identifier-to-CID mappings are cached for the latest 100,000
binaries and read from the IPFS Files index beyond that. Enumeration for
DataStore GC lists the whole index in one call and reads each binary's
attributes with one call, so GC time grows linearly with the number of
binaries.

## Current boundaries

- Persistent mappings survive backend restarts against the same IPFS
  repository. Independent IPFS repositories share blobs only through a
  `CidResolver` (`IPFSDataStore.setCidResolver`): a blob missing from the local
  index is looked up there, recorded locally (mapping and attributes, no
  retention link) and read through the local node, which fetches it from its
  peers. `IPFSReplicatedReadTest` shows this across three private-swarm nodes.
  The reading node does not pin what it fetches; durability across nodes
  comes from IPFS Cluster (below). Without a resolver, a blob can only be
  resolved through the repository and files root that stored it.
- With `ipfsClusterEndpoint` set, every write (and rewrite) pins the CID through
  IPFS Cluster with `ipfsClusterReplicationMin`/`Max` and returns only once at
  least the minimum of peers report it `pinned`. Peers in `pin_error` or
  `cluster_error` do not count, so a write that cannot reach the minimum fails
  early instead of being acknowledged under-replicated; a pin in `pin_error` is
  recovered once before the write gives up. With a cluster configured, the
  DataStore's asynchronous upload staging is disabled so that no caller is
  acknowledged before replication. `IPFSClusterReplicationTest` and the
  consensus module's `ReplicatedBinaryClusterReadTest` show a binary surviving
  the loss of the IPFS node that stored it, after repository GC on the
  surviving nodes.
- Kubo and IPFS Cluster pins have no owner or reference count. Deleting a
  record therefore removes its pins only when nothing else can depend on them:
  without a cluster, the local pin of a blob stored through this repository;
  with a cluster, nothing unless `ipfsClusterUnpinOnDelete` is set. Otherwise
  DataStore GC removes the Oak mapping and reclaiming the pinned storage is an
  operator action on the cluster pinset.
- Without `ipfsClusterEndpoint`, pinning is local to the configured endpoint and
  there is no guarantee of a replica count.
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
- Content is plaintext unless `ipfsEncryptionKeys` is set (see
  [Encryption](#encryption)), and a Kubo node with default settings announces
  what it stores to the public IPFS network; startup warns about that (see
  [Private network](#private-network)). Use a private network as well as
  encryption for content that must stay private.

These are capability boundaries of an actively maintained product module, not a
claim of production readiness. See the
[promotion record](../docs/PRODUCT-PROMOTION.md) for evidence against the current
baseline and [Kubo documentation](https://docs.ipfs.tech/) for IPFS operations.

## License

[Apache License 2.0](../LICENSE.txt); retain [NOTICE.txt](../NOTICE.txt) when
redistributing. Somarc builds must not be represented as official Apache releases.
