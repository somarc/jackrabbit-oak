# Oak Segment Consensus Product Promotion

**Decision:** 2026-09-20 — maintain a permanent Somarc downstream product, following
Apache Oak through reviewed merges. The requested deliverable is a validated PR
for user review, **not** a merge, deployment, or release.

**Status:** local candidate validation complete. Promotion remains subject to the
latest checks and user review on [Somarc PR #1](https://github.com/somarc/jackrabbit-oak/pull/1);
no mainline merge, deployment, or release has been performed.

## Pinned inputs and preserved history

| Input | Commit |
|---|---|
| Published `feature/blockchain-aem-poc` | `ce9a0337be7c9b7730ad618b5fb6457c62dca806` |
| Published `fix/three-node-genesis-readiness` | `e32e1c45ef5983193e9fde33bfeaf31696e37fda` |
| Apache `trunk` and Somarc `trunk` at intake | `9595d3fde56a1e8e6f75afafc487bde947659bdd` |
| Last Apache baseline inherited by the fix branch | `aca22d07dd461e3210ea94ece7d156c682f5f6fe` |

The published feature and fix branches diverged. The feature side contained the
later Apache merge; the fix side contained `bf10af8c76` and `e32e1c45ef`. Neither
published branch alone represented the complete promotion input.

The isolated `promote/oak-segment-consensus` branch preserves both sides:

1. `7f2225f564012e23fc248bb481f61d3b8ca6b73d` merges the published feature history
   into the genesis-fix lineage.
2. `20e80474360da95108d2411b19feb5f3a07e1143` merges exact Apache `9595d3f` into
   that reconciliation.

All three input tips were verified as ancestors of the result. The only conflict
in the current Apache intake was `.gitignore`: both generated-file rules were
retained and the malformed existing source-fixture JAR exception was corrected.
No shared branch was rebased, reset, or force-pushed.

The original working checkout and its six pre-existing edited/untracked README
and policy files were left untouched and separately preserved with checksums.
The approved policy drafts were adapted in the isolated promotion worktree;
historical prototype findings were not silently promoted into current evidence.

## Admitted product scope

- `oak-segment-consensus/`: standalone validator and its tests/resources.
- `oak-blob-cloud-ipfs/`: IPFS binary backend and its tests.
- Required integration under `oak-segment-tar/`, root/parent POMs, assembly,
  CI, and repository documentation.

Deferred `oak-auth-web3`, `oak-segment-agentic`, and prototype oak-run commands
remain excluded. Their historical commits do not make them part of the product.

### Shared Oak adaptations

| Surface | Downstream reason |
|---|---|
| Root POM | Include consensus and IPFS in the reactor |
| Parent POM | Downstream build/provenance policy, deployment off by default, existing coverage/documentation configuration |
| Segment Tar POM | Explicit segment/file/TAR/standby exports and EventAdmin dependency for fork-local integration |
| `SegmentNodeStoreRegistrar` | Read-only composite-mount registration; preserve upstream cache-toggle registration and cleanup on both store paths |
| `HttpSegmentStoreSync` | Retained historical OSGi HTTP-sync helper; not the standalone consensus write protocol |
| Assembly and ignore rules | Keep generated consensus packaging outputs out of source distributions |
| GitHub workflows | Non-publishing Somarc validation, not Apache release or prototype deployment automation |

The extra Segment Tar exports are a fork-private compatibility seam. They do not
constitute a stable public Apache API. The retained HTTP-sync helper and complete
OSGi deployment remain separate review surfaces; standalone validation does not
establish their production readiness.

## Compatibility and identity changes

- Align both fork module parents to Apache `2.7-SNAPSHOT`.
- Give the two fork-owned modules the distinct `2.7.0-somarc-SNAPSHOT` version;
  resolve inherited Oak dependencies through `${oak.version}`, not the product
  version. Consensus resolves IPFS at the shared Somarc version.
- Migrate the IPFS DataStore implementation/tests and genesis blob test to
  `org.apache.jackrabbit.oak.spi.blob.data`; remove obsolete direct Jackrabbit
  DataStore dependencies.
- Use the Apache-managed Netty, Commons Lang, Metrics, and Testcontainers baseline
  rather than carrying conflicting legacy pins. Keep Aeron, Jetty, Web3j, and
  Prometheus as explicitly product-owned dependencies.
- Inherit the Java 17 minimum instead of the stale Java 11/AEM enforcer override.
- Restore both upstream cache feature toggles on read-only composite mounts, with
  service/factory registration and deactivation regression tests.
- Make the consensus bundle explicitly implementation-only rather than declaring
  nonexistent exported packages; do not accidentally export all implementation
  packages through BND defaults.
- Identify Somarc in fork bundle metadata and stamp source/upstream/dirty state
  into the executable manifest. Merge Java service-loader resources when shading;
  retain Metrics explicitly at the managed version because Oak declares it provided.
- Add an opt-in `http.bind.host` setting, tested for configured and unchanged-default
  behavior, so the isolated validation can bind all Jetty listeners to loopback.
- Remove inert ASF repository/notification metadata and Apache-only commit checks;
  they are not the governance or release authority for this downstream.

These changes do not claim a storage-format migration or promise rollback
compatibility. Do not replace a running validator JAR or reset existing stores as
part of this promotion. Upgrades, genesis compatibility, and rollback need an
explicit operator plan and evidence for the affected stores.

## Validation record

The runtime implementation tested below is
`5ddd26348169ef41878922f2a9aac0a53bc76b67`. Subsequent evidence-only documentation
commits do not change that Java/POM implementation. Check the latest PR commit's
Java 17/21 results before merging; older green runs do not approve a newer head.

| Gate | Recorded result |
|---|---|
| Input ancestry | PASS — published feature, fix, and pinned Apache tips retained |
| XML/YAML, migrated API, and diff checks | PASS |
| Initial complete dependency reactor | All 21 dependency modules PASS; the discovered standalone Metrics omission was corrected and retested |
| Consensus suite on the corrected implementation | 1,298 tests, zero failures/errors |
| IPFS suite | 58 tests, zero failures/errors |
| Segment Tar suite | 822 tests, zero failures/errors, one existing skip; includes writable/read-only cache-toggle lifecycle regressions |
| Added executable-line coverage | 19/22 lines, **86.36%**, against reconciliation commit `20e8047436` |
| Package and license checks | PASS; zero unapproved RAT files; no blanket RAT or baseline skip |
| Bundle baseline | Inherited baseline checks PASS; the new fork artifacts have no previous published version to compare |
| Standalone contents/provenance | Somarc version/vendor and source/upstream/dirty metadata verified; Metrics present; legacy DataStore classes absent; Netty 4.1 family aligned at 4.1.136.Final |
| Final bounded three-node campaign | PASS — 39 assertions, 115.19 seconds |
| GitHub Java 17/21 | Required on the latest [PR head](https://github.com/somarc/jackrabbit-oak/pull/1/checks); both initial-candidate checks passed |

Tested standalone JAR SHA-256:

```text
60925397be9701456efb46699409471a1dc36a087e317940f3ff42fb609ec573
```

Its manifest records source `5ddd263481...`, `Oak-Source-Dirty=false`, and Apache
`9595d3fde5...`. The distribution is still an unpublished development snapshot.

### Final bounded runtime evidence

The additional test topology used HTTP `18090/18092/18094`, Aeron base `19000`,
loopback-only HTTP connectors, local TAR stores, and its own offline IPFS daemon
on API `15001`. The final disposable root was
`target/promotion-local3-20260920-04`, separate from the existing cluster.

- One leader, quorum two, and reachability 3/3 were established.
- Local shard prefixes were `00,80`; `ef` was a static read-only mount of
  `http://127.0.0.1:8090`.
- All new nodes matched on **46 genesis nodes, 275 typed properties, and one
  binary checksum**. The strict digest remained unchanged after the rejected
  zero-wallet write:
  `39ebca2421bd11b40e45414b0453c713ab5add9bf80dafc8b9b2eb0476783fe4`.
- The existing node and all three new nodes matched on the mounted witness:
  **six nodes and 35 typed properties**, digest
  `943e1967ff831bc877d2e54aec8003ef11ecd4962a2597cb4258e8bc9c3f2be8`.
  The scoped explorer handler reads the local CompositeNodeStore; this was not
  replaced by an explorer-to-explorer HTTP proxy. Startup/transport evidence
  also identifies the lazy NodeStore and HTTP segment persistence.
- Three distinct proposals reached leader-observed `COMMITTED` / `ACKED`.
  After each, the full typed wallet subtree converged across all three new
  validators, and each expected message payload was checked on every replica.
- The new wallet root was absent at existing endpoint `8090` (HTTP 404).
- The zero-wallet write returned exact HTTP 403 and
  `genesis_namespace_reserved`; the full genesis digest remained unchanged.
- Final queues were empty and backpressure inactive. The write helper's
  postflight health/queue records were complete, with no `postflightError`.
- All test validators performed SIGTERM-triggered orderly shutdown without
  SIGKILL; the disposable IPFS daemon exited normally. Test stores were
  retained rather than deleted.
- The existing validators retained their PIDs, start metadata, configured store
  paths, and `UP` status. No protected-cluster write was issued. This is bounded
  protection evidence, not a full before/after digest of all existing content.

Sampled resource peaks for the additional topology were approximately 991 MiB
RSS, 25.1% CPU, and 158 MiB allocated disk. These are sampled observations, not
host-independent performance limits or throughput benchmarks.

### Initial failures and corrections are retained

1. A two-CPU test JVM created a one-worker common pool, which did not satisfy
   the inherited parallel-stream fixture. Explicitly supplying two workers
   preserved the CPU budget and all existing assertions.
2. Removing a provided-scope Metrics dependency exposed eight missing-class
   errors. Restoring the direct, parent-managed dependency fixed them.
3. RAT identified three existing UI resources without headers; headers were
   added instead of excluding the resources.
4. The first disposable launch used a 16 KiB receive buffer against a 128 KiB
   initial window. The receive default now follows Aeron's configured window;
   explicit overrides remain validated. Failed launch cleanup now runs and
   preserves the original exception if cleanup also fails.
5. Runtime-02 exposed a harness scope error: the local API correctly rejected
   genesis outside the declared `80` prefix. Runtime-03 used the correct
   `00,80` local and `ef` remote scopes, but stopped on a stale HTTP 400
   expectation. The contract is HTTP 403. Runtime-04 required that exact status
   and error code and reran the entire campaign, including post-rejection
   genesis and final queue checks. Earlier runs are not retroactively called
   successful.

### Explicit limits

- The mount is **static, one-way new → existing**. This does not establish
  automatic discovery, shared cross-cluster identity, bidirectional mounts, or
  a single cross-cluster consensus domain.
- `COMMITTED` / `ACKED` was observed on the leader. Follower operation endpoints
  returned 404 while their repository contents converged; replicated operation
  records are not claimed.
- ACK evidence is in-run. Crash durability, restart recovery, quorum-loss,
  partitions, cloud backends, chain-backed payments, and production readiness
  were not tested or claimed.
- The new validators shared one disposable offline IPFS service. Binary reads
  through all three views do not establish independent IPFS replication.
- Two followers logged an initial unavailable lazy-ingress client for the first
  durability vote, entered the designed retry path, and recovered before ACK.
  The runtime logs are not described as error-free.
- The preserved write helper has a future false-green risk if postflight errors
  are merely recorded, and the wrapper records cleanup outcomes separately.
  Independent audit verified no postflight error, complete health/queue evidence,
  and no forced cleanup in runtime-04. Those conditions must remain hard gates
  when reusing the harness; a generic helper exit code alone is insufficient.

Campaigns follow the
[companion consensus test charter](https://github.com/somarc/oak-chain-infra/blob/main/modes/mock/validators/tests/CONSENSUS-TEST-CHARTER.md).
The evidence pack retains failed and successful runs, commands, source/JAR
provenance, typed exports, operation histories, resource samples, and cleanup.
Private keystores and IPFS repository configuration are not part of the pack.

## Before the user merges the PR

- Require the Java 17 and Java 21 product checks and review the runtime evidence.
- Require PRs for Somarc `trunk`; block force pushes and deletion.
- Permit merge commits. Do not require linear history or squash this promotion.
- Ensure no automation resets or mirrors Apache directly over the product branch.
- Merge into **somarc/jackrabbit-oak:trunk**, not Apache.
- Keep feature/fix refs until review and promotion are complete.
- After promotion, make local product branches track `origin/trunk`, not
  `upstream/trunk`; use the latter only as the Apache intake reference.

Repository protection/settings are not changed by this PR. After merge, `trunk`
is the single product authority and future Apache intake follows the
[upstream runbook](FORK-UPSTREAM-SYNC-RUNBOOK.md).

## Release boundary

No Maven publication, container push, site publication, release tag, cluster
restart, or deployment is authorized by this promotion PR. Deployment is disabled
by default in the parent, and CI retains only diagnostic reports. Before any
release, qualify modified inherited artifacts as well as the product modules,
adopt a distribution channel, and record tested upgrade/rollback expectations.
See the [release contract](FORK-MAIN-CONTRACT.md#release-contract).
