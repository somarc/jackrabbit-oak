## Product change

Describe the Somarc-owned contract and why this change is needed.
Target: **somarc/jackrabbit-oak:trunk**, not Apache.

## Scope and provenance

- Source SHA:
- Integrated Apache SHA:
- Fork-owned modules affected:
- Shared Oak touchpoints changed and why:

For upstream intake, record the previous/new Apache SHAs, conflicts, and retained
adaptations. **Preserve merge commits for sync and promotion PRs; do not squash or
rebase them.**

## Validation

- [ ] Java 17 and Java 21 unit/package checks pass.
- [ ] Affected modules were tested without weakening assertions.
- [ ] Package/baseline/license results and known skips are recorded.
- [ ] Consensus/storage changes include bounded runtime evidence, or are explicitly blocked pending it.

Runtime evidence must identify the tested source/JAR, confirmed runtime root,
operation outcomes, strict logical-state comparisons, resource bounds, and known
limitations. Physical RecordIds/TAR layouts are separate diagnostics. Unit tests,
HTTP 202, and health responses are not substitutes for consensus proof.

## Release and safety boundary

- [ ] No deployment, package/container publication, or release is part of this PR.
- [ ] Mock-mode results are not described as chain-backed or production proof.
- [ ] Upgrade, rollback, and operator-visible changes are described where applicable.
