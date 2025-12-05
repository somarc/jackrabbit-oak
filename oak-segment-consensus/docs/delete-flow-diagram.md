# Delete Proposal Flow - Visual Diagrams

## Complete End-to-End Flow

```
┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         DELETE PROPOSAL LIFECYCLE                                   │
└─────────────────────────────────────────────────────────────────────────────────────┘

Phase 1: API Entry & Validation (< 10ms)
═══════════════════════════════════════════════════════════════════════════════════════
     
   Sling Author                          Validator HTTP Server
        │                                        │
        │  POST /v1/propose-delete               │
        │  {                                     │
        │    walletAddress: "0xdd87...",         │
        │    signature: "0x1a2b...",             │
        │    contentPath: "/oak-chain/.../page1",│
        │    ethereumTxHash: "0xabcd..."         │
        │  }                                     │
        ├───────────────────────────────────────►│
        │                                        │
        │                                        ├──► ❶ Validate wallet format (0x...)
        │                                        │
        │                                        ├──► ❷ Lookup client registration
        │                                        │       - Primary: by walletAddress
        │                                        │       - Fallback: by clientId
        │                                        │
        │                                        ├──► ❸ Path ownership enforcement
        │                                        │       Shard: /oak-chain/dd/87/0f/0xdd87.../
        │                                        │       Check: contentPath.startsWith(shard)
        │                                        │
        │                                        ├──► ❹ GC Debt Accounting
        │                                        │       - Size: 1MB (heuristic)
        │                                        │       - Cost: $0.10/MB
        │                                        │       - Add to pending debt
        │                                        │
        │                                        ├──► ❺ Queue for verification
        │                                        │       proposalId = UUID.generate()
        │                                        │       unverifiedQueue.add(proposal)
        │                                        │
        │  202 Accepted                          │
        │  {                                     │
        │    proposalId: "uuid-123",             │
        │    state: "PENDING",                   │
        │    gcDebtIncurred: "0.10",             │
        │    totalDebt: "5.40",                  │
        │    writesBlocked: false                │
        │  }                                     │
        │◄───────────────────────────────────────┤
        │                                        │


Phase 2: Ethereum Verification (1-15 seconds)
═══════════════════════════════════════════════════════════════════════════════════════

   Validator (Background Agent)              Ethereum Sepolia Testnet
        │                                           │
        │  EthereumVerificationAgent               │
        │  (polling every 1000ms)                  │
        │                                           │
        ├──► ❻ Dequeue from unverifiedQueue        │
        │       proposal = unverifiedQueue.poll()  │
        │                                           │
        ├──► ❼ Call EvmBridge                      │
        │       txHash = proposal.ethereumTxHash   │
        │                                           │
        │  verifyPayment(txHash)                   │
        ├──────────────────────────────────────────►│
        │                                           │
        │                                           ├──► Query transaction
        │                                           │    - From: user wallet (MetaMask)
        │                                           │    - To: ValidatorPaymentV3_1
        │                                           │    - Value: payment amount
        │                                           │    - Block: confirmation depth
        │                                           │
        │  PaymentProof                             │
        │  {                                        │
        │    txHash, blockNumber,                  │
        │    from, to, value, confirmed            │
        │  }                                        │
        │◄──────────────────────────────────────────┤
        │                                           │
        ├──► ❽ Check confirmation (>= 1 block)     │
        │       if (confirmed) {                    │
        │         proposal.state = VERIFIED         │
        │         epochQueue.add(proposal)          │
        │       }                                   │
        │                                           │
        ├──► ❾ Payment tier detection              │
        │       PRIORITY: +0 epochs (immediate)     │
        │       EXPRESS:  +1 epoch  (~16 min)       │
        │       STANDARD: +2 epochs (~32 min)       │
        │                                           │


Phase 3: Epoch-Based Batching (0-32 minutes)
═══════════════════════════════════════════════════════════════════════════════════════

   EpochQueue (In-Memory Batching)
        │
        │  Batch Structure (by epoch + wallet):
        │  ┌──────────────────────────────────────────────┐
        │  │ Epoch 12345 (PRIORITY - current):           │
        │  │   0xdd87... → [delete1, write1, delete2]    │
        │  │   0xaabb... → [write2, delete3]             │
        │  │                                              │
        │  │ Epoch 12346 (EXPRESS - +1 epoch):           │
        │  │   0xccdd... → [delete4, write3]             │
        │  │                                              │
        │  │ Epoch 12347 (STANDARD - +2 epochs):         │
        │  │   0xeeff... → [delete5, write4, write5]     │
        │  └──────────────────────────────────────────────┘
        │
        ├──► ❿ Epoch finalization (every ~16 min)
        │       currentEpoch = getCurrentEpoch()
        │       batches = epochQueue.dequeueEpoch(currentEpoch)
        │
        │       for (wallet, proposals in batches) {
        │         batchQueue.offer(proposals)
        │       }
        │


Phase 4: Aeron Consensus Replication (< 100ms)
═══════════════════════════════════════════════════════════════════════════════════════

   Validator-0 (Leader)      Aeron Cluster       Validator-1       Validator-2
        │                         │                    │                 │
        │  AeronSenderAgent       │                    │                 │
        ├──► ⓫ Dequeue batch      │                    │                 │
        │       batch = batchQueue.poll()              │                 │
        │       proposals = 5 deletes + 3 writes       │                 │
        │                         │                    │                 │
        ├──► ⓬ Encode message     │                    │                 │
        │       if (batch.size == 1 && DELETE) {       │                 │
        │         templateId = 101 (DELETE_PROPOSAL)   │                 │
        │       } else {                                │                 │
        │         templateId = 106 (WRITE_BATCH)       │                 │
        │       }                                       │                 │
        │                         │                    │                 │
        │  appendDeleteProposal() │                    │                 │
        ├─────────────────────────►│                    │                 │
        │       JSON: {           │                    │                 │
        │         walletAddress,  │                    │                 │
        │         path,           │                    │                 │
        │         signature       │                    │                 │
        │       }                 │                    │                 │
        │                         │                    │                 │
        │                         │  ⓭ Raft Log       │                 │
        │                         │     Append          │                 │
        │                         ├────────────────────►│                 │
        │                         │                    │                 │
        │                         │  ⓮ Raft Log       │                 │
        │                         │     Append          │                 │
        │                         ├─────────────────────────────────────►│
        │                         │                    │                 │
        │                         │  ⓯ ACK (quorum)   │                 │
        │                         │◄────────────────────┤                 │
        │                         │◄─────────────────────────────────────┤
        │                         │                    │                 │
        │                         │  ⓰ COMMIT         │                 │
        │                         │     (broadcast)    │                 │
        │                         ├────────────────────►│                 │
        │                         ├─────────────────────────────────────►│
        │                         │                    │                 │


Phase 5: Deterministic Delete Application (< 50ms)
═══════════════════════════════════════════════════════════════════════════════════════

   ALL Validators (Identical Operations)
        │
        │  MessageDispatcher.handleDeleteProposal()
        ├──► ⓱ Extract JSON from Aeron buffer
        │       walletAddress = extractJsonField(json, "walletAddress")
        │       path = extractJsonField(json, "path")
        │       signature = extractJsonField(json, "signature")
        │
        ├──► ⓲ Delegate to writeCallback
        │       writeCallback.applyDelete(walletAddress, path, signature)
        │
        │  ConsensusApiHandler.applyReplicatedDelete()
        ├──► ⓳ Get current ROOT
        │       NodeBuilder rootBuilder = nodeStore.getRoot().builder()
        │
        ├──► ⓴ Navigate to parent node
        │       path = "/oak-chain/dd/87/0f/0xdd87.../content/page1"
        │       parts = [oak-chain, dd, 87, 0f, 0xdd87..., content, page1]
        │       
        │       for (part in parts[:-1]) {
        │         current = current.getChildNode(part)
        │       }
        │
        ├──► 🅰️ Remove target node
        │       targetNode = "page1"
        │       if (current.hasChildNode(targetNode)) {
        │         current.getChildNode(targetNode).remove()
        │       }
        │
        ├──► 🅱️ Commit deletion (Oak merge)
        │       CommitInfo commitInfo = new CommitInfo(
        │         "aeron-replication-delete",
        │         null,
        │         {"replicated": "true"}
        │       )
        │       
        │       nodeStore.merge(rootBuilder, EmptyHook.INSTANCE, commitInfo)
        │       fileStore.flush()
        │
        ├──► 🅲 New HEAD (automatic consensus)
        │       newHead = fileStore.getHead().getRecordId().toString10()
        │       
        │       ┌────────────────────────────────────────┐
        │       │ ALL VALIDATORS HAVE SAME HEAD          │
        │       │ (natural consequence of deterministic  │
        │       │  operations in same order)             │
        │       │                                        │
        │       │ No manual HEAD broadcasting needed!    │
        │       └────────────────────────────────────────┘
        │
        ├──► 🅳 Old segments remain on disk
        │       Content removed from tree: ✅
        │       Segments physically deleted: ❌ (deferred to GC)
        │


Phase 6: Periodic GC Job (every 5 minutes)
═══════════════════════════════════════════════════════════════════════════════════════

   PeriodicGCJob (Background Thread)
        │
        │  executeGCCycle()
        ├──► 🅴 Find entities with pending debt
        │       entitiesWithPending = gcAccountManager.getAccountsWithPendingDebt()
        │
        │       Example:
        │       0xdd87... → pending: $0.10, executed: $5.30
        │       0xaabb... → pending: $2.50, executed: $90.00
        │
        ├──► 🅵 Convert pending → executed
        │       for (account in entitiesWithPending) {
        │         pendingAmount = account.getPendingDebt()
        │         account.convertPendingToExecuted(pendingAmount)
        │         
        │         // Check if should block writes
        │         if (account.executedDebt >= account.debtLimit) {
        │           account.writesBlocked = true
        │         }
        │       }
        │
        │       After conversion:
        │       0xdd87... → pending: $0.00, executed: $5.40, blocked: false
        │       0xaabb... → pending: $0.00, executed: $92.50, blocked: false
        │
        ├──► 🅶 Log blocked entities
        │       blockedAccounts = gcAccountManager.getBlockedAccounts()
        │       
        │       Example output:
        │       ⚠️  2 entities currently BLOCKED from writing:
        │          - 0xccdd... (debt: $105.20, limit: $100.00)
        │          - 0xeeff... (debt: $150.00, limit: $100.00)
        │
        │  ┌──────────────────────────────────────────────────────┐
        │  │ IMPORTANT: This is simulated GC for POC             │
        │  │                                                      │
        │  │ Production would:                                    │
        │  │ 1. Trigger actual Oak FileStore.cleanup()           │
        │  │ 2. Attribute reclaimed space to entities            │
        │  │ 3. Update debt based on actual GC results           │
        │  └──────────────────────────────────────────────────────┘


Phase 7: Actual Segment Cleanup (manual trigger)
═══════════════════════════════════════════════════════════════════════════════════════

   GC Proposal Lifecycle
        │
        │  ⓵ Propose GC
        ├──► POST /v1/gc/propose
        │       {
        │         proposerWallet: "0xdd87...",
        │         targetRevision: "HEAD" or specific revision
        │       }
        │
        ├──► 🅷 Cost Estimation (10-15s for 4M segments)
        │       GCCostEstimator.estimateCost(targetRevision)
        │       
        │       Step 1: Get HEAD revision
        │       Step 2: BFS traverse segment graph from HEAD
        │       Step 3: Find reachable segments (200-300 MB memory)
        │       Step 4: Calculate reclaimable = all - reachable
        │       Step 5: Estimate cost = reclaimableMB * $0.10/MB
        │       
        │       Result:
        │       - Reclaimable: 1,024 MB
        │       - Estimated cost: $102.40
        │
        ├──► ⓶ Voting Phase
        │       proposal.state = PENDING
        │       
        │       for (validator in cluster) {
        │         validator.voteOnProposal(proposalId, approve, reason)
        │       }
        │       
        │       Quorum: 2/3+ validators must approve
        │       
        │       Vote results:
        │       Validator-0: ✅ APPROVE ("Fragmentation > 30%")
        │       Validator-1: ✅ APPROVE ("Cost reasonable")
        │       Validator-2: ✅ APPROVE ("Free disk space low")
        │       
        │       Result: APPROVED (3/3 votes)
        │
        ├──► ⓷ Payment Verification
        │       evmBridge.verifyPayment(proposalId)
        │       
        │       Check Ethereum transaction:
        │       - proposerWallet → ValidatorPaymentV3_1
        │       - Amount: $102.40 (in USDC)
        │       - Confirmations: >= 1 block
        │       
        │       Result: ✅ Payment verified
        │
        ├──► ⓸ Execution (auto-scheduled on approval)
        │       proposal.state = EXECUTING
        │       
        │       fileStore.cleanup()  // Oak native GC
        │       
        │       Operations:
        │       - Compact TAR files
        │       - Remove unreachable segments
        │       - Update segment references
        │       - Flush journal.log
        │       
        │       Result:
        │       - Segments removed: 1,024 MB
        │       - Actual cost: $102.40
        │       - Duration: 45 seconds
        │
        ├──► 🅸 Update fragmentation metrics
        │       fragmentationTracker.resetMetrics(affectedEntities)
        │       
        │       proposal.state = COMPLETED
        │


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         GC DEBT STATE MACHINE                                       │
└─────────────────────────────────────────────────────────────────────────────────────┘

   Entity Lifecycle:

   ┌───────────────┐
   │  Clean State  │  executedDebt: $0.00
   │               │  pending: $0.00
   │               │  writesBlocked: false
   └───────┬───────┘
           │
           │ DELETE content (1 MB)
           │
           ▼
   ┌───────────────┐
   │ Pending Debt  │  executedDebt: $0.00
   │               │  pending: $0.10
   │               │  writesBlocked: false
   └───────┬───────┘
           │
           │ Periodic GC runs (5 min)
           │
           ▼
   ┌───────────────┐
   │ Executed Debt │  executedDebt: $0.10
   │               │  pending: $0.00
   │               │  writesBlocked: false (< $100 limit)
   └───────┬───────┘
           │
           │ ... more deletes ...
           │
           ▼
   ┌───────────────┐
   │ High Debt     │  executedDebt: $105.00
   │               │  pending: $0.00
   │  🔒 BLOCKED   │  writesBlocked: true (>= $100 limit)
   └───────┬───────┘
           │
           │ PAY $50 via smart contract
           │
           ▼
   ┌───────────────┐
   │ Partial Pay   │  executedDebt: $55.00
   │               │  pending: $0.00
   │  ✅ UNBLOCKED │  writesBlocked: false (< $100 limit)
   └───────┬───────┘
           │
           │ PAY $55 via smart contract
           │
           ▼
   ┌───────────────┐
   │  Clean State  │  executedDebt: $0.00
   │               │  pending: $0.00
   │               │  writesBlocked: false
   └───────────────┘


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         SEGMENT LIFECYCLE                                           │
└─────────────────────────────────────────────────────────────────────────────────────┘

   Time: T0 (Initial state)
   ═══════════════════════════════════════════════════════════════════════════════════
   
   Segment Store:
   ┌─────────────────────────────────────────────────────────────────┐
   │ data00001a.tar:                                                 │
   │   Segment-ABC (reachable from HEAD)                            │
   │   Segment-DEF (reachable from HEAD)                            │
   │                                                                 │
   │ data00002a.tar:                                                 │
   │   Segment-GHI (reachable from HEAD - contains /content/page1) │
   │   Segment-JKL (reachable from HEAD)                            │
   └─────────────────────────────────────────────────────────────────┘
   
   Node Tree:
   /oak-chain/dd/87/0f/0xdd87.../content/
     ├── page1 ─────► Segment-GHI
     └── page2 ─────► Segment-JKL


   Time: T1 (After DELETE applied, before GC)
   ═══════════════════════════════════════════════════════════════════════════════════
   
   Segment Store:
   ┌─────────────────────────────────────────────────────────────────┐
   │ data00001a.tar:                                                 │
   │   Segment-ABC (reachable from HEAD)                            │
   │   Segment-DEF (reachable from HEAD)                            │
   │                                                                 │
   │ data00002a.tar:                                                 │
   │   Segment-GHI (UNREACHABLE - no tree reference) ◄── RECLAIMABLE│
   │   Segment-JKL (reachable from HEAD)                            │
   │                                                                 │
   │ data00003a.tar: (NEW TAR after delete commit)                  │
   │   Segment-MNO (new HEAD segment)                               │
   └─────────────────────────────────────────────────────────────────┘
   
   Node Tree:
   /oak-chain/dd/87/0f/0xdd87.../content/
     └── page2 ─────► Segment-JKL
   
   🔴 Segment-GHI still on disk, consuming space!


   Time: T2 (After GC cleanup)
   ═══════════════════════════════════════════════════════════════════════════════════
   
   Segment Store:
   ┌─────────────────────────────────────────────────────────────────┐
   │ data00001a.tar:                                                 │
   │   Segment-ABC (reachable from HEAD)                            │
   │   Segment-DEF (reachable from HEAD)                            │
   │                                                                 │
   │ data00002a.tar.gc: (COMPACTED - removed Segment-GHI)           │
   │   Segment-JKL (reachable from HEAD)                            │
   │                                                                 │
   │ data00003a.tar:                                                 │
   │   Segment-MNO (HEAD segment)                                   │
   └─────────────────────────────────────────────────────────────────┘
   
   🟢 Disk space freed! Segment-GHI physically removed.


┌─────────────────────────────────────────────────────────────────────────────────────┐
│                         KEY INSIGHTS                                                │
└─────────────────────────────────────────────────────────────────────────────────────┘

1. DETERMINISTIC CONSENSUS
   ═══════════════════════════════════════════════════════════════════════════════════
   ✅ All validators execute SAME operations in SAME order
   ✅ Aeron Cluster guarantees message ordering (Raft consensus)
   ✅ Oak commits are deterministic → SAME HEAD on all nodes
   ✅ No manual HEAD broadcasting needed (natural consequence)

2. TWO-PHASE DELETION
   ═══════════════════════════════════════════════════════════════════════════════════
   ✅ Phase 1: Logical delete (< 50ms) - remove from tree
   ✅ Phase 2: Physical cleanup (minutes) - reclaim segments
   ✅ Immediate user experience, deferred cost

3. ECONOMIC INCENTIVES
   ═══════════════════════════════════════════════════════════════════════════════════
   ✅ Deletes incur GC debt ($0.10/MB)
   ✅ Debt becomes "due" after periodic GC (5 min)
   ✅ Writes blocked if executed debt > limit
   ✅ Payment required to continue writing

4. SECURITY MODEL
   ═══════════════════════════════════════════════════════════════════════════════════
   ✅ Path ownership: Only wallet owner can delete content in their shard
   ✅ Ethereum payment: All deletes require on-chain payment
   ✅ Signature verification: Cryptographic proof of intent
   ✅ Client registration: Wallets must be registered before deleting

5. FAILURE RESILIENCE
   ═══════════════════════════════════════════════════════════════════════════════════
   ✅ Idempotent deletes: Safe to replay (already deleted → no-op)
   ✅ Payment timeout: Proposals expire after 5 minutes
   ✅ Raft recovery: Followers catch up via log replay
   ✅ GC failure: Proposal marked FAILED, segments remain

