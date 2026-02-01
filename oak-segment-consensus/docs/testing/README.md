# Testing Guide

**How to test oak-segment-consensus**

---

## Test Structure

```
src/test/java/
├── consensus/
│   ├── aeron/              # Aeron consensus tests
│   ├── queue/               # Proposal queue tests
│   └── gc/                  # GC tests
└── http/
    └── server/              # HTTP handler tests
```

---

## Running Tests

### All Tests

```bash
mvn test -pl oak-segment-consensus
```

### Specific Test Class

```bash
mvn test -pl oak-segment-consensus -Dtest=ProposalQueueIntegrationTest
```

### With Coverage

```bash
mvn test -pl oak-segment-consensus jacoco:report
```

---

## Test Categories

### Unit Tests

**Location**: `src/test/java/`

**Examples**:
- `ProposalStateTest` - State machine transitions
- `GCProposalStateTest` - GC proposal states
- `EvmBridgeTest` - Payment verification

**Run**:
```bash
mvn test -pl oak-segment-consensus -Dtest="*Test"
```

### Integration Tests

**Location**: `src/test/java/.../integration/`

**Examples**:
- `ProposalQueueIntegrationTest` - End-to-end proposal flow
- `MultiValidatorConsensusTest` - Multi-validator consensus

**Requirements**:
- Docker (for multi-validator tests)
- IPFS node (for IPFS tests)

**Run**:
```bash
mvn test -pl oak-segment-consensus -Dtest="*IntegrationTest"
```

---

## Local Testing

### Single Validator

```bash
# 1. Start validator
export OAK_BLOCKCHAIN_MODE=mock
java -jar oak-segment-consensus.jar

# 2. Test API
curl http://localhost:8090/v1/consensus/status

# 3. Submit write proposal
curl -X POST http://localhost:8090/v1/propose-write \
  -d "walletAddress=0xtest..." \
  -d "signature=0xtest..." \
  -d "message=test" \
  -d "contentPath=/oak-chain/test/content/page1" \
  -d "ethereumTxHash=0xtest..."
```

### Multi-Validator Cluster

```bash
# Use docker-compose
cd blockchain-aem-infra/docker-compose
docker-compose -f testing/3-validators-aeron.yml up -d

# Test cluster
curl http://localhost:8090/v1/aeron/cluster-state
curl http://localhost:8092/v1/aeron/cluster-state
curl http://localhost:8094/v1/aeron/cluster-state
```

---

## Test Scenarios

### Write Proposal Flow

```bash
# 1. Submit proposal
PROPOSAL_ID=$(curl -X POST http://localhost:8090/v1/propose-write \
  -d "walletAddress=0xtest..." \
  -d "signature=0xtest..." \
  -d "message=test" \
  -d "contentPath=/oak-chain/test/content/page1" \
  -d "ethereumTxHash=0xtest..." | jq -r .proposalId)

# 2. Check status
curl http://localhost:8090/v1/proposals/$PROPOSAL_ID/status

# 3. Verify content
curl http://localhost:8090/api/explore?path=/oak-chain/test/content/page1
```

### Delete Proposal Flow

```bash
# 1. Submit delete
curl -X POST http://localhost:8090/v1/propose-delete \
  -d "walletAddress=0xtest..." \
  -d "signature=0xtest..." \
  -d "contentPath=/oak-chain/test/content/page1" \
  -d "ethereumTxHash=0xtest..."

# 2. Check GC debt
curl http://localhost:8090/v1/gc/account?wallet=0xtest...

# 3. Verify deletion
curl http://localhost:8090/api/explore?path=/oak-chain/test/content
```

### Cluster Health

```bash
# Check all validators
for port in 8090 8092 8094; do
  echo "Validator $port:"
  curl -s http://localhost:$port/v1/consensus/status | jq .clusterHealthy
done
```

---

## Mock Mode Testing

**Mock mode** (`OAK_BLOCKCHAIN_MODE=mock`) enables testing without blockchain:

- ✅ Instant payment verification (simulated)
- ✅ No Ethereum RPC required
- ✅ Fast iteration
- ⚠️ Not cryptographically secure

**Use for**:
- Development
- Unit tests
- Integration tests
- Load testing

---

## Test Data

### Test Wallets

```bash
# Generate test wallet
export TEST_WALLET="0x$(openssl rand -hex 20)"

# Use in tests
curl -X POST http://localhost:8090/v1/propose-write \
  -d "walletAddress=$TEST_WALLET" \
  ...
```

### Test Content

```json
{
  "title": "Test Page",
  "content": "Test content",
  "metadata": {
    "created": "2026-01-31",
    "author": "test"
  }
}
```

---

## Performance Testing

### Load Test

```bash
# Use Apache Bench
ab -n 1000 -c 10 -p write.json -T application/x-www-form-urlencoded \
  http://localhost:8090/v1/propose-write
```

### Monitor Metrics

```bash
# Prometheus metrics
curl http://localhost:8090/metrics | grep oak_consensus

# JSON metrics
curl http://localhost:8090/api/metrics | jq .
```

---

*See [DELETE-QUICK-REFERENCE.md](../../DELETE-QUICK-REFERENCE.md) for delete/GC testing scenarios.*
