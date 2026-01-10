# Oak Segment HTTP

**Status**: Production-Ready (POC)
**Purpose**: HTTP-based remote segment persistence for Blockchain AEM clients
**Part of**: Blockchain AEM POC

## Overview

`oak-segment-http` provides an HTTP-based implementation of Oak's `SegmentNodeStorePersistence` SPI, enabling Sling authors to mount a read-only view of the blockchain validator's global content store via HTTP segment transfer.

**Key Characteristics:**
- Uses **only public Oak SPIs** (no internal package dependencies)
- Designed for **read-only composite mounts** in Sling authors
- Includes **wallet-based write proposal services** for authenticated writes
- Supports **lazy mounting** for resilient startup when validators are unavailable

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
| oak-segment-consensus | Server that this module connects to |
| oak-segment-tar | Provides SegmentNodeStoreFactory that uses this persistence |
| oak-store-composite | Provides CompositeNodeStore that mounts this |
| oak-auth-web3 | Provides wallet authentication for Sling |

## Documentation

- **ADR 005**: HTTP Segment Transfer decision (Blockchain-AEM/adr/)
- **oak-segment-consensus README**: Validator server documentation
- **Blockchain AEM Architecture**: Overall architecture docs (Blockchain-AEM/02-architecture/)

## License

Apache License 2.0 - See LICENSE for details.

---

**Part of**: Blockchain AEM POC
**Repository**: jackrabbit-oak (feature/blockchain-aem-poc branch)
