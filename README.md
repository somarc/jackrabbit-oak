Oak Segment Consensus
=====================

**Somarc's independently maintained Oak Chain runtime, based on
[Apache Jackrabbit Oak](https://github.com/apache/jackrabbit-oak).**

This repository is a permanent downstream product, not a staging branch for an
Apache contribution. Apache remains the authority for inherited Oak platform
behavior; Somarc owns the validator, its Oak integration, validation, and releases.
We continue to integrate Apache development through reviewed, ancestry-preserving
merges into the Somarc product line.

## Product scope

- [`oak-segment-consensus`](oak-segment-consensus/README.md): a standalone validator
  that orders repository commands through Aeron Cluster and applies them to local
  Oak Segment/TAR stores.
- [`oak-blob-cloud-ipfs`](oak-blob-cloud-ipfs/README.md): the optional IPFS-backed
  binary storage integration.
- Required Segment Tar, build, and packaging adaptations, kept explicit and small.

The product's permanent status is separate from capability maturity. Local
mock-mode validation does not prove chain-backed payments, production security,
cloud recovery, or every failure/recovery scenario. Logical repository equality
is the convergence invariant; physical RecordIds and TAR files need not match.

The canonical product branch is Somarc `trunk`. `feature/*` and `fix/*` branches
return to that branch, while `sync/apache-*` branches carry reviewed upstream
updates. The original `feature/blockchain-aem-poc` is historical development
lineage, not a second product mainline. The initial promotion is recorded in the
[promotion record](docs/PRODUCT-PROMOTION.md).

## Build and validate

Use Maven 3.6.1+ and a JDK capable of building the Java 17 target. Product CI checks
JDK 17 and 21. From the repository root:

```sh
mvn -B -ntp -pl oak-segment-consensus -am verify -DskipITs
```

This runs unit tests, builds both fork modules and their Oak dependencies, and
checks bundle baselines and licenses. It is not a live three-validator campaign.
Use the package/verify lifecycle: `oak-shaded-guava` creates its relocated artifact
during packaging, so a clean reactor cannot be validated by `compile` alone.

The standalone runtime is built at
`oak-segment-consensus/target/oak-segment-consensus.jar`. Both fork-owned modules
use the distinct `2.7.0-somarc-SNAPSHOT` development version; inherited Oak modules
retain the pinned Apache `2.7-SNAPSHOT` baseline. No release is implied. Maven
deployment is disabled by default, and CI neither publishes packages nor deploys
validators. See the [release boundary](docs/FORK-MAIN-CONTRACT.md#release-contract).

## Contracts and contribution

- [Product governance and scope](docs/FORK-MAIN-CONTRACT.md)
- [Upstream integration runbook](docs/FORK-UPSTREAM-SYNC-RUNBOOK.md)
- [Genesis v2 and replicated write safety](oak-segment-consensus/docs/GENESIS-AND-WRITE-SAFETY.md)
- [Contributing to the Somarc product](CONTRIBUTING.md)
- [Inherited Apache Oak documentation](https://jackrabbit.apache.org/oak/docs/)

Do not use a fork-sync operation that discards downstream commits. Apache intake
is a compatibility change with its own validation, not a reset to Apache's tree.

## Apache foundation and license

Apache Jackrabbit Oak is a scalable, high-performance hierarchical content
repository developed by the Apache Jackrabbit project. This Somarc distribution
is not an official Apache release and does not imply Apache endorsement.

See [LICENSE.txt](LICENSE.txt) and [NOTICE.txt](NOTICE.txt).

Collective work: Copyright 2014 The Apache Software Foundation.

Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements. See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to You under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License. You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
