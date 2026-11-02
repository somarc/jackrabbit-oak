# Genesis and replicated write-safety contract

This is the code contract for the fixed three-member pre-production launch profile
(the local mock cluster started by
[`oak-chain-infra`](https://github.com/somarc/oak-chain-infra)), not a claim that
every production recovery or settlement gate has passed. Request-to-commit
behavior is described in [OPERATION-LIFECYCLE.md](OPERATION-LIFECYCLE.md).

## Canonical genesis v2

The reserved Oak namespace is
`/oak-chain/00/00/00/0x0000000000000000000000000000000000000000`.
Its `content/genesis` child is the code-authored network bootstrap contract. The
zero address names Oak content; it is not an EVM account whose private key is needed
for bootstrap. `DO IT LIVE!` remains the motto.

Genesis declares:

> Oak Chain turns enterprise content into shared, verifiable state. Aeron orders
> commands; Oak stores their meaning. Every validator must produce the same typed
> logical state. Acceptance is not commitment: commitment requires a verified
> durable quorum. Genesis is reserved, versioned, and verified; failures must remain
> explicit rather than being reported as success.

`CanonicalGenesisContent` is the single authoring implementation. Genesis v2:

- accepts only a `CREATE_GENESIS` trigger whose sole parameter is the leader's
  canonical sorted member URL set (`{"command":"CREATE_GENESIS","members":[...]}`),
  takes creation time from Aeron, and records the first URL of that set as the
  bootstrap member;
- formats dates in UTC, with JCR `NAME`, `DATE`, and `BINARY` property types;
- omits node-local gateway URLs, backend enablement flags, and BlobStore IDs;
- stores the bundled image through the NodeStore, with a pinned byte SHA-256;
- records `version=2.1.0`, `integrityFormat=oak-chain-genesis-v1`, and an
  `integrityDigest` over the entire zero-wallet subtree;
- publishes the content shapes at `genesis/shapes` (records, claims, attestations,
  assets, receipts). Each shape carries a typed example record; the shapes change
  only with a new genesis version.

The image remains the original 22,216-byte `genesis-assets/do-it-live.jpeg`:
`ed06f9cbf0fe878013ccb266170e6b3ba676933a6f065675cc0115c840bf1442`.
Oak may externalize the binary according to the configured store. Its content bytes,
not its storage reference, define logical identity. Independent binary availability
is a separate operational requirement.

The digest is SHA-256 over a length-framed stream: format identifier, relative node
paths, sorted property names, exact type tags, scalar/array flags, value counts and
values, and sorted children. Sorting uses Java's natural string ordering; strings
are UTF-8 with a four-byte length prefix. Binary values contribute their byte count
and SHA-256. Only `/content/genesis@integrityDigest` is excluded. Segment RecordIds,
TAR layout, and blob references are not part of the digest.

Verification checks the stored seal and independently re-authors the expected tree
on a detached memory builder, including the pinned image. It does not write blobs
or mutate the repository. Corrupt, incomplete, unsupported-version, or differently
authored content is rejected. The `genesisValidator` field denotes the configured
bootstrap endpoint: the lexicographically first URL in the common configured HTTP
member set. It is not an Aeron member-ID, ingress-caller, or elected-leader claim.
All members must have the same configured HTTP endpoint set. The leader puts its
set in the committed trigger and every member compares it with its own at apply:
on a mismatch the member fails stop (a member-local apply failure, see below)
instead of recording a different `genesisValidator`. A trigger with other fields,
or with an empty, duplicated or unsorted member list, is rejected identically on
every member. The parameter-free trigger written by earlier versions is still
accepted so existing logs replay unchanged; it derives the bootstrap member from
local configuration as before. The trigger is not part of genesis content: for the
same configuration and Aeron timestamp both forms author an identical tree and
`integrityDigest`, so the genesis version is unchanged. A member that replays the
genesis entry with a configured set different from the one committed (for example a
replacement validator with a new URL replaying from the start of the log) also
fails stop; restore it from a snapshot instead. The local three-validator promotion
campaign checks the member set alongside the genesis digest.
Configured self and peer URLs share a pure lexical canonicalizer (including the
`localhost`/`::1` loopback alias); public hostnames remain hostnames. DNS resolution
is not allowed to change the identity recorded in genesis.
A local digest is not a signature or an external
commitment: replacing both legitimate genesis proposal inputs and all derived state
requires an independently retained proposal commitment to detect adversarially.

There is no implicit v1-to-v2 migration. Disposable pre-production stores can be
reseeded deliberately. A deployed network would require an explicit migration plan.

## Mutation and failure boundaries

Ordinary registration, writes, deletes, binary admission, queue admission, and
replicated write/delete application reject the zero wallet. Mutation-path checks
also reject the zero-wallet subtree and its ancestors. Internal replicated genesis
creation is the explicit bootstrap exception.

Standalone replicated writes carry Aeron's command timestamp through immutable
`MutationAuditMetadata.appliedAt`. It is never accepted from ingress JSON. Content
`timestamp`, wallet `walletCreated`, and wallet `lastWrite` use that one value.
Legacy direct-call helpers retain a local-clock fallback; they are not the
standalone replicated ingress path.

A replicated write that carries a stored binary also records its blob identifier,
CID and length under the hidden `/:oak-chain-binaries/<aa>/<bb>/<sha-256>` index.
The entry is derived only from the proposal, so every member writes the same one;
the first entry for an identifier is kept. Node-local gateway URLs are not part of
this index. Each apply that stores or references an indexed binary also stamps the
entry with the entry's log position and Aeron timestamp (`refPosition`, `refTime`);
a replicated `BINARY_RELEASE` removes only entries last referenced before its mark
(see [GC-AND-PAID-DELETES.md](GC-AND-PAID-DELETES.md), section 4.4).

Aeron's committed log is the apply authority. The dispatcher never filters committed
commands using a locally synthesized HTTP leadership term: the term it compares is
Aeron's `leadershipTermId`, applied in log order from `onNewLeadershipTermEvent`, so
every member reaches the same decision. Malformed/stale input rejection
(`MutationRejectedException`) remains distinct from a member-local apply failure.

A replicated binary write on a member without a BlobStore is such a member-local
failure: it is not rewritten as a STRING `jcr:data` reference.

A valid committed write/delete/genesis that cannot be applied latches a quarantine
and terminates the Aeron clustered-service agent with a FATAL cause, which takes the
fail-stop path (crash record, Aeron shutdown, then the configured process-exit
callback). Later callbacks cannot continue applying commands. Health reports failure
and ordinary HTTP routes return 503 until restart and repair. Startup is not
write-ready until canonical genesis is verified.

Every replicated write, delete, batch item, genesis, charter, directory update and
binary release records its applied-log watermark (Aeron log position, batch item, and term) under the hidden root node
`:consensus` in the same Oak merge as the mutation. Replay skips entries at or below
the watermark, so a restarted member re-applies only what its store does not yet hold;
an entry that failed on a member is above its watermark and is applied again after
restart. Startup refuses an Aeron log or snapshot that does not belong to the store.
The watermark is outside the zero-wallet subtree and is not part of the genesis digest.

The same merge records the client `proposalId` of each applied write or delete under
`:consensus/proposals/<id[2..4]>/<id[4..6]>/<id>` (`kind`, `path`, `appliedAt` from the
log timestamp). Apply checks this index in log order: a proposal id that is already
there mutates nothing and is acknowledged as a duplicate, so one paid proposal has one
effect on every member. A delete whose target is absent still records its id. Entries
are never removed. The index travels with the store like any other node and is
rebuilt identically by replay; stores and logs written before it load unchanged, and
their resends at the same path are still caught by the existing `oak:proposalId`
check. History applied by an older build has no index entries, so replaying it from
an empty store with this build would apply a different-path resend once instead of
twice; members whose watermark already covers that history are unaffected.

## Durability acknowledgement

`202 Accepted` means queue admission only. Poll
`/v1/ops/operations/{proposalId}`; success is `state=COMMITTED`, corresponding to
`sourceState=PROCESSED` plus `durabilityState=ACKED`.

Every local durability vote must follow a successful FileStore flush covering that
application. A repeated write, an already absent delete target, a proposal id already
in the applied-proposal index, or an existing `oak:proposalId` does not prove disk
persistence: those paths request a flush too.
Flush callbacks are assigned to a captured cohort. Work registered while a flush is
in progress waits for a subsequent flush. Failed flushes retain callbacks for retry
and emit no durability-success vote. Callback HEADs are captured before the flush,
not sampled from potentially newer state when the callback runs.

## Startup feature gate

The startup gate `oak.consensus.safety.enabled` defaults to true. The explicit value
`false` prevents the validator from starting, because genesis v2 requires these
invariants on every member; a misspelling does not disable safety.
It is read at startup, not used to switch replicated semantics while a member is
running. Clock, durability, reservation, integrity, and failure invariants are
unconditional at apply, so mixed or changing node-local settings cannot restore an
unsafe state machine. This is a deployment gate, not a v1 migration or unsafe
behavior rollback switch.

## Regression and release gates

The module tests cover failed-flush write/delete replay, command-derived clocks,
flush-cohort races, genesis corruption and re-sealing, native Segment store reopen,
zero-wallet HTTP/apply rejection, and apply-failure quarantine. Existing nominal
and protocol assertions remain part of the module suite.

These changes do not claim to finish cluster-wide proposal-ID/intent idempotency,
leader-transfer of the proposal ledger, transaction/GC correctness, complete
snapshot restoration, independent IPFS durability, or production signed-intent and
EVM settlement. Keep those release gates explicit. A three-member deployment still
needs tested leader/follower failure, quorum loss, preserved-store restart,
replacement-member restore, and bounded soak evidence.
