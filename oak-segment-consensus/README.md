# Oak Segment Consensus

**Product:** the independently maintained Somarc validator runtime for Oak Chain.
**Delivery:** a standalone executable JAR and an OSGi bundle, built with this Oak fork.
**Maturity:** local validator behavior is testable; production and chain-backed
capabilities remain subject to explicit validation gates.

The runtime orders repository commands through Aeron Cluster. Each validator owns
an independent Oak Segment/TAR store and applies the ordered commands locally.
HTTP segment transfer supports read-only mounts; it is not a second write-consensus
protocol. IPFS binary storage is provided by `oak-blob-cloud-ipfs`.

This is a permanent downstream product based on Apache Jackrabbit Oak, not an
Apache feature awaiting acceptance. Its prototype history is retained, while
normal development targets Somarc `trunk`.

## Runtime contracts

- [Genesis v2 and replicated write safety](docs/GENESIS-AND-WRITE-SAFETY.md)
- [Product authority and supported scope](../docs/FORK-MAIN-CONTRACT.md)
- [Pinned upstream baseline and promotion evidence](../docs/PRODUCT-PROMOTION.md)
- [Upstream maintenance procedure](../docs/FORK-UPSTREAM-SYNC-RUNBOOK.md)

HTTP acceptance is not commitment. A write must reach an explainable terminal
outcome; `COMMITTED` is gated by the runtime's durability contract. Genesis is a
code-verified, reserved repository subtree. The genesis/write-safety document
specifies those invariants and the default-enabled safety setting.

Convergence means exact logical equality of nodes, property types, and values
after the asserted command range. Independent stores can have different physical
RecordIds, journal heads, and TAR layouts. Neither matching health responses nor
Aeron message ordering alone proves correct deterministic application.

## Build and test

From the repository root, using Maven 3.6.1+ and JDK 17 or 21:

```sh
mvn -B -ntp -pl oak-segment-consensus -am verify -DskipITs
```

This executes the module and dependency unit tests, packaging, license checks,
and bundle baselines. It does not run a live multi-validator campaign. Tests must
not be skipped to establish product readiness. A fresh build needs at least the
package lifecycle because `oak-shaded-guava` generates its relocated artifact
there.

Outputs:

- `target/oak-segment-consensus.jar`: executable standalone runtime;
- the versioned bundle and attached standalone artifact under `target/`.

The fork-owned module version is `2.7.0-somarc-SNAPSHOT`; its inherited Oak baseline
is `2.7-SNAPSHOT`. The IPFS module shares the Somarc version. This is a development
build, not an official Apache artifact or a published Somarc release.

### Build provenance

For an evidence-bearing build from a clean checkout:

```sh
test -z "$(git status --porcelain)"
mvn -B -ntp -pl oak-segment-consensus -am verify -DskipITs \
  -Doak.source.revision="$(git rev-parse HEAD)" -Doak.source.dirty=false
```

The standalone manifest records Somarc vendor/product version, the pinned Apache
SHA, the source SHA, and dirty state. Unstamped builds explicitly say `unrecorded`
and `unknown`; do not treat them as release provenance. Preserve the JAR SHA-256
alongside the test record. Never label a dirty build as clean.

The OSGi bundle depends on this fork's additional Segment Tar package exports.
It is not a drop-in bundle for an arbitrary stock Apache or AEM installation.
Export compatibility and actual bundle resolution require separate verification
from the standalone JVM launch.

## Running and safety

The entry point is
`org.apache.jackrabbit.oak.segment.consensus.server.GlobalStoreServer`.
Multi-validator launch configuration and lifecycle operations belong to the
companion `oak-chain-infra` repository; use an explicit runtime root, node IDs,
ports, and isolated Aeron directories. Do not assume a config pack describes an
already-running cluster, and never replace its JAR or run `--fresh` as part of a
source build.

Local mock mode is the development baseline. It simulates payment behavior and
is not evidence of on-chain verification. API token authentication is optional
and is **disabled when no token is configured**. Do not expose that default to
an untrusted network. Production deployment needs a reviewed security boundary,
not merely a change from `mock` to another mode.

For local-only HTTP/HTTPS listeners, set `-Dhttp.bind.host=127.0.0.1`. This
opt-in setting applies to every Jetty network connector; when absent, the
existing wildcard-bind behavior is unchanged. A loopback bind is useful for
isolated local validation but is not a substitute for production authentication.

The Aeron receive-buffer default follows `aeron.rcv.initial.window.length`.
If overriding `aeron.socket.so_rcvbuf`, keep it at least as large as that window;
inconsistent explicit values are rejected rather than silently clamped. The
companion Mac profile explicitly sets both to 16 KiB. No global kernel tuning
is required for the bounded validation profile.

### Validator-native interfaces

- `GET /v1/index`: discover the current route contract.
- `GET /health` and `/health/deep`: health observations, not correctness proofs.
- `GET /v1/consensus/status` and `/v1/aeron/cluster-state`: cluster observations.
- `GET /v1/config/osgi*`: effective configuration and its provenance.
- `POST /v1/propose-write` and `/v1/propose-delete`: proposal submission.
- `GET /v1/proposals/{id}/status`: operation lifecycle observations.
- `GET /v1/explorer/*`: repository exploration contracts.
- `GET /metrics`: Prometheus observations.

The embedded dashboard, explorer, and API browser are validator-local operator
surfaces. Consumers should use the governed API contracts rather than infer
safety from a dashboard badge.

## Validation boundaries

A product PR must distinguish:

1. unit and package verification;
2. bounded multi-validator logical-state and operation-history evidence;
3. restart, failover, and other fault evidence;
4. chain-backed and production evidence.

A pass in one category does not establish the others. The consensus test charter
requires read-only preflight, bounded traffic in a confirmed disposable namespace,
strict recursive logical comparisons, terminal operation reconciliation, resource
limits, and preserved evidence. Faults, destructive resets, and live-cluster
mutations require explicit approval.

Still subject to further validation/hardening are signed-intent/payment binding,
cloud storage and recovery, dynamic membership, cross-cluster deployments, and
full crash/recovery coverage. `oak-auth-web3` and `oak-segment-agentic` are deferred
sidecars, not dependencies of this product.

## License

[Apache License 2.0](../LICENSE.txt). Retain [NOTICE.txt](../NOTICE.txt) when
redistributing. Somarc's downstream runtime is not an official Apache release.
