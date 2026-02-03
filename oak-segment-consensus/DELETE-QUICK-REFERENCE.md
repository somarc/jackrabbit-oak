# Delete Proposal Quick Reference

**Quick navigation for developers working on delete proposal and GC features.**

## 📁 Key Files

| Component | File | Lines | Purpose |
|-----------|------|-------|---------|
| **API Entry** | `ConsensusApiHandler.java` | 537-748 | REST endpoint for delete proposals |
| **Queueing** | `ProposalQueueManagerOptimized.java` | 451-513 | Queue delete for Ethereum verification |
| **Aeron Sending** | `ProposalQueueManagerOptimized.java` | 617-627 | Send via Aeron (templateId 101) |
| **Message Dispatch** | `MessageDispatcher.java` | 241-268 | Route Aeron messages to handlers |
| **Delete Application** | `ConsensusApiHandler.java` | 1099-1161 | Apply replicated delete to Oak |
| **GC Debt** | `GCAccountManager.java` | 43-224 | Track debt per wallet |
| **GC Account** | `EntityGCAccount.java` | 38-165 | Per-wallet debt state |
| **Periodic GC** | `PeriodicGCJob.java` | 48-281 | Convert pending → executed debt |
| **GC Proposals** | `GCProposalManager.java` | 103-533 | Propose/vote/execute GC |
| **GC Cost Estimation** | `GCCostEstimator.java` | 65-193 | BFS segment graph traversal |

## 🔑 Key Concepts

### Delete Flow Phases
1. **API Validation** (< 10ms): Wallet, path ownership, client registration
2. **Ethereum Verification** (1-15s): EvmBridge confirms payment
3. **Epoch Batching** (0-32min): Batched by payment tier (PRIORITY/EXPRESS/STANDARD)
4. **Aeron Replication** (< 100ms): Raft consensus, all validators receive
5. **Deterministic Apply** (< 50ms): All validators execute same Oak operations
6. **Periodic GC** (every 5min): Convert pending → executed debt
7. **Actual Cleanup** (manual): Oak FileStore.cleanup() reclaims segments

### GC Debt Model
```
Delete content → totalDebt += $0.10/MB (pending)
    ↓
Periodic GC (5 min) → pending → executed
    ↓
If executedDebt >= $100 → writesBlocked = true
    ↓
Payment → executedDebt -= amount → unblocked
```

## 🔧 API Endpoints

### Delete Proposal
```bash
POST /v1/propose-delete
{
  "walletAddress": "0xdd870fa1b7c4700f2bd7f44238821c26f7392148",
  "signature": "0x1a2b3c...",
  "contentPath": "/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content/page1",
  "ethereumTxHash": "0xabcd..."
}

Response: 202 Accepted
{
  "proposalId": "uuid-123",
  "type": "DELETE",
  "state": "PENDING",
  "gcDebtIncurred": "0.10",
  "totalDebt": "5.40",
  "pendingDebt": "0.10",
  "writesBlocked": false
}
```

### GC Proposal
```bash
POST /v1/gc/propose
{
  "proposerWallet": "0xdd870fa1b7c4700f2bd7f44238821c26f7392148",
  "targetRevision": "HEAD"
}

Response: 200 OK
{
  "proposalId": "gc-uuid-456",
  "estimatedReclaimableSizeMB": 1024,
  "estimatedCostUSDC": "102.40",
  "state": "PENDING"
}
```

### Check GC Account
```bash
GET /v1/gc/account?wallet=0xdd870fa1b7c4700f2bd7f44238821c26f7392148

Response: 200 OK
{
  "walletAddress": "0xdd870fa1b7c4700f2bd7f44238821c26f7392148",
  "totalDebt": "5.40",
  "executedDebt": "5.30",
  "pendingDebt": "0.10",
  "debtLimit": "100.00",
  "writesBlocked": false,
  "deletes": [
    {"path": "/oak-chain/.../page1", "sizeMB": 1, "cost": "0.10", "timestamp": 1733421234000}
  ]
}
```

## 🧪 Testing Scenarios

### Scenario 1: Simple Delete
```bash
# 1. Submit delete proposal
curl -X POST http://localhost:8090/v1/propose-delete \
  -d "walletAddress=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
  -d "signature=0x..." \
  -d "contentPath=/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content/page1" \
  -d "ethereumTxHash=0xabcd..."

# Expected: 202 Accepted, proposalId returned

# 2. Wait for Ethereum confirmation (1-15s)
curl http://localhost:8090/v1/proposals/{proposalId}/status

# Expected: state transitions PENDING → VERIFIED → COMMITTED

# 3. Check content removed
curl http://localhost:8090/api/explore?path=/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content

# Expected: page1 not in children list

# 4. Check GC debt added
curl http://localhost:8090/v1/gc/account?wallet=0xdd870fa1b7c4700f2bd7f44238821c26f7392148

# Expected: totalDebt increased by $0.10, pendingDebt = $0.10
```

### Scenario 2: Write Blocking
```bash
# 1. Delete 1000 MB of content
for i in {1..1000}; do
  curl -X POST http://localhost:8090/v1/propose-delete \
    -d "walletAddress=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
    -d "signature=0x..." \
    -d "contentPath=/oak-chain/.../content/page${i}" \
    -d "ethereumTxHash=0x..."
done

# Expected: totalDebt += $100 (pending)

# 2. Wait for periodic GC (5 min)
# ... or trigger manually via API

# 3. Check if blocked
curl http://localhost:8090/v1/gc/account?wallet=0xdd870fa1b7c4700f2bd7f44238821c26f7392148

# Expected: executedDebt = $100, writesBlocked = true

# 4. Attempt write (should fail)
curl -X POST http://localhost:8090/v1/propose-write \
  -d "walletAddress=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
  -d "..."

# Expected: 402 Payment Required, "Writes blocked due to unpaid GC debt"
```

### Scenario 3: Payment & Unblock
```bash
# 1. Pay debt via smart contract
curl -X POST http://localhost:8090/v1/gc/pay \
  -d "walletAddress=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
  -d "amount=50.00" \
  -d "ethereumTxHash=0x..."

# Expected: executedDebt reduced to $50

# 2. Check account unblocked
curl http://localhost:8090/v1/gc/account?wallet=0xdd870fa1b7c4700f2bd7f44238821c26f7392148

# Expected: executedDebt = $50, writesBlocked = false

# 3. Writes now allowed
curl -X POST http://localhost:8090/v1/propose-write \
  -d "walletAddress=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
  -d "..."

# Expected: 202 Accepted
```

## 🐛 Debugging Tips

### Delete Not Applied
**Symptoms**: Content still visible after delete proposal confirmed

**Check**:
1. Proposal state: `GET /v1/proposals/{proposalId}/status` → should be `COMMITTED`
2. Aeron logs: `grep "DELETE_PROPOSAL" /var/log/oak-validator.log`
3. Oak commit logs: `grep "aeron-replication-delete" /var/log/oak-validator.log`
4. Path ownership: Ensure contentPath starts with wallet's shard root

**Common Causes**:
- Path ownership violation (different wallet's shard)
- Aeron replication failure (check cluster health)
- Oak commit exception (check FileStore logs)

### GC Debt Not Tracked
**Symptoms**: totalDebt = $0 after delete

**Check**:
1. GCAccountManager initialized: `grep "GCAccountManager" /var/log/oak-validator.log`
2. addDebt() called: `grep "Added GC debt" /var/log/oak-validator.log`
3. Size estimation: Check if sizeMB > 0

**Common Causes**:
- GCAccountManager not wired to ConsensusApiHandler
- Size estimation returns 0
- Exception thrown in addDebt() (swallowed)

### Writes Not Blocked
**Symptoms**: executedDebt > $100 but writesBlocked = false

**Check**:
1. Periodic GC running: `GET /v1/gc/periodic-job/stats`
2. Debt conversion: `grep "Converted pending debt" /var/log/oak-validator.log`
3. Account state: `shouldBlockWrites()` method logic

**Common Causes**:
- Periodic GC job not started
- convertPendingToExecuted() not called
- Debt limit misconfigured (> $100)

## 📊 Performance Benchmarks

| Operation | Latency | Throughput | Notes |
|-----------|---------|------------|-------|
| **Delete API validation** | < 10ms | 1000 req/s | In-memory checks |
| **Ethereum verification** | 1-15s | N/A | External blockchain query |
| **Aeron replication** | < 100ms | 500 msg/s | UDP multicast, Raft consensus |
| **Oak commit** | < 50ms | 200 commit/s | FileStore merge + flush |
| **GC cost estimation** | 10-15s | N/A | BFS traverse 4M segments |
| **FileStore.cleanup()** | 30-120s | N/A | Depends on TAR file count |

## 🔐 Security Checklist

- [ ] Wallet validation: Must be valid `0x...` address
- [ ] Path ownership: contentPath must start with wallet's shard root
- [ ] Client registration: Wallet must be registered before delete
- [ ] Ethereum payment: Transaction must be confirmed (>= 1 block)
- [ ] Signature verification: TODO (not enforced in MVP)
- [ ] Rate limiting: TODO (not implemented)
- [ ] Debt enforcement: Writes blocked when executedDebt >= limit

## 🚀 Optimization Opportunities

### 1. Batch Size Estimation
**Current**: Heuristic (1MB per content item)

**Improvement**: Calculate actual size from Oak NodeStore
```java
long calculateActualSize(NodeStore nodeStore, String path) {
    NodeState node = nodeStore.getRoot().getChildNode(path);
    long totalSize = 0;
    
    // Sum binary property sizes
    for (PropertyState prop : node.getProperties()) {
        if (prop.getType() == Type.BINARY) {
            totalSize += prop.size();
        }
    }
    
    // Recurse for children
    for (ChildNodeEntry child : node.getChildNodeEntries()) {
        totalSize += calculateActualSize(nodeStore, path + "/" + child.getName());
    }
    
    return totalSize / (1024 * 1024); // Convert to MB
}
```

### 2. Segment Graph Caching
**Current**: Load TAR graphs on every GC cost estimation (2-5s)

**Improvement**: Cache graphs in memory, invalidate on TAR file changes
```java
private final LoadingCache<String, Map<UUID, Set<UUID>>> tarGraphCache = 
    CacheBuilder.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(10, TimeUnit.MINUTES)
        .build(new CacheLoader<String, Map<UUID, Set<UUID>>>() {
            public Map<UUID, Set<UUID>> load(String fileName) throws IOException {
                return tarFiles.getGraph(fileName);
            }
        });
```

### 3. Incremental GC (Wallet-Specific)
**Current**: Full repository GC affects all wallets

**Improvement**: GC only segments owned by specific wallet
```java
public void executeWalletGC(String walletAddress) {
    String shardRoot = WalletPathUtil.getShardRoot(walletAddress);
    NodeState walletNode = nodeStore.getRoot().getChildNode(shardRoot);
    
    // Find segments reachable from wallet's shard root
    Set<UUID> walletSegments = findReachableSegments(walletNode);
    
    // Compact only those segments
    fileStore.cleanup(walletSegments);
}
```

## 📚 Related Documentation

### Comprehensive Documentation (Blockchain-AEM Repository)
- Delete Proposal & GC Deep Dive - Complete technical deep dive
- Delete Flow Diagrams - Visual flow diagrams
- [ADR 017 - GC Account Tax Model](../Blockchain-AEM/adr/017-gc-account-tax-model.md)
- [ADR 018 - GC Proposal Complexity Analysis](../Blockchain-AEM/adr/018-gc-proposal-formal-complexity.md)

## 🛠️ Development Workflow

### Adding a New Delete Feature

1. **Update API Handler** (`ConsensusApiHandler.java`)
   - Add validation logic
   - Update response JSON

2. **Update Queue Manager** (`ProposalQueueManagerOptimized.java`)
   - Add new fields to `QueuedProposal`
   - Update `queueDeleteProposal()` method

3. **Update Aeron Message** (`MessageDispatcher.java`)
   - Add new fields to JSON payload
   - Update `handleDeleteProposal()` parsing

4. **Update Delete Application** (`ConsensusApiHandler.applyReplicatedDelete()`)
   - Add new Oak operations
   - Ensure deterministic execution

5. **Update Tests**
   - Add integration test
   - Verify Aeron replication
   - Check GC debt tracking

### Testing Changes Locally

```bash
# 1. Build module
cd jackrabbit-oak
mvn clean install -pl oak-segment-consensus -am -DskipTests

# 2. Copy JAR to infra
cp oak-segment-consensus/target/oak-segment-consensus.jar \
   ../blockchain-aem-infra/docker-compose/

# 3. Rebuild Docker image
cd ../blockchain-aem-infra
docker build -t oak-global-store:latest -f docker/validator/Dockerfile docker/validator

# 4. Restart validators
cd docker-compose
docker-compose -f testing/3-validators-aeron.yml restart

# 5. Test delete proposal
curl -X POST http://localhost:8090/v1/propose-delete \
  -d "walletAddress=0xdd870fa1b7c4700f2bd7f44238821c26f7392148" \
  -d "signature=0x..." \
  -d "contentPath=/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content/test" \
  -d "ethereumTxHash=0xtest..."

# 6. Check logs
docker logs -f validator-0
```

## 📝 Code Style Guidelines

### Logging Conventions
```java
// Use emoji prefixes for visual scanning
log.info("🗑️  DELETE PROPOSAL: client={}, wallet={}, path={}", clientId, wallet, path);
log.warn("🚫 Delete proposal rejected: Path ownership violation");
log.error("❌ Delete proposal failed", e);
log.debug("📥 Queuing DELETE proposal {} (tx: {})", proposalId, ethereumTxHash);
```

### Error Handling
```java
// Always return descriptive error messages
response.sendError(HttpServletResponse.SC_FORBIDDEN, 
    String.format("Path ownership violation: Content at %s does not belong to wallet %s. " +
                 "Only content under %s/ can be deleted.",
                 contentPath, wallet, shardRoot));
```

### Idempotency
```java
// Check existence before deletion
if (!current.hasChildNode(targetNodeName)) {
    log.warn("⚠️  Target node doesn't exist: {} (idempotent delete)", targetNodeName);
    return; // Not an error - already deleted
}
```

---

**Last Updated**: December 4, 2025  
**Maintainer**: Oak Segment Consensus Team  
**Status**: 🧪 POC / Active Development
