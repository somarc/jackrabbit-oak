Contributing
====

Thanks for choosing to contribute.

This repository is Somarc's independent Oak Chain distribution based on
Apache Jackrabbit Oak. Route a contribution according to the behavior it owns.

## Oak Chain And Somarc Distribution Changes

Changes to the following belong in `somarc/jackrabbit-oak`:

- `oak-segment-consensus`
- fork-local validator APIs and operations
- Oak Chain storage, payment, recovery, and consensus behavior
- required fork packaging and Apache Oak integration
- Somarc CI, release, and operational documentation

Create a short-lived branch from Somarc `trunk` and open a pull request back
to **somarc/jackrabbit-oak:trunk**, not Apache. Somarc-only work uses descriptive
branch and commit names; do not invent an Apache Jira issue. Explain the owned contract, affected inherited Oak surface,
validation performed, and any new permanent fork touchpoint.

See [docs/FORK-MAIN-CONTRACT.md](docs/FORK-MAIN-CONTRACT.md) for project
authority and [docs/FORK-UPSTREAM-SYNC-RUNBOOK.md](docs/FORK-UPSTREAM-SYNC-RUNBOOK.md)
for upstream integration.

## General Apache Oak Changes

A change that is useful to Apache Jackrabbit Oak independently of Oak Chain may
be proposed separately through Apache's contribution process:

<https://jackrabbit.apache.org/oak/docs/participating.html>

Apache contribution is optional and separate from Somarc project governance.
Do not make an Oak Chain fix depend on Apache accepting a proposal. If Somarc
needs the change, carry it as a documented downstream patch and track any
upstream proposal independently.

Contributors must preserve applicable Apache license headers and NOTICE
requirements. Project descriptions, artifacts, and releases must not imply
that Somarc's downstream changes are official Apache features or releases.

## Required validation and review

Run `mvn -B -ntp -pl oak-segment-consensus -am verify -DskipITs` from the
repository root and record results for the exact source under review. Run
additional affected-module tests as appropriate. Preserve existing assertions.
The Java 17 and Java 21 product checks are unit/package gates, not live-cluster
proof. Changes to consensus, persistence, recovery, or shared storage require
bounded runtime evidence under the consensus charter as well.

Upstream-sync and initial-promotion PRs must retain their merge commits: do not
squash or rebase them. The upstream SHA, retained shared-Oak adaptations, and
validation evidence belong in the PR. Request review from maintainers familiar
with the affected modules. Do not push directly or force-push the product line.

CI never deploys, publishes packages or containers, or creates releases. A
release needs a separate distribution/versioning and compatibility review.
