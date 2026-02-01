# Troubleshooting Guide

**Common issues and solutions**

---

## Cluster Health Issues

### Cluster Unhealthy

**Symptoms**: `503 Service Unavailable - Cluster unhealthy`

**Check**:
```bash
curl http://localhost:8090/v1/consensus/status
```

**Common Causes**:
1. **No leader elected** - Wait for leader election (< 5 seconds)
2. **Quorum lost** - Check validator connectivity
3. **Aeron Media Driver down** - Restart validator

**Solutions**:
```bash
# Check Aeron cluster state
curl http://localhost:8090/v1/aeron/cluster-state

# Check all validators
for port in 8090 8092 8094; do
  echo "Validator $port:"
  curl -s http://localhost:$port/v1/consensus/status | jq .
done

# Restart validator
docker-compose restart validator-0
```

---

## Proposal Issues

### Proposal Stuck in PENDING

**Symptoms**: Proposal never transitions to CONFIRMED

**Check**:
```bash
curl http://localhost:8090/v1/proposals/{id}/status
```

**Common Causes**:
1. **Ethereum verification pending** - Check `ethereumTxHash` confirmation
2. **Payment tier delay** - STANDARD tier waits 2 epochs (~12.8 min)
3. **Queue backlog** - Check pending count

**Solutions**:
```bash
# Check pending proposals
curl http://localhost:8090/v1/proposals/pending/count

# Check Ethereum transaction (Sepolia mode)
# Use Etherscan or Web3j to verify transaction

# Use PRIORITY tier for immediate processing
curl -X POST ... -d "paymentTier=PRIORITY"
```

### Proposal Rejected

**Symptoms**: `403 Forbidden` or `400 Bad Request`

**Common Causes**:
1. **Path ownership violation** - Path doesn't belong to wallet
2. **Invalid signature** - Signature verification failed
3. **Wallet not registered** - Client not registered

**Solutions**:
```bash
# Verify path ownership
# Path must start with: /oak-chain/{L1}/{L2}/{L3}/0x{wallet}/

# Check signature format
# Must be: walletAddress:timestamp:contentType:message

# Register client
curl -X POST http://localhost:8090/v1/register-client \
  -d "walletAddress=0x..." \
  -d "clientId=my-client"
```

---

## Performance Issues

### Slow Write Throughput

**Symptoms**: High latency, low throughput

**Check**:
```bash
# Check metrics
curl http://localhost:8090/api/metrics | jq .oak_consensus

# Check Aeron metrics
curl http://localhost:8090/v1/aeron/raft-metrics
```

**Common Causes**:
1. **Backpressure** - Queue full, Aeron backpressure active
2. **Network latency** - High latency between validators
3. **Oak commit slow** - FileStore operations slow

**Solutions**:
```bash
# Check backpressure status
curl http://localhost:8090/v1/consensus/status | jq .backpressure

# Reduce proposal rate
# Or increase cluster size

# Check disk I/O
iostat -x 1
```

### High Memory Usage

**Symptoms**: OutOfMemoryError, high heap usage

**Check**:
```bash
# JVM metrics
curl http://localhost:8090/metrics | grep jvm_memory

# Heap dump
jmap -dump:format=b,file=heap.hprof <pid>
```

**Common Causes**:
1. **Large proposal queue** - Too many pending proposals
2. **Segment cache** - Large segment cache
3. **Memory leak** - Check for leaks

**Solutions**:
```bash
# Reduce queue size (configuration)
export OAK_PROPOSAL_QUEUE_MAX_SIZE=1000

# Reduce segment cache
export OAK_SEGMENT_CACHE_SIZE=256

# Check for leaks
jmap -histo <pid> | head -20
```

---

## Network Issues

### Validators Can't Connect

**Symptoms**: Quorum lost, cluster unhealthy

**Check**:
```bash
# Check network connectivity
ping validator-1
telnet validator-1 20110

# Check Aeron ports
netstat -an | grep 20110
```

**Common Causes**:
1. **Firewall blocking** - UDP ports blocked
2. **Wrong IP addresses** - Validators using wrong IPs
3. **Network partition** - Validators on different networks

**Solutions**:
```bash
# Check firewall
sudo ufw status

# Verify Aeron cluster members
echo $AERON_CLUSTER_MEMBERS

# Use correct IP addresses
export AERON_CLUSTER_MEMBERS="0=10.0.1.10:20110,1=10.0.2.10:20111,2=10.0.3.10:20112"
```

---

## IPFS Issues

### IPFS Not Available

**Symptoms**: Binary uploads fail, IPFS errors

**Check**:
```bash
# Check IPFS node
ipfs id

# Check IPFS API
curl http://localhost:5001/api/v0/version
```

**Common Causes**:
1. **IPFS daemon not running** - Start IPFS
2. **Wrong endpoint** - Check `IPFS_API_ENDPOINT`
3. **IPFS node unreachable** - Network issue

**Solutions**:
```bash
# Start IPFS
ipfs daemon &

# Verify endpoint
export IPFS_API_ENDPOINT=/ip4/127.0.0.1/tcp/5001

# Test IPFS
curl http://localhost:5001/api/v0/version
```

---

## Debugging Tips

### Enable Debug Logging

```bash
export LOG_LEVEL=DEBUG
java -jar oak-segment-consensus.jar
```

### Check Logs

```bash
# Follow logs
tail -f /var/log/oak-validator.log

# Search for errors
grep ERROR /var/log/oak-validator.log

# Search for specific proposal
grep "proposalId-uuid" /var/log/oak-validator.log
```

### Use Dashboard

Access dashboard at `http://localhost:8090/`:
- Cluster state
- Metrics
- API browser
- Content explorer

---

## Common Error Messages

| Error | Cause | Solution |
|-------|-------|----------|
| `Cluster unhealthy: No leader elected` | Leader election in progress | Wait < 5 seconds |
| `Path ownership violation` | Path doesn't belong to wallet | Use correct path |
| `Wallet not registered` | Client not registered | Register client first |
| `Invalid signature` | Signature verification failed | Check signature format |
| `Rate limit exceeded` | Too many requests | Reduce request rate |
| `Proposal not found` | Invalid proposal ID | Check proposal ID |

---

*See [DELETE-QUICK-REFERENCE.md](../../DELETE-QUICK-REFERENCE.md) for delete/GC troubleshooting.*
