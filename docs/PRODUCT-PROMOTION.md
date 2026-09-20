# Oak Segment Consensus Product Promotion

**Decision:** 2026-09-20 — maintain a permanent Somarc downstream product, following
Apache Oak through reviewed merges. The requested deliverable is a validated PR
for user review, **not** a merge, deployment, or release.

**Status:** reconciliation and product changes prepared; validation in progress.

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

Validation must reference the actual source under test. A snapshot suffix and a
successful HTTP response are not evidence by themselves.

| Gate | Current result |
|---|---|
| Input ancestry preservation | PASS — feature, fix, and Apache tips retained |
| XML/YAML and migration checks | PASS — parsed POMs/workflow; no stale DataStore imports; diff whitespace clean |
| Initial full reactor, JDK 21 / Java 17 target | 21 dependency modules PASS; consensus exposed the missing direct Metrics dependency; corrected candidate requires rerun |
| JDK 17 and JDK 21 GitHub checks | Pending PR |
| Fork module and shared-store regression tests | IPFS and Segment Tar PASS; consensus/coverage rerun pending after Metrics correction |
| Shaded artifact/dependency/provenance inspection | Pending package |
| Bounded three-validator candidate validation | Additional three-node topology and read-only cross-cluster checks approved; awaiting corrected package |
| Fault, destructive, cloud, and chain-backed campaigns | Not run; not claimed |

The initial disposable runtime attempt failed before Aeron startup: the direct
launcher selected a 16 KiB receive buffer against Aeron's 128 KiB initial window.
No writes were submitted, and the existing cluster remained unchanged. The
candidate now derives its receive default from the configured Aeron window and
cleans up a launcher whose initialization throws. Explicit overrides remain
validated by Aeron. Regression tests cover the defaults/overrides and preservation
of the original failure if cleanup also throws. The failed run's stores and logs
are retained; a retry uses a different disposable directory and the same deadline.

The first reactor run stopped in inherited `ForkJoinUtilsTest` with a one-worker
common pool created by a two-CPU JVM cap. Its parallel-stream/latch fixture expects
at least nine worker-processed items, but that setup processed eight. No test
assertions were changed or excluded. The bounded validation configuration now
keeps two common-pool workers explicitly; its outcome is recorded with the next
run rather than treating a retry as automatic success.

The second full reactor run passed all 21 dependency modules. Consensus ran
1,294 tests with eight errors, all `NoClassDefFoundError` for Metrics classes.
Oak and the Prometheus bridge declare Metrics as provided, so the standalone
module needs its direct dependency. That dependency is restored without a
version override; the corrected candidate must pass before runtime testing.

Live consensus campaigns follow the
[companion consensus test charter](https://github.com/somarc/oak-chain-infra/blob/main/modes/mock/validators/tests/CONSENSUS-TEST-CHARTER.md):
explicit runtime roots, read-only preflight, bounded operations, terminal-state
reconciliation, strict recursive logical equality, resource budgets, and retained
evidence. Physical HEADs/TAR layout are diagnostics, not equality requirements.
The user's existing cluster is not a test fixture to reset or replace implicitly.

## Before the user merges the PR

- Require the Java 17 and Java 21 product checks and review the runtime evidence.
- Require PRs for Somarc `trunk`; block force pushes and deletion.
- Permit merge commits. Do not require linear history or squash this promotion.
- Ensure no automation resets or mirrors Apache directly over the product branch.
- Merge into **somarc/jackrabbit-oak:trunk**, not Apache.
- Keep feature/fix refs until review and promotion are complete.

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
