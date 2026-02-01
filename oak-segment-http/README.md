# Oak Segment HTTP

**Status**: Production-Ready (POC)
**Purpose**: HTTP-based remote segment persistence for validator cross-cluster operations
**Part of**: Blockchain AEM POC
**Package**: `org.apache.jackrabbit.oak.segment.http`

## Overview

`oak-segment-http` provides an HTTP-based implementation of Oak's `SegmentNodeStorePersistence` SPI, enabling **validators** to mount read-only views of other clusters' content stores via HTTP segment transfer for cross-cluster reads and shard routing.

**Key Characteristics:**
- Uses **only public Oak SPIs** (no internal package dependencies)
- Designed for **cross-cluster reads** in validator infrastructure
- Used by `oak-segment-consensus` for **shard routing** and multi-cluster operations
- Supports **lazy mounting** for resilient startup when remote clusters are unavailable

## Important: AEM Customers Use `oak-chain-connector`

**⚠️ For AEM Integration**: AEM customers should use **[`oak-chain-connector`](../../oak-chain-connector/README.md)** instead of this module.

**Why Two Modules?**
- **`oak-segment-http`** (this module): Used by validators for cross-cluster operations (Apache package namespace)
- **`oak-chain-connector`**: AEM-compatible add-on with renamed packages (`com.oakchain.connector.*`)

The connector is a migration of this module's code with AEM-compatible package names. This module remains in the fork because validators need it for internal shard routing operations.

## Architecture

```
Sling Author Instance
├── Composite NodeStore
│   ├── Default Mount (local, read-write)
│   └── /oak-chain Mount (HTTP, read-only)
│       └── HttpPersistenceService
│
├── Wallet Services
│   ├── SlingAuthorWalletService (secp256k1 keypair)
│   ├── SlingWriteProposalService (signed writes)
│   ├── SlingDeleteProposalService (signed deletes)
│   └── SlingAuthorRegistrationService (validator reg)
│
└── HTTP Connection ──────────────────────────────────────►
                                                           │
                    Validator (oak-segment-consensus)      │
                    ├── GET /journal.log                   │
                    ├── GET /manifest                      │
                    ├── GET /segments/{id}                 │
                    ├── POST /v1/register-client           │
                    ├── POST /v1/propose-write             │
                    └── POST /v1/propose-delete            │
```

## Components

### Core Persistence Layer

| Class | Purpose |
|-------|---------|
| HttpPersistence | Implements SegmentNodeStorePersistence SPI for HTTP-based segment access |
| HttpPersistenceService | OSGi component that configures and exposes HttpPersistence |
| HttpSegmentArchiveManager | Manages segment archive reading via HTTP |
| HttpSegmentArchiveReader | Reads individual segments from remote validator |
| HttpJournalFile | Reads journal.log from remote validator |
| HttpManifestFile | Reads manifest from remote validator |
| HttpGCJournalFile | Reads GC journal from remote validator |
| HttpClientPool | Connection pooling for HTTP requests (200 max, 50 per route) |

### Wallet Services

| Class | Purpose |
|-------|---------|
| SlingAuthorWalletService | Manages secp256k1 keypair for Sling author identity |
| SlingAuthorRegistrationService | Registers Sling author with validator on startup |
| SlingWriteProposalService | Submits signed write proposals to validators |
| SlingDeleteProposalService | Submits signed delete proposals to validators |
| EthereumWallet | Ethereum wallet utilities (signature generation) |
| ValidatorAuthHelper | Adds authentication headers when token auth is configured |

## Configuration

### HttpPersistenceService

```json
{
  "globalStoreUrl": "http://validator-0:8090",
  "role": "composite-mount-oak-chain",
  "lazyMount": true,
  "healthCheckIntervalSeconds": 10
}
```

| Property | Description | Default |
|----------|-------------|---------|
| globalStoreUrl | URL of the validator's HTTP server | Required |
| role | Service role (must match SegmentNodeStoreFactory config) | Required |
| lazyMount | If true, defer mount until validator is reachable | false |
| healthCheckIntervalSeconds | Health check interval in lazy mode | 10 |

### SlingAuthorWalletService

```json
{
  "keystorePath": "sling/wallet/keystore.json",
  "keystorePassword": "${env:WALLET_PASSWORD}"
}
```

### SlingWriteProposalService

```json
{
  "validatorUrl": "http://validator-0:8090",
  "defaultPaymentTier": "standard"
}
```

## Usage

### 1. Deploy Bundle

The bundle is automatically deployed when using the Sling Starter with blockchain features:

```bash
# Build and install to local Maven repo
cd jackrabbit-oak
mvn clean install -pl oak-segment-http -am -DskipTests
```

### 2. Configure Composite Mount

In Sling, configure the composite NodeStore to use HTTP persistence:

**SegmentNodeStoreFactory~oak-chain.cfg.json:**
```json
{
  "role": "composite-mount-oak-chain",
  "customSegmentStore": true
}
```

**HttpPersistenceService.cfg.json:**
```json
{
  "globalStoreUrl": "http://validator-0:8090",
  "role": "composite-mount-oak-chain",
  "lazyMount": true
}
```

### 3. Access Blockchain Content

Once configured, blockchain content is accessible at /oak-chain:

```java
// In Sling servlet or component
ResourceResolver resolver = request.getResourceResolver();
Resource blockchainContent = resolver.getResource("/oak-chain/content");
```

## Lazy Mount Mode

When lazyMount=true, the service behaves as follows:

1. **Startup**: Sling starts immediately without blocking
2. **Background Check**: Health check thread polls validator every N seconds
3. **Mount Trigger**: When validator responds, SegmentNodeStorePersistence service is registered
4. **Composite Activation**: SegmentNodeStoreFactory detects service and creates composite mount

**Benefits:**
- Sling starts even if validators are down
- No startup timeout waiting for network
- Graceful degradation (local content works, /oak-chain unavailable)

## Write Flow

```
1. Sling Author creates content
   └── SlingWriteProposalService.proposeWrite(path, content, tier)

2. Service signs transaction
   └── SlingAuthorWalletService.sign(proposalHash)

3. Submit to validator
   └── POST /v1/propose-write
       {
         "walletAddress": "0x...",
         "signature": "0x...",
         "contentType": "text",
         "message": "...",
         "paymentTier": "standard"
       }

4. Validator processes via consensus
   └── Aeron Cluster replicates to all validators

5. Content appears in /oak-chain
   └── HttpPersistence fetches new segments
```

## Dependencies

This module depends only on **public Oak SPIs**:

- org.apache.jackrabbit.oak.segment.spi.persistence.*
- org.apache.jackrabbit.oak.spi.state.*
- Apache HttpClient (for HTTP requests)
- OSGi annotations (for service registration)

**No dependencies on:**
- org.apache.jackrabbit.oak.segment.file.* (internal)
- org.apache.jackrabbit.oak.segment.SegmentNodeStore (internal)

## Related Modules

| Module | Relationship |
|--------|--------------|
| oak-segment-consensus | **Uses this module** for cross-cluster reads and shard routing (`LazyHttpNodeStore`) |
| oak-chain-connector | **AEM-compatible version** - migrated code with renamed packages for AEM customers |
| oak-segment-tar | Provides SegmentNodeStoreFactory that uses this persistence |
| oak-store-composite | Provides CompositeNodeStore that mounts this |
| oak-auth-web3 | Provides wallet authentication for Sling |

## Usage in Validator Infrastructure

This module is used by `oak-segment-consensus` for:

1. **Cross-Cluster Reads**: Validators mount other clusters as read-only HTTP stores
2. **Shard Routing**: `LazyHttpNodeStore` uses `HttpPersistence` to connect to remote shards
3. **Multi-Cluster Architecture**: Each cluster reads from other clusters via HTTP segment transfer

**Example** (`LazyHttpNodeStore.java`):
```java
import org.apache.jackrabbit.oak.segment.http.HttpPersistence;

// Creates HTTP persistence for cross-cluster reads
HttpPersistence persistence = new HttpPersistence(endpoint);
```

**Dependency** (`oak-segment-consensus/pom.xml`):
```xml
<dependency>
    <groupId>org.apache.jackrabbit</groupId>
    <artifactId>oak-segment-http</artifactId>
    <version>${project.version}</version>
</dependency>
```

## Documentation

- **ADR 005**: HTTP Segment Transfer decision (Blockchain-AEM/adr/)
- **oak-segment-consensus README**: Validator server documentation
- **Blockchain AEM Architecture**: Overall architecture docs (Blockchain-AEM/02-architecture/)

## License

Apache License 2.0 - See LICENSE for details.

---

**Part of**: Blockchain AEM POC
**Repository**: jackrabbit-oak (feature/blockchain-aem-poc branch)
