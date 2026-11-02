# Garbage collection and paid deletes

Status: direction decided; phase 1 implemented on `feature/consensus-gc-safety`. Written against
`trunk` at `42fb205ece`. Evidence comes from the [compaction and paid-deletes review](#evidence) and
was re-checked on this branch.

Decided by the owner: GC is fully decoupled from Ethereum; writes and deletes stay paid through the
ETH contract; Oak's own GC mechanisms run unabated; pricing gets a separate rethink. That settles D1
and D2 below and defers D4 to the pricing rethink.

Update: D1 is decided and section 4.1 is implemented (phase 1a). Section 1 describes the code at
`42fb205ece`, before that change. D3 is decided and binary GC (section 4.4) is implemented on
`feature/binary-gc`.

This note explains how deletes, garbage and GC work today, then proposes a simpler model: validators
run GC as routine maintenance, and only writes and deletes are paid on chain. Section 6 lists the
decisions needed before code.

## 1. How it works today

### 1.1 A paid delete

1. The client signs the content path (outside mock mode) and pays `settleDelete` on
   `ValidatorPaymentV5`: a flat `DELETE_PRICE_ETH = 0.001 ether`, the same as `WRITE_PRICE_ETH`.
2. The receiving validator, in practice the leader, estimates the size of the subtree (1 KB per
   node, 100 B per property, binary length in bytes, minimum 1 MB, at most 50,000 nodes) and adds
   that amount as **GC debt** to the wallet's account in its own memory
   (`DeleteProposalHandler`, lines 245-275).
3. Every member applies the delete as `NodeBuilder.remove()` in one merge with the consensus
   watermark (`DeleteApplicationService`). It is a removal, not a tombstone. The removed records stay
   in the segment store until GC reclaims them.

### 1.2 GC debt

- Debt lives in `GCAccountManager`, in memory, on one validator. It is not replicated and is lost on
  restart or leader change.
- A write is refused while the wallet's debt is above its limit (`WriteProposalHandler`, line 362).
- `POST /v1/gc/account/{wallet}/pay` credits any amount against an unverified transaction hash.
  `POST /v1/gc/account/{wallet}/set-limit` sets any wallet's limit, including 0, with no signature.
- `PeriodicGCJob` starts with every validator. It does not collect garbage: every 384 s (300 s in
  mock mode) it moves pending debt to "executed" and recomputes whether writes are blocked. Its
  Javadoc says it simulates GC.

### 1.3 GC proposals

1. `POST /v1/propose-gc` estimates reclaimable space, prices it at `gc.usdc.per.mb` (default 0.10),
   and replicates a `GC_PROPOSAL` (template 103).
2. `POST /v1/gc/vote` replicates a `GC_VOTE` (104). The voter's `validatorId` comes from the request,
   so one caller can cast every member's vote. Quorum is `floor(2n/3)+1`.
3. The leader checks payment for the proposal. A configured chain bridge can never verify it: GC
   proposal ids are UUIDs, the bridge expects 32-byte ids, and `ValidatorPaymentV5` has no GC kind.
   Without a bridge the check is skipped.
4. `GC_EXECUTE` (105) is replicated. Each member then calls `FileStore.cleanup()` on a separate
   thread, at a point after the log entry that is not ordered against later writes.
5. The log entries have no replay watermark, so a restarted member re-runs cleanup on replay.

### 1.4 What is actually reclaimed

Nothing in `oak-segment-consensus` calls Oak compaction (`compactFull`, `compactTail`, `fullGC`,
`tailGC`, `getGCRunner`). Without compaction every segment stays in generation 0, so `cleanup()` has
no old generation to remove. Reclaimed space is about 0 B, which matches the logs and the unit test
that asserts `actualReclaimedSizeMB == 0`.

Binaries are not reclaimed either. The `/:oak-chain-binaries` index entry stays after a delete, and
nothing calls `IPFSBackend.deleteRecord`, so IPFS pins stay.

### 1.5 Summary

| Part | Intended | Actual |
|---|---|---|
| Delete fee | Pays for the delete | Flat 0.001 ETH; works |
| GC debt | Deleter pays for the garbage | Charged in one validator's memory for reclamation that never happens; lost on restart |
| Write gate on debt | Unpaid garbage blocks writes | Works on that validator only; any caller can set a limit of 0 and block a wallet |
| GC proposal, vote, payment | Cluster agrees and is paid to collect | Votes can be forged; payment cannot be verified on chain; execution reclaims nothing |
| Segment space | Reclaimed after deletes | Never reclaimed |
| Binary space | Reclaimed after deletes | Never reclaimed |

The paid-GC machinery charges for work that does not happen, and its open endpoints are the main
security exposure in the GC area.

## 2. Requirements

1. Reclaim segment-store space left by deletes and overwrites.
2. Reclaim IPFS space for binaries nothing references, without unpinning content another cluster or
   an author still needs.
3. Never break a local or remote reader that is within a stated staleness bound.
4. Never stall consensus: members must not all compact at once, and writers must not be blocked for
   long.
5. No outsider can trigger, block or tune GC, and no wallet can be blocked through it.
6. Writes and deletes stay ETH-gated.

## 3. Options

**A. Keep the paid-GC market and fix it.** Authenticate the endpoints, replicate debt, add a GC
settlement kind to the contract, sign votes, and wire in compaction. This keeps the original intent,
but per-wallet attribution does not fit how GC works:

- A compaction reclaims everyone's garbage at once. A delete's bytes leave disk only after the
  second compaction that follows it.
- Compaction cost scales with the live content it copies, not with the bytes a deleter removed.
- What is reclaimable depends on reader retention and on shared IPFS pins, neither of which the
  deleter controls.

**B. Operational GC (recommended).** Validators run GC as routine maintenance, as Oak deployments do
for revision cleanup. Writes and deletes are the paid operations, and their prices cover storage and
upkeep. The GC market, debt accounts and votes are removed.

**C. B plus size-aware pricing.** As B, but a write that stores a binary pays by size, because
binary bytes are the cost that grows with use (section 4.5).

The recommendation is B now, with C's pricing as a separate decision.

## 4. Proposed design (option B)

### 4.1 Remove the paid-GC market

- Remove `POST /v1/propose-gc`, `/v1/gc/vote`, `/v1/gc/execute`, `/v1/gc/trigger` and
  `/v1/gc/account/**`, the debt gate in the write and delete handlers, `GCAccountManager`,
  `PeriodicGCJob` and the `gc.usdc.per.mb` setting.
- Replace `GET /v1/gc/status` with real GC state (4.2). Remove `GET /v1/gc/estimate`, whose estimate
  does not predict what cleanup reclaims.
- Keep decoding `GC_PROPOSAL`, `GC_VOTE` and `GC_EXECUTE` (103-105) so existing logs replay. Applying
  one becomes a logged no-op. No new message type is needed for segment GC.
- Removing the endpoints closes the forged-vote and `set-limit` exposures without new auth code.

### 4.2 Segment GC: member-local, scheduled, staggered

Each member runs Oak online revision GC on its own `FileStore`: tail compaction on a short schedule,
full compaction on a long one, each followed by cleanup.

- **No replicated decision.** Members' physical stores already differ: segment ids are random and
  segment boundaries depend on local flushes. Physical results cannot be compared, and logical
  content does not change during GC. A replicated decision would add replay hazards and no
  correctness.
- **Staggered.** Member *i* of *n* starts at an offset of *i* x period / *n*, so at most one member
  compacts at a time and the quorum (2 of 3, 3 of 5) stays responsive. The offset comes from the
  member id, with no coordination messages.
- **Writers are not stalled.** Replicated apply runs on Aeron's service thread (`onSessionMessage`).
  Oak's force phase blocks writers for up to `forceTimeout`, 60 s by default. Set it to a few seconds,
  or disable force compaction if Oak permits that (to verify). A compaction that cannot finish then
  gives up and retries at the next slot.
- **Settings.** `retainedGenerations` (default 2), compaction periods, `forceTimeout`, and Oak's
  estimation and memory thresholds become validator settings with documented defaults.
- **Reported.** `/health` and `GET /v1/federation` publish each member's GC state: full and tail
  generation, last compaction (type, finish time, reclaimed bytes, outcome), `retainedGenerations`,
  the minimum interval between full compactions, and the next scheduled run.
- **Off by default** behind a toggle until the harness gate in section 5 passes.

### 4.3 Remote readers

A reader on another cluster holds record ids from the owner's store. After two full compactions on
the owner, those segments are gone.

- The HTTP segment reader records, per request, why a remote fetch failed: the owner answered 404
  (missing) or the request failed (transport). Oak reports both as `SegmentNotFoundException`.
- When such a request would answer 500, it answers 503 with `Retry-After: 1` instead:
  `mount_unavailable` for a transport failure, which also counts against the endpoint's circuit
  breaker so a federated mount fails over; `mount_stale` for a missing segment, which refreshes the
  mount's head at once so the retry reads current content.
- There is no retry inside the request: handlers answer their own errors and commit the response,
  so a retry would need response buffering. Implemented in phase 1 and tested against a real owner
  store compacted twice under a held head (`MountReaderAcrossRemoteCompactionTest`).
- A mount that has not refreshed for longer than the owner's published window (minimum interval
  between full compactions x (`retainedGenerations` - 1)) stops serving until it refreshes.
- Contract for owners: never publish a window shorter than the readers' refresh interval plus a
  margin.

### 4.4 Binary GC

Research on Oak's own blob GC (shared-DataStore mark-sweep) with `IPFSDataStore`:

- `IPFSDataStore` already implements what Oak's mark-sweep needs (`SharedDataStore` metadata
  records, record enumeration with last-modified times, `deleteRecord`), and
  `SegmentBlobReferenceRetriever` works on the validator `FileStore`. Each validator can run Oak's
  blob GC against its own segment store and its own Kubo index today.
- Two things limit what it can reclaim. Until segment compaction runs (4.2), every blob stays
  referenced, so sweep deletes nothing. And while the validators share no metadata, Oak's "sweep
  after every repository has marked" barrier covers only one validator, so it may only drop that
  validator's local index entries and links, never an IPFS Cluster pin. That is the current wiring:
  `unpinOnDelete` is never set.
- Unpinning a cluster pin is the one step that needs ordering against writes. Another member can
  take a new upload of the same bytes and re-pin it after this member marked.
- A write that only names an existing `ipfsCid` stores a string, which mark does not see. Phase 3
  must store the mapped blob id instead, so that content stays referenced.

Implemented design: a mark on the leader, a replicated release ordered against writes by log
position, and member-local deletes. Reference counts were not needed: Oak's own reference collection
on the leader's `FileStore` already answers "is it referenced", and log positions answer "was it
referenced since".

- **Stamps.** Every replicated apply that stores or references an indexed binary sets `refPosition`
  (the entry's log position, as recorded in the applied watermark) and `refTime` (Aeron cluster time)
  on its index entry. Both come from the log entry, so every member writes the same values. Entries
  without stamps read as position 0 and time 0. A binary write without a CID stamps an existing entry
  and creates none.
- **Client CIDs.** At ingress, a client `ipfsCid` that the CID mappings resolve to a blob id, and that
  the replicated index records for that blob id, is proposed with `blobId` (with its length from the
  index). Apply then stores a real binary reference and stamps the entry, exactly as for an upload.
  Any other `ipfsCid`, and every log entry written before this change, keeps the string reference and
  replays unchanged. CIDs the validator never stored have no index entry and are never released.
- **Mark.** `BinaryGcCoordinator` runs on its own thread on every member and acts only when a mark is
  due (`oak.gc.binary.interval.ms`, default one day; the first at most ten minutes after start) and the
  member may propose cluster commands (leader, genesis verified, no apply failure). It reads the
  applied watermark and the index from one root, flushes the `FileStore`, collects its blob
  references (every generation not yet reclaimed), and proposes `BINARY_RELEASE` (template 118) in
  batches of at most 1000 ids for entries that are unreferenced, have `refPosition` below the
  watermark position and `refTime` at least `oak.gc.binary.grace.ms` (default one day) old. The
  payload is `{"markedAtPosition":p,"maxLastModified":t,"blobIds":[...]}` with `t` = mark time minus
  the grace. Proposals go through the internal ingress like CHARTER; no Ethereum, vote or HTTP trigger.
- **Release.** Every member removes each listed entry that still exists and whose `refPosition` is
  below `markedAtPosition`, and records the watermark in the same merge, so replay below the floor
  skips the release. An entry referenced at or after the mark position is skipped. A malformed
  release is rejected identically on every member.
- **Delete.** After the merge the member schedules, on its own thread and after
  `oak.gc.binary.delete.delay.ms` (default ten minutes), `deleteRecord` on its DataStore for each
  released id. It keeps a record whose index entry exists again, whose last-modified time is after
  `maxLastModified` (the same bytes were uploaded again and their write is still on its way to the
  log), or that it does not hold. Failures are logged and counted and never affect the apply. The
  CID mapping of a deleted record is dropped, so ingress stops accepting that CID.
- **IPFS Cluster.** `ipfs.cluster.unpin-on-delete` (`IPFS_CLUSTER_UNPIN_ON_DELETE`, default `false`)
  is passed to `IPFSDataStore` as `ipfsClusterUnpinOnDelete`. Without it a delete drops the member's
  IPFS Files entries and leaves the cluster pin; with it the cluster pin goes too. Turn it on only
  when the IPFS Cluster is dedicated to this enterprise cluster. Kubo reclaims unpinned blocks with
  its own GC (`--enable-gc`, `Datastore.GCPeriod`); the validator does not call `repo/gc`.
- **Status.** `ServerContext.binaryGcStatus` supplies the member's settings, last mark, last release
  and delete counters, or is null when binary GC is disabled.

Deviations from the original sketch, and why:

- The release removes entries with `refPosition` strictly below `markedAtPosition`, where
  `markedAtPosition` is the watermark position at the mark. The watermark names the last applied
  item; later items of the same batch entry share its position and may not have been applied when
  the references were collected, so an entry referenced at exactly that position waits for the next
  mark.
- The payload carries `maxLastModified`. Ordering by log position cannot see an upload whose write is
  not committed yet; the record's last-modified time can, as in Oak's own mark-sweep
  (`maxLastModifiedInterval`). The grace must exceed the longest wait between upload and commit.
- Deletes wait `oak.gc.binary.delete.delay.ms` and re-check the index, so a write admitted with a
  mapped `ipfsCid` just before the release, which touches no record, keeps its bytes if it commits
  within the delay.
- `oak.gc.binary.enabled=false` stops this member from proposing and deleting; it still applies
  releases, which must not depend on node-local settings.

Open risks:

- A release that commits and is then followed by a crash before the delay has run leaves that
  member's records in place: replay skips the release and nothing proposes those ids again. The space
  is lost, not content. Oak's per-validator mark-sweep against the local DataStore could recover it.
- A client `ipfsCid` write admitted before a release and committed after the delete delay references
  deleted bytes on members that deleted them.
- The mark keeps every referenced blob id in memory.
- Releases are ordered against writes on this enterprise cluster only. A remote reader holding an
  old head is already bounded by segment GC: two full compactions after a delete remove the segments
  that reference the binary before it can become a candidate.

### 4.5 Pricing

- **Deletes.** A delete leaves about a kilobyte of dead records per member, almost regardless of
  what it removes. A flat delete fee fits.
- **Writes without binaries.** The same reasoning applies; a flat fee fits.
- **Writes with validator-hosted binaries.** A flat fee buys unbounded bytes, stored on every IPFS
  Cluster peer that holds a replica. Either enforce a size cap at ingress (no contract change), or
  price by size in a new contract version. The recommendation is a cap now and a pricing decision for
  the next contract version.

## 5. Phases

1. **Simplify and close exposures.**
   - Remove the paid-GC market (4.1); old GC log entries become no-ops.
   - Mounts fail safe (4.3), with an offline test that compacts the remote store twice under a held
     root.
   - Update the infra scripts that call the GC endpoints (`10-e2e-write-delete-gc.sh`,
     `11-e2e-write-namespace-delete-gc.sh`, `oak_api_smoke.sh`) and the `/operators/segment-gc` docs
     page.
2. **Segment GC.** Scheduled, staggered member-local GC behind a toggle (4.2), GC state reporting,
   and a 3x2 harness run where cluster B compacts while cluster A reads through its mount. Turn the
   toggle on by default only after that run passes with no 500s or missing content on A.
3. **Binary GC** (4.4). Implemented on `feature/binary-gc`, on by default.
4. **Binary pricing** (4.5).

## 6. Decisions needed

| # | Decision | Outcome |
|---|---|---|
| D1 | Replace paid GC with operational GC (option B) and remove the GC market, debt accounts and votes | Decided: yes. Done in phase 1 |
| D2 | Segment GC member-local and staggered, not a replicated decision | Decided: Oak's own mechanisms, unabated |
| D3 | Binary GC: Oak's blob GC per validator for local entries; cluster unpin only behind a barrier ordered against writes, only on an IPFS Cluster dedicated to the enterprise cluster | Decided and implemented as in 4.4: leader mark from Oak's reference collection, replicated `BINARY_RELEASE` ordered by log position, member-local deletes; cluster unpin only with `ipfs.cluster.unpin-on-delete` |
| D4 | Binary size and pricing | Deferred to the pricing rethink |
| D5 | Default schedule: tail compaction daily, full compaction weekly, `retainedGenerations` 2 | Open, tune after the harness run |

## Evidence

The review that this note summarises:
`oak-chain-compaction-and-paid-deletes-review.md` (Studio artifact), with file and line references
for every claim in section 1. The claims were re-checked on this branch at `42fb205ece`.
