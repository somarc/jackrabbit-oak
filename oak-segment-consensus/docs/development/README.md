# Development Guide

**How to extend and modify oak-segment-consensus**

---

## Development Setup

### Prerequisites

- Java 11+
- Maven 3.8+
- Docker (for local testing)

### Build

```bash
cd jackrabbit-oak
mvn clean install -pl oak-segment-consensus -am -DskipTests -Dbaseline.skip=true -Drat.skip=true
```

### Run Locally

```bash
cd oak-segment-consensus
java -jar target/oak-segment-consensus.jar
```

### Runtime Knobs and Gears

- [Blockchain Config Knobs and Gears](BLOCKCHAIN-CONFIG-KNOBS.md) - OSGi/env/system precedence and API introspection mapping used by dashboard consumers.
- [ADR 077 Contract Review Follow-Up](ADR-077-CONTRACT-REVIEW-FOLLOW-UP.md) - Oak-side backend changes that require separate smart contract and SDK review.

---

## Code Structure

### Entry Points

- **`GlobalStoreServer.java`** - Main server class, orchestrates components
- **`AeronConsensusEngine.java`** - Core consensus engine (~4400 lines)
- **`SegmentHttpServer.java`** - HTTP server setup
- **`RequestRouter.java`** - HTTP request routing

### Adding a New API Endpoint

1. **Create Handler** (`http/server/handlers/`)
   ```java
   public class MyApiHandler {
       public void handleMyEndpoint(HttpServletRequest request, HttpServletResponse response) {
           // Your logic
       }
   }
   ```

2. **Register Route** (`RequestRouter.java`)
   ```java
   if ("/v1/my-endpoint".equals(path) && "GET".equals(method)) {
       myApiHandler.handleMyEndpoint(request, response);
       baseRequest.setHandled(true);
       return;
   }
   ```

3. **Add to Dashboard** (`DashboardHandler.java`)
   ```java
   addApiEndpoint(html, "GET", "/v1/my-endpoint", "Description", "my_endpoint");
   ```

### Adding a New Proposal Type

1. **Define Template ID** (`SimpleMessageHeader.java`)
   ```java
   public static final int TEMPLATE_ID_MY_PROPOSAL = 106;
   ```

2. **Add to MessageDispatcher** (`MessageDispatcher.java`)
   ```java
   case SimpleMessageHeader.TEMPLATE_ID_MY_PROPOSAL:
       handleMyProposal(timestamp, buffer, offset, length);
       break;
   ```

3. **Implement Handler**
   ```java
   private void handleMyProposal(long timestamp, DirectBuffer buffer, int offset, int length) {
       // Parse proposal
       // Apply deterministically
   }
   ```

### Modifying Proposal Queue

**File**: `ProposalQueueManagerOptimized.java`

**Key Methods**:
- `queueWriteProposal()` - Queue write proposals
- `queueDeleteProposal()` - Queue delete proposals
- `sendWriteBatchThroughIngress()` - Send batch via Aeron

**Epoch Batching**:
- Proposals batched by Ethereum epoch
- Payment tier determines batch timing
- PRIORITY: Immediate
- EXPRESS: 1 epoch (~6.4 min)
- STANDARD: 2 epochs (~12.8 min)

---

## Testing

### Unit Tests

```bash
mvn test -pl oak-segment-consensus
```

### Integration Tests

See [Testing Guide](../testing/README.md)

### Local Testing

1. **Start Validator**
   ```bash
   java -jar oak-segment-consensus.jar
   ```

2. **Test API**
   ```bash
   curl http://localhost:8090/v1/consensus/status
   ```

3. **Check Logs**
   ```bash
   tail -f /var/log/oak-validator.log
   ```

---

## Code Style

### Logging Conventions

```java
// Use emoji prefixes for visual scanning
log.info("✅ Write applied successfully");
log.warn("⚠️  Warning message");
log.error("❌ Error occurred", e);
log.debug("🔍 Debug information");
```

### Error Handling

```java
// Always return descriptive error messages
response.sendError(HttpServletResponse.SC_FORBIDDEN, 
    String.format("Path ownership violation: Content at %s does not belong to wallet %s",
                 contentPath, wallet));
```

### Deterministic Operations

**Critical**: All operations in `onSessionMessage()` must be deterministic:

```java
// ✅ GOOD: Deterministic
public void applyReplicatedWrite(String wallet, String path, String content) {
    NodeBuilder node = nodeStore.getRoot().getChildNode(path);
    node.setProperty("content", content);
    nodeStore.merge(node, EmptyHook.INSTANCE, CommitInfo.EMPTY);
}

// ❌ BAD: Non-deterministic (uses current time)
public void applyReplicatedWrite(String wallet, String path, String content) {
    NodeBuilder node = nodeStore.getRoot().getChildNode(path);
    node.setProperty("timestamp", System.currentTimeMillis()); // WRONG!
}
```

---

## Common Tasks

### Adding a New Configuration Option

1. **Define the config surface**
   - Add or extend an `@ObjectClassDefinition`
   - Wire the override through the runtime resolver/registry layer
2. **Consume it in the runtime path**
   ```java
   String myConfig = RuntimeConfigValueResolver.readString("oak.my.config", "OAK_MY_CONFIG", "default");
   ```
3. **Expose it in the read-only control plane**
   - Update `OsgiConfigApiHandler` effective values, sources, schema, and coverage
   - Add the endpoint to dashboard/API docs if it changes operator workflow
4. **Document it**
   - Update `CONFIGURATION.md`
   - Update any relevant API or development docs

### Adding Metrics

1. **Define Metric** (`ConsensusMetrics.java`)
   ```java
   public final Counter myCounter = Counter.build()
       .name("oak_consensus_my_counter")
       .help("My counter metric")
       .register();
   ```

2. **Increment in Code**
   ```java
   metrics.myCounter.inc();
   ```

3. **Expose via `/metrics`** (automatic)

---

## Debugging

### Enable Debug Logging

```bash
export LOG_LEVEL=DEBUG
java -jar oak-segment-consensus.jar
```

### Check Cluster Health

```bash
curl http://localhost:8090/v1/consensus/status
```

### View Aeron Metrics

```bash
curl http://localhost:8090/v1/aeron/raft-metrics
```

---

*See [Troubleshooting](../troubleshooting/README.md) for common issues.*
