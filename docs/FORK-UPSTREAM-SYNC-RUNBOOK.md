# Fork Upstream Sync Runbook

> **Current baseline:** See [PRODUCT-PROMOTION.md](./PRODUCT-PROMOTION.md)
> for the initial promotion inputs and validation. Historical prototype reviews
> are not substitutes for evidence against the current source.

This runbook implements the
[Somarc Oak Chain Downstream Contract](./FORK-MAIN-CONTRACT.md).

Oak Chain is an independent Somarc project based on Apache Jackrabbit Oak.
Following Apache is a deliberate compatibility and maintenance practice, not a
plan to submit the Oak Chain runtime to Apache and not permission to erase the
Somarc delta.

## Remote And Branch Meaning

In the standard Somarc clone:

| Ref | Meaning |
|---|---|
| `origin` | `somarc/jackrabbit-oak`, the Somarc project repository |
| `upstream` | `apache/jackrabbit-oak`, the Apache source repository |
| `upstream/trunk` | latest fetched Apache baseline |
| `origin/trunk` | target Somarc integration and release line |
| `origin/upstream-trunk` | optional verified mirror of Apache `trunk` |
| `promote/oak-segment-consensus` | initial reviewed reconciliation of feature, fix, and Apache histories |
| `feature/blockchain-aem-poc` | historical development lineage, not the permanent integration line |

After promotion, Somarc `trunk` intentionally contains both Apache history and
Somarc-owned changes. It must not be fast-forwarded or reset to Apache
`upstream/trunk`.

## Operating Principles

1. Preserve downstream history.
2. Merge Apache into Somarc; do not rebase the established downstream line.
3. Integrate through a dedicated sync branch and reviewed pull request.
   Preserve the upstream merge commits when merging the PR; never squash or
   rebase an upstream-sync or initial-promotion PR.
4. Resolve conflicts according to project ownership and runtime invariants.
5. Validate both inherited Oak behavior and the Oak Chain runtime.
6. Record the upstream SHAs, shared touchpoints, conflicts, and evidence.
7. Never accept a sync operation that discards Somarc commits.

## Preconditions

Before any sync:

- use a clean, isolated worktree
- confirm no local or remote branch divergence is unexplained
- fetch both repositories or use a verified Apache mirror
- identify the last integrated Apache SHA
- identify changes to known shared touchpoints
- create a safety ref before resolving a large or unusual integration

If `git status --branch` reports that the local branch is both ahead of and
behind its tracked Somarc branch, stop. Reconcile that divergence first in a
separate reviewed operation. Do not hide it with a force push, blind pull, or
rebase.

## Canonical Flow After Fork-Trunk Promotion

Fetch both authorities:

```bash
git fetch origin --prune
git fetch upstream --prune
```

If direct Apache access is unavailable, update and verify the Somarc
`upstream-trunk` mirror first, then substitute `origin/upstream-trunk` for
`upstream/trunk` below.

Start from the current Somarc integration line:

```bash
git switch trunk
git pull --ff-only origin trunk
git switch -c sync/apache-trunk-YYYYMMDD
git branch safety/trunk-pre-apache-sync-YYYYMMDD
```

Capture the inputs before merging:

```bash
git rev-parse HEAD
git rev-parse upstream/trunk
git log --oneline --no-merges HEAD..upstream/trunk
git diff --name-status HEAD...upstream/trunk
```

Merge Apache explicitly:

```bash
git merge --no-ff upstream/trunk
```

Resolve conflicts with context. For every conflict, decide whether the file is:

- Apache-owned inherited behavior
- Somarc-owned Oak Chain behavior
- a shared touchpoint that must satisfy both

Do not mechanically choose `ours` or `theirs` across the tree.

After resolving the merge, align both fork module parents to the actual Oak
parent version, update `oak-parent`'s `oak.upstream.revision` to the verified
Apache SHA, and review the Somarc module versions. Their `${oak.version}`
dependencies must resolve to the inherited reactor; consensus-to-IPFS must
resolve to the Somarc version. Verify the resulting runtime manifests.

After validation, push the sync branch and open a pull request into Somarc
`trunk`:

```bash
git push -u origin sync/apache-trunk-YYYYMMDD
```

The pull request is the integration record. Somarc `trunk` moves only after
review and required checks pass.

## Initial Product Promotion

`promote/oak-segment-consensus` is the initial reconciliation branch. It preserves
both published feature/fix histories and integrates the exact Apache baseline
recorded in [PRODUCT-PROMOTION.md](./PRODUCT-PROMOTION.md). Do not restart routine
integration on the old prototype branch or assume the newest fix branch contains
every upstream merge.

Keep the feature and fix refs intact until the promotion PR is reviewed. The
initial PR targets Somarc `trunk` and must retain both reconciliation merge
commits. Publishing that PR does not authorize merging it, replacing a running
validator JAR, changing repository protection, or publishing a release.

After promotion, all Apache syncs target Somarc `trunk`; normal development uses
short-lived branches. If repository settings still mirror Apache into Somarc
`trunk`, disable that automation before the product PR is merged.

## Why Merge Instead Of Rebase

The Somarc distribution is a long-lived downstream with established upstream
merge points and substantial fork-owned history. Merge commits:

- preserve the provenance of both authorities
- make each Apache integration point explicit
- avoid replaying a large downstream stack
- provide an auditable conflict-resolution boundary
- avoid force-pushing the canonical Somarc history

Rebase is appropriate for an unpublished short-lived work branch. It is not the
default for the downstream integration line or the current promotion branch.

## Conflict Ownership

Known shared touchpoints include:

- root and parent POMs
- `oak-segment-tar` package exports and integration classes
- assembly and OSGi packaging
- storage interfaces used by the validator
- repository-wide dependency and Java-version changes

For a shared touchpoint, preserve Apache's current baseline while retaining the
smallest explicit Somarc adaptation required by Oak Chain. Record why the delta
still exists. If it is no longer required, remove it deliberately and test the
result rather than preserving it by habit.

The current `oak-segment-tar` exports are fork-local because
`oak-segment-consensus` runs as a standalone bundle and imports segment and
standby packages instead of attaching as a fragment host. That is an intended
Somarc integration delta, not a proposed Apache change.

## Validation

At minimum, a sync record includes:

```bash
mvn -B -ntp -pl oak-segment-consensus -am verify -DskipITs \
  -Dskip.deployment=true \
  -Doak.source.revision="$(git rev-parse HEAD)" \
  -Doak.source.dirty=false
```

The provenance flags above require a clean checkout. For an uncommitted
compatibility iteration, record dirty state honestly and retain the patch.
Use `verify`, not bare `compile`: the relocated `oak-shaded-guava` dependency
is created during packaging. This gate runs unit tests and package/baseline
checks, not the multi-validator campaign. Do not use `-DskipTests`, RAT skips,
or blanket baseline skips to claim a validated sync.

On constrained runners, the inherited `ForkJoinUtilsTest` needs at least two
common-pool workers. CI keeps the two-CPU budget and explicitly uses
`-Djava.util.concurrent.ForkJoinPool.common.parallelism=2` in the test JVM.
Do not relax its assertions to accommodate a one-worker common pool.

Also run tests for every inherited Oak module changed during conflict
resolution. Changes to consensus, persistence, recovery, package exports, or
shared storage behavior require bounded multi-validator evidence under the Oak
consensus test charter, not only a green module build.

The pull request must distinguish:

- logical API agreement
- physical repository diagnostics (not byte-identity requirements)
- mock-mode evidence
- chain-backed evidence
- deferred production-hardening claims

## Sync Record Template

Every sync pull request should state:

```text
Previous integrated Apache SHA:
New Apache SHA:
Apache commit range:
Updated oak-parent oak.upstream.revision:
Fork module versions and Oak dependency alignment:

Fork-owned modules affected:
Shared touchpoints affected:
Conflicts resolved:
Downstream adaptations retained/removed:

Compile evidence:
Focused test evidence:
Consensus evidence:
Known limitations or deferred follow-up:
```

## Cadence

- weekly while Oak Chain runtime development is active
- within 48 hours for relevant security or critical dependency changes
- before modifying shared build or package-export surfaces
- before every Somarc release
- promptly when Apache changes a known shared touchpoint

A missed cadence is visible maintenance debt. It is not resolved by resetting
the fork to Apache.

## Generated File Hygiene

Do not track generated packaging artifacts that are not source-of-truth files.
For `oak-segment-consensus`, this includes:

- `META-INF/MANIFEST.MF`
- `dependency-reduced-pom.xml`

Prefer ignore rules and assembly exclusions over committing generated outputs.
