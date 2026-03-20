# Fork Upstream Sync Runbook

This fork tries to stay close to `apache/jackrabbit-oak` instead of drifting
into a permanently divergent codebase.

## When This Applies

Use this flow when a long-lived fork branch is behind `upstream/trunk` and has
substantial fork-local work on top.

This specifically applies to branches like `feature/blockchain-aem-poc` where
GitHub may show the branch as both ahead of and behind `apache/jackrabbit-oak`.

## What Not To Do

Do not use GitHub's `Sync fork` action on a long-lived feature branch when the
UI reports conflicts and offers to discard local commits.

If GitHub says it must "discard N commits" to match upstream, stop. That path
is destructive for the feature branch.

## Preferred Local Flow

1. Ensure the worktree is clean.
2. Fetch both remotes.
3. Fast-forward local `trunk` to `upstream/trunk`.
4. Create a safety branch from the feature branch before integrating upstream.
5. Merge updated `trunk` into the feature branch locally.
6. Resolve conflicts with context instead of using GitHub UI fallback actions.
7. Re-run focused verification for the active module surface.
8. Push the updated feature branch.

## Example Commands

```bash
git fetch origin --prune
git fetch upstream --prune

git checkout trunk
git merge --ff-only upstream/trunk

git checkout feature/blockchain-aem-poc
git branch codex/feature-blockchain-aem-poc-pre-upstream-merge-YYYYMMDD
git merge --no-ff trunk
```

After conflict resolution:

```bash
mvn -pl oak-segment-consensus -am -DskipTests compile
git commit
git push origin feature/blockchain-aem-poc
```

## Why Merge Instead Of Rebase

For a branch that is already hundreds of commits ahead of upstream, a merge is
usually safer than a rebase.

Reasons:

- preserves the branch's existing history
- reduces risk of replaying a large fork-local stack incorrectly
- makes upstream integration explicit in one merge commit
- is easier to reason about when the branch already contains multiple fork-only
  architectural changes

Rebase is still possible, but it is not the default for this fork shape.

## Conflict Pattern From 2026-03-20

When `feature/blockchain-aem-poc` was merged with current `upstream/trunk`, the
actual conflicts were limited to:

- `.gitignore`
- `oak-parent/pom.xml`

`oak-segment-tar/pom.xml` merged cleanly even though it had a fork-local export
change.

## Fork-Local Build Surface Rule

If a fork-local change touches shared build surface such as `pom.xml`, prefer
to sync upstream first and then make the change on top of the newer base.

If that is not possible and the change lands first, document the fork-local
reason inline in the file and in the commit message.

## Specific Fork Contract: `oak-segment-tar`

The fork exports additional `oak-segment-tar` packages because
`oak-segment-consensus` no longer attaches as a `Fragment-Host` and instead
imports segment and standby packages as a standalone bundle.

That export change is intentionally fork-local. It is not intended as an Apache
upstream change.

## Generated File Hygiene

Do not keep generated packaging artifacts tracked in git when they are not
source-of-truth files.

For `oak-segment-consensus`, this includes:

- `META-INF/MANIFEST.MF`
- `dependency-reduced-pom.xml`

If packaging generates them, prefer ignore rules and assembly exclusions over
committing the generated outputs.
