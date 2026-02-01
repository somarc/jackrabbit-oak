# Integration Guide

**How to integrate oak-segment-consensus with other systems**

---

## Sling Author Integration

### Setup

1. **Configure Sling** (`sling.properties`)
   ```properties
   oak.global.store.url=http://localhost:8090
   oak.composite.mount.oak-chain.role=composite-mount-oak-chain
   ```

2. **Mount Global Store**
   - Sling automatically mounts `/oak-chain` as read-only
   - Uses HTTP segment transfer
   - Polls `/journal.log` for updates

3. **Register Client**
   ```bash
   curl -X POST http://localhost:8090/v1/register-client \
     -d "walletAddress=0x..." \
     -d "clientId=sling-author-1"
   ```

### Write from Sling

Sling authors submit writes via HTTP:

```java
// In Sling component
String validatorUrl = "http://localhost:8090";
String proposalId = submitWriteProposal(validatorUrl, wallet, signature, content);
```

---

## HTTP Client Integration

### Basic Client

```java
public class OakChainClient {
    private final String validatorUrl;
    private final String walletAddress;
    
    public String proposeWrite(String content, String path) {
        String signature = signMessage(walletAddress + ":" + timestamp + ":page:" + content);
        
        Map<String, String> params = new HashMap<>();
        params.put("walletAddress", walletAddress);
        params.put("signature", signature);
        params.put("message", content);
        params.put("contentPath", path);
        params.put("ethereumTxHash", "0xtest...");
        
        // POST to /v1/propose-write
        return httpClient.post(validatorUrl + "/v1/propose-write", params);
    }
}
```

### Using SDK

See `oak-chain-sdk` for higher-level client library.

---

## Docker Deployment

### Single Validator

```dockerfile
FROM openjdk:11-jre-slim
COPY oak-segment-consensus.jar /app/
WORKDIR /app
EXPOSE 8090
CMD ["java", "-jar", "oak-segment-consensus.jar"]
```

### Multi-Validator Cluster

Use `docker-compose`:

```yaml
version: '3.8'
services:
  validator-0:
    image: oak-global-store:latest
    environment:
      - AERON_NODE_ID=0
      - AERON_CLUSTER_MEMBERS=0=validator-0:20110,1=validator-1:20111,2=validator-2:20112
      - PORT=8090
    ports:
      - "8090:8090"
```

See `blockchain-aem-infra/docker-compose/` for complete examples.

---

## Kubernetes Deployment

### StatefulSet

```yaml
apiVersion: apps/v1
kind: StatefulSet
metadata:
  name: oak-validator
spec:
  serviceName: oak-validator
  replicas: 3
  template:
    spec:
      containers:
      - name: validator
        image: oak-global-store:latest
        env:
        - name: AERON_NODE_ID
          valueFrom:
            fieldRef:
              fieldPath: metadata.name
        - name: AERON_CLUSTER_MEMBERS
          value: "0=oak-validator-0:20110,1=oak-validator-1:20111,2=oak-validator-2:20112"
```

See `blockchain-aem-infra/modes/sepolia/kubernetes/` for complete manifests.

---

## Monitoring Integration

### Prometheus

Metrics exposed at `/metrics`:

```yaml
scrape_configs:
  - job_name: 'oak-validator'
    static_configs:
      - targets: ['localhost:8090']
```

### Grafana Dashboard

Import dashboard from `blockchain-aem-infra/shared/monitoring/grafana/`.

---

## Load Balancer Integration

### Health Check

```bash
# Health check endpoint
curl http://localhost:8090/health

# Deep health check
curl http://localhost:8090/health/deep
```

### Leader Routing

```bash
# Get leader URL
LEADER_URL=$(curl -s http://localhost:8090/v1/consensus/status | jq -r .leaderUrl)

# Route writes to leader (optional - Aeron handles this)
curl -X POST $LEADER_URL/v1/propose-write ...
```

**Note**: Aeron automatically routes writes to leader, so load balancer can route to any validator.

---

## CI/CD Integration

### Build & Test

```yaml
# GitHub Actions example
- name: Build
  run: mvn clean install -pl oak-segment-consensus -DskipTests

- name: Test
  run: mvn test -pl oak-segment-consensus

- name: Build Docker
  run: docker build -t oak-global-store:${{ github.sha }} .
```

---

*See [blockchain-aem-infra](../blockchain-aem-infra/README.md) for deployment examples.*
