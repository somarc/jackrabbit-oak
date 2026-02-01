# API Flow Mapping: oak-segment-consensus ↔ oak-chain-connector ↔ oak-chain-sdk

**Date**: 2026-01-31  
**Purpose**: Complete mapping of APIs and data flow between all three components

---

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────────────┐
│                         CLIENT LAYER                                    │
├─────────────────────────────────────────────────────────────────────────┤
│                                                                         │
│  ┌──────────────────────┐         ┌──────────────────────┐            │
│  │  oak-chain-sdk       │         │  oak-chain-connector │            │
│  │  (JavaScript/TS)     │         │  (AEM OSGi Bundle)   │            │
│  │                      │         │                      │            │
│  │  - REST API client   │         │  - JCR API wrapper   │            │
│  │  - Wallet signing    │         │  - Wallet service    │            │
│  │  - Payment handling  │         │  - Write proposals  │            │
│  │  - TypeScript types  │         │  - Composite mount  │            │
│  └──────────┬───────────┘         └──────────┬───────────┘            │
│             │                                │                        │
│             │ HTTP POST /v1/propose-write   │                        │
│             │                                │                        │
└─────────────┼────────────────────────────────┼──────────────────────┘
              │                                │
              │                                │
┌─────────────┼────────────────────────────────┼──────────────────────┐
│             │                                │                      │
│             ▼                                ▼                      │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │         oak-segment-consensus (Validator HTTP API)           │   │
│  │                                                              │   │
│  │  POST /v1/propose-write                                     │   │
│  │  POST /v1/propose-delete                                    │   │
│  │  GET  /v1/consensus/status                                  │   │
│  │  GET  /v1/proposals/{id}/status                             │   │
│  │  GET  /v1/proposals/pending/count                           │   │
│  │                                                              │   │
│  │  Validation:                                                │   │
│  │  ✅ Wallet signature                                        │   │
│  │  ✅ Payment (Ethereum tx)                                   │   │
│  │  ✅ Path format & ownership                                 │   │
│  │  ❌ Content structure (not enforced)                        │   │
│  └──────────────────────┬───────────────────────────────────────┘   │
│                         │                                           │
│                         │ Aeron Raft Consensus                     │
│                         ▼                                           │
│  ┌──────────────────────────────────────────────────────────────┐   │
│  │              Oak Segment Store (TAR files)                    │   │
│  │                                                              │   │
│  │  Storage: nt:unstructured nodes                             │   │
│  │  Properties: message, contentType, wallet, signature, etc.   │   │
│  └──────────────────────────────────────────────────────────────┘   │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

---

## API Endpoints

### Write Proposal: `POST /v1/propose-write`

**Used by**: Both `oak-chain-sdk` and `oak-chain-connector`

#### Request Format

**Content-Type**: `application/x-www-form-urlencoded` or `multipart/form-data`

**Parameters**:

| Parameter | Type | Required | Description | Enforced |
|-----------|------|----------|-------------|----------|
| `walletAddress` | string | ✅ Yes | Ethereum wallet (0x...) | ✅ Format validation |
| `signature` | string | ✅ Yes | Signed message | ✅ Signature verification |
| `message` | string | ✅ Yes | Content (JSON string or text) | ❌ No structure validation |
| `contentPath` | string | ✅ Yes | Full Oak path | ✅ Path format & ownership |
| `ethereumTxHash` | string | ✅ Yes | Payment transaction hash | ✅ On-chain verification |
| `paymentTier` | string | ⚠️ Optional | STANDARD/EXPRESS/PRIORITY | ✅ Enum validation |
| `contentType` | string | ⚠️ Optional | "page", "asset", etc. | ❌ No validation |
| `organization` | string | ⚠️ Optional | Organization scope (ADR 037) | ❌ No validation |
| `ipfsCid` | string | ⚠️ Optional | IPFS CID for binary | ❌ No validation |
| `file` | binary | ⚠️ Optional | Binary file (multipart) | ❌ No validation |

#### Response Format

**202 Accepted** (proposal queued):
```json
{
  "proposalId": "uuid-123",
  "type": "WRITE",
  "state": "PENDING",
  "contentPath": "/oak-chain/dd/87/0f/0xdd870fa1b7c4700f2bd7f44238821c26f7392148/content/page1",
  "timestamp": 1733421234000,
  "estimatedConfirmationTime": "6.4 minutes"
}
```

**400 Bad Request** (validation error):
```json
{
  "error": "Invalid wallet address format",
  "code": "VALIDATION_ERROR"
}
```

#### Validation Flow

```
Request → Validate Wallet Format
         → Verify Signature
         → Verify Payment (Ethereum)
         → Validate Path Format
         → Check Path Ownership
         → Queue Proposal
         → Return 202 Accepted
```

**Note**: Content structure (`message` field) is **not validated**. It's stored as-is.

---

## Component-Specific Details

### oak-chain-sdk

**Location**: `OAK/oak-chain-sdk/`

**TypeScript Interface**:
```typescript
interface WriteProposal {
  wallet: WalletAddress;
  organization: string;
  path: string;
  content: ContentNode;  // ← TypeScript type, not enforced at runtime
  paymentTier: PaymentTier;
  txHash: TransactionHash;
  signature: Signature;
  timestamp: number;
}
```

**Client Implementation** (`src/client/index.ts`):
```typescript
async proposeWrite(proposal: WriteProposal): Promise<WriteProposalResponse> {
  // Signs message: walletAddress:timestamp:contentType:message
  // POSTs to /v1/propose-write
  // No content structure validation (just TypeScript types)
}
```

**What it does**:
- ✅ Provides TypeScript types for type safety (compile-time)
- ✅ Handles wallet signing (MetaMask integration)
- ✅ Formats HTTP requests
- ❌ Does NOT validate content structure at runtime

---

### oak-chain-connector

**Location**: `OAK/oak-chain-connector/`

**Java Service** (`core/src/main/java/com/oakchain/connector/wallet/SlingWriteProposalService.java`):
```java
public WriteResult proposeWrite(String contentType, String message) {
    // Creates signature: walletAddress:timestamp:contentType:message
    // POSTs to /v1/propose-write
    // No content structure validation
}
```

**What it does**:
- ✅ Provides JCR API wrapper (AEM integration)
- ✅ Handles wallet signing (Sling keystore)
- ✅ Formats HTTP requests
- ❌ Does NOT validate content structure (passes through)

**Read-Only Mount**:
- Connector mounts `/oak-chain` read-only via composite mount
- Uses `HttpPersistence` to fetch segments from validators
- No write validation needed (read-only)

---

### oak-segment-consensus

**Location**: `OAK/jackrabbit-oak/oak-segment-consensus/`

**HTTP Handler** (`src/main/java/.../http/server/handlers/ConsensusApiHandler.java`):
```java
@POST
@Path("/v1/propose-write")
public Response proposeWrite(@FormParam("walletAddress") String wallet,
                            @FormParam("signature") String signature,
                            @FormParam("message") String message,
                            @FormParam("contentPath") String path,
                            // ... other params
                            ) {
    // Validates: wallet format, signature, payment, path
    // Does NOT validate: content structure (message field)
    // Stores: nt:unstructured node with flat properties
}
```

**Storage** (`service/WriteApplicationService.java`):
```java
contentNode.setProperty("jcr:primaryType", "nt:unstructured");
contentNode.setProperty("contentType", contentType);
contentNode.setProperty("message", message);  // ← Stored as-is, no validation
contentNode.setProperty("wallet", walletAddress);
contentNode.setProperty("signature", signature);
```

**What it enforces**:
- ✅ Wallet address format
- ✅ Cryptographic signature
- ✅ Ethereum payment verification
- ✅ Path format (`/oak-chain/{shard}/{wallet}/...`)
- ✅ Path ownership (wallet must match path)
- ❌ Content structure (not enforced)

---

## Data Flow Example

### Example: Writing a Page via SDK

```
1. SDK Client (JavaScript)
   └─> Creates WriteProposal {
         wallet: "0x742d...",
         path: "content/pages/hello",
         content: { title: "Hello", body: "World" },
         paymentTier: "EXPRESS",
         ...
       }
   
2. SDK Signs Message
   └─> signature = sign("0x742d...:1733421234:page:{title:'Hello',body:'World'}")
   
3. SDK POSTs to Validator
   └─> POST /v1/propose-write
       walletAddress=0x742d...
       signature=0xabc123...
       message={"title":"Hello","body":"World"}
       contentPath=/oak-chain/74/2d/0f/0x742d.../content/pages/hello
       ethereumTxHash=0xdef456...
       paymentTier=EXPRESS
   
4. Validator Validates
   └─> ✅ Wallet format OK
       ✅ Signature valid
       ✅ Payment verified (Ethereum)
       ✅ Path format OK
       ✅ Path ownership OK (wallet matches path)
       ❌ Content structure NOT validated (message stored as-is)
   
5. Validator Stores
   └─> Oak Node: /oak-chain/74/2d/0f/0x742d.../content/pages/hello
       Properties:
         jcr:primaryType = "nt:unstructured"
         contentType = "page"
         message = "{\"title\":\"Hello\",\"body\":\"World\"}"  ← Stored as string
         wallet = "0x742d..."
         signature = "0xabc123..."
         timestamp = 1733421234000
   
6. Consensus Replication
   └─> Aeron Raft replicates to all validators
       All validators store identical node
```

---

## Key Observations

### What's Enforced

✅ **Write Path**:
- Wallet signature (cryptographic proof)
- Payment verification (economic proof)
- Path format & ownership (namespace isolation)

### What's NOT Enforced

❌ **Content Structure**:
- No JSON schema validation
- No node type validation
- No property validation
- No relationship validation

### Why This Design?

**Rick Rubin Ethos**: "Add the minimum necessary layer"

- **Consensus needs**: Who wrote it? Did they pay? Is path valid?
- **Consensus doesn't need**: What the content means

**First Principles**:
- **Fundamental constraint**: Consensus must agree on state
- **State = nodes + properties** (Oak's model)
- **Content meaning = client concern**, not consensus concern

---

## Recommendations

### Current State: ✅ Keep As-Is

**Consensus layer**: Minimal enforcement (wallet, payment, path)  
**Client layer**: Optional validation (SDK types, connector checks)

### Future Enhancements (Optional)

1. **Client-side validation** (not consensus):
   - SDK: Runtime validation library (opt-in)
   - Connector: AEM content structure validation (for compatibility)

2. **Content pattern guide** (not enforced):
   - Document recommended structures
   - Examples for common use cases
   - Best practices

3. **Type system** (client-side):
   - Enhanced TypeScript types in SDK
   - Java interfaces in connector
   - Runtime validation libraries (optional)

**Status**: Current design aligns with Rick Rubin ethos. No changes needed.
