# Somarc Oak Chain Downstream Contract

- **Status:** Downstream product direction approved 2026-09-20; initial promotion subject to PR review
- **Repository:** `somarc/jackrabbit-oak`
- **Upstream:** `apache/jackrabbit-oak`

## Project Identity

Oak Chain is an independently governed Somarc project built on Apache
Jackrabbit Oak. This repository is the Oak runtime distribution used by Oak
Chain. It carries the validator and consensus capabilities that Somarc owns and
operates while continuing to consume compatible Apache Oak development.

This is not a temporary staging fork for an Apache pull request. There is no
requirement or current plan to contribute `oak-segment-consensus` to Apache
Jackrabbit Oak. Apache acceptance is not an exit condition for the project.

The relationship is deliberately downstream:

- Apache governs Apache Jackrabbit Oak.
- Somarc governs Oak Chain and its downstream Oak distribution.
- Apache `trunk` remains the baseline authority for inherited Oak behavior.
- Somarc decides which Apache changes to integrate and owns the resulting
  downstream release.
- Somarc may contribute generally useful Oak fixes upstream, but those
  contributions are separate from the existence or legitimacy of Oak Chain.

Oak Chain must be described as an independent downstream project based on
Apache Jackrabbit Oak, not as an Apache feature, proposed Apache feature, or
Apache release.

## Independence Versus Maturity

Project status and capability maturity are different questions.

- **Project status:** Oak Chain is an active, independently governed project.
- **Runtime status:** the standalone validator baseline is established and is
  the fork-owned implementation surface.
- **Capability status:** individual paths may still be mock-only, incomplete,
  experimental, or subject to production-hardening gates.

An incomplete capability must be labeled honestly. It does not make the
project temporary. Conversely, independent governance does not make every
capability production-ready.

## Repository Authority

The intended repository authority is:

| Surface | Authority |
|---|---|
| Inherited Oak platform behavior | Apache upstream, as integrated by Somarc |
| Oak Chain validator protocol and runtime | Somarc |
| Fork-local packaging and Oak integration | Somarc |
| Validator-native APIs and operations | Somarc |
| Somarc builds, releases, and support claims | Somarc |
| Apache releases and Apache project direction | Apache |

Somarc is responsible for validating every upstream integration. A successful
Apache build or release does not by itself establish that the Oak Chain delta
remains correct.

## Canonical Branch Model

The target branch model is:

```text
apache/jackrabbit-oak:trunk
              |
              | reviewed upstream-sync merges
              v
somarc/jackrabbit-oak:trunk       canonical integration and release line
              |
              +-- feature/*      short-lived Somarc development
              +-- fix/*          short-lived Somarc fixes
              +-- release tags   immutable Somarc releases

somarc/jackrabbit-oak:upstream-trunk
              optional exact mirror of Apache trunk
```

The initial `promote/oak-segment-consensus` branch reconciles
`feature/blockchain-aem-poc`, `fix/three-node-genesis-readiness`, and the pinned
Apache baseline. These histories are retained, not rebased or squashed. After
reviewed promotion, normal work branches from and returns to Somarc `trunk`.
See [PRODUCT-PROMOTION.md](./PRODUCT-PROMOTION.md) for inputs and evidence.

The Somarc integration branch must not be force-pushed or reset to Apache
`trunk`. Upstream is merged into the downstream history; downstream commits are
not discarded to make the repository appear identical to Apache.

## Bounded Downstream Delta

The downstream delta must remain intentional and reviewable. The default
fork-owned surface is:

- `oak-segment-consensus/`
- `oak-blob-cloud-ipfs/`
- required integration changes in `oak-segment-tar/`
- required root build, packaging, CI, release, and operational documentation

Changes outside those surfaces require an explicit reason tied to the Oak
Chain runtime. Shared Oak touchpoints, including root POMs, package exports,
storage interfaces, and assembly configuration, must be recorded in the merge
or release evidence.

The following are excluded from the canonical runtime by default unless a
separate decision adopts them:

- `oak-auth-web3/`
- `oak-segment-agentic/`
- unrelated refactors of inherited Oak modules
- experiments that lack an owner, contract, and validation gate

Presence in branch history is not sufficient justification for inclusion in a
Somarc release.

## Upstream Integration Contract

Somarc follows Apache closely to retain security fixes, compatibility, and the
continued engineering value of the Oak platform. Following upstream means
reviewed integration, not downstream erasure.

Requirements:

1. Fetch Apache through the `upstream` remote or a verified mirror.
2. Integrate Apache `trunk` through an explicit sync branch and pull request.
3. Preserve the upstream merge commit and record the old and new Apache SHAs.
4. Review conflicts according to ownership; do not automatically choose the
   Apache or Somarc side.
5. Revalidate inherited Oak behavior and fork-owned validator behavior.
6. Record changes to shared touchpoints and any accepted downstream adaptation.
7. Never use a sync operation that offers to discard downstream commits.

Use [FORK-UPSTREAM-SYNC-RUNBOOK.md](./FORK-UPSTREAM-SYNC-RUNBOOK.md) for the
operational procedure.

## Development And Review Contract

After promotion, fork-local development uses short-lived branches and pull
requests into Somarc `trunk`.

Every material change must establish:

- which project-owned contract changes
- whether inherited Oak behavior is affected
- which runtime and compatibility tests provide evidence
- whether the change adds a permanent shared-Oak touchpoint
- whether documentation or operator expectations change

Changes to consensus, persistence, recovery, security, payment, or package
boundaries require review proportional to their runtime blast radius.

## Release Contract

Somarc releases are downstream releases, not modified Apache releases. Each
release must identify:

- the Somarc source SHA and immutable release tag
- the integrated Apache base SHA
- the fork-owned and shared-touchpoint delta
- build, test, and consensus evidence
- known mock-only, deferred, or production-hardening boundaries
- artifact and container digests when published
- upgrade and rollback expectations

The fork-owned consensus and IPFS modules use `2.7.0-somarc-SNAPSHOT`; their
Oak dependencies use the parent's `2.7-SNAPSHOT` version. The inherited reactor
is not renumbered. Bundle/standalone manifests identify Somarc and record the
upstream SHA, supplied source SHA, and dirty state. Unstamped values are explicit
(`unrecorded` / `unknown`), not provenance.

Maven deployment is disabled by default in `oak-parent`, and product CI only
verifies and retains test reports. This promotion does not publish a Maven
artifact, container, site, release tag, or deployment. Before any release, adopt
a reviewed distribution channel and distinguish every modified inherited
artifact, including Segment Tar, from official Apache coordinates; the Somarc
module suffix alone does not make a patched Apache-shaped dependency safe to
publish. Never publish the whole reactor under Apache-identical coordinates.

Apache license and notice requirements remain in force, and project language
must not imply Apache endorsement.

## Contribution Routing

Contributions to Oak Chain runtime behavior, Somarc packaging, or this
downstream distribution belong in `somarc/jackrabbit-oak`.

A generally useful change to inherited Oak may be proposed separately to
Apache under Apache's contribution process. Oak Chain work must not depend on
that proposal being accepted. If Somarc needs the change immediately, it may be
carried as a documented downstream patch while upstream consideration proceeds.

## Initial Product Promotion

Promotion into the Somarc default branch requires one reviewed integration
record containing:

1. the exact Somarc and Apache merge bases
2. an included and excluded path manifest
3. resolution of local-versus-remote feature-branch divergence
4. a list of permanent changes outside fork-owned modules
5. successful compile and focused module tests
6. bounded multi-validator consensus evidence
7. honest labels for mock, chain-backed, and production-hardening paths
8. corrected CI triggers and required branch checks for Somarc `trunk`
9. updated repository language that treats the branch name as transition
   history rather than project status

After promotion, freeze `feature/blockchain-aem-poc` as historical lineage.
Keep the source branches until the promotion is reviewed and merged; deleting
or archiving refs is a separate action. Do not retain it as a second long-lived
integration authority.

## Non-Goals

- Seeking Apache adoption of Oak Chain as a condition of success
- Claiming that Oak Chain is an Apache feature or release
- Keeping the Somarc tree byte-for-byte identical to Apache
- Allowing unrelated experiments to accumulate in the downstream runtime
- Treating mock-mode success as evidence of chain-backed or production safety

## Changing This Contract

Changes to project identity, branch authority, allowed fork scope, upstream
integration policy, or release authority require an explicit Somarc decision
and a reviewable update to this document. They must not emerge accidentally
from a merge conflict or branch rename.
