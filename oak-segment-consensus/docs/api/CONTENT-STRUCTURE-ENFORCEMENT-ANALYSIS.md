# Content Structure Enforcement Analysis

**Date**: 2026-01-31  
**Status**: Analysis & Decision Framework

## Current State: What We Enforce

### Write Path Enforcement ✅

**What's enforced:**
1. **Wallet signature** - Cryptographic proof of identity
2. **Payment verification** - Ethereum transaction hash verified on-chain
3. **Path format** - Must start with `/oak-chain/{shard}/{wallet}/...`
4. **Path ownership** - Wallet address must match path (can only write to own namespace)
5. **Signature format** - `walletAddress:timestamp:contentType:message`

**What's NOT enforced:**
- ❌ Content structure (JSON schema, node types, properties)
- ❌ Content validation (required fields, data types)
- ❌ Content relationships (parent-child constraints)
- ❌ Content semantics (meaning, business rules)

### Current Content Storage

```java
// WriteApplicationService.java:286
contentNode.setProperty("jcr:primaryType", "nt:unstructured");
contentNode.setProperty("contentType", contentType != null ? contentType : "page");
contentNode.setProperty("message", message != null ? message : "");
contentNode.setProperty("timestamp", System.currentTimeMillis());
contentNode.setProperty("wallet", walletAddress);
contentNode.setProperty("signature", signature);
```

**Observation**: Content is stored as **flat properties** on `nt:unstructured` nodes. No structure validation.

---

## API Flow Mapping

### oak-segment-consensus → oak-chain-connector → oak-chain-sdk

```
┌─────────────────────────────────────────────────────────────────┐
│                    API FLOW DIAGRAM                            │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  oak-chain-sdk (JavaScript/TypeScript)                        │
│         │                                                       │
│         │ POST /v1/propose-write                               │
│         │ {                                                     │
│         │   wallet, signature, path, content,                  │
│         │   paymentTier, txHash                                 │
│         │ }                                                     │
│         ▼                                                       │
│  oak-chain-connector (AEM OSGi Bundle)                        │
│         │                                                       │
│         │ POST /v1/propose-write                               │
│         │ (same API, different client)                          │
│         ▼                                                       │
│  oak-segment-consensus (Validator HTTP API)                   │
│         │                                                       │
│         │ Validates:                                            │
│         │ ✅ Wallet signature                                   │
│         │ ✅ Payment (Ethereum tx)                              │
│         │ ✅ Path format & ownership                            │
│         │ ❌ Content structure                                  │
│         │                                                       │
│         │ Stores:                                                │
│         │ - Flat properties on nt:unstructured                  │
│         │ - No schema validation                                │
│         │                                                       │
│         ▼                                                       │
│  Oak Segment Store (TAR files)                                 │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

### Current API Contracts

#### oak-chain-sdk → oak-segment-consensus

**TypeScript Interface** (`oak-chain-sdk/src/types/index.ts`):
```typescript
interface WriteProposal {
  wallet: WalletAddress;
  organization: string;
  path: string;
  content: ContentNode;  // ← No schema enforced
  paymentTier: PaymentTier;
  txHash: TransactionHash;
  signature: Signature;
  timestamp: number;
}
```

**HTTP Request** (`POST /v1/propose-write`):
```
Content-Type: application/x-www-form-urlencoded

walletAddress=0x...
signature=0x...
message={...}  // ← JSON string, no validation
contentPath=/oak-chain/...
ethereumTxHash=0x...
paymentTier=EXPRESS
```

#### oak-chain-connector → oak-segment-consensus

**Java Service** (`SlingWriteProposalService.java`):
```java
public WriteResult proposeWrite(String contentType, String message) {
    // Signs: walletAddress:timestamp:contentType:message
    // POSTs to /v1/propose-write
    // No content structure validation
}
```

**Same API endpoint** - connector and SDK use identical HTTP API.

---

## The Question: Do We Need Content Structure Enforcement?

### Arguments FOR Enforcement

1. **Data Integrity**: Prevents malformed content from entering the system
2. **Queryability**: Structured data is easier to query/index
3. **Compatibility**: Ensures content works with AEM tooling (Composum, etc.)
4. **Validation**: Catches errors early (client-side vs consensus-time)
5. **Standards**: Enables content portability across systems

### Arguments AGAINST Enforcement (Rick Rubin Ethos)

1. **Minimalism**: "Add the minimum necessary layer"
   - Current: Wallet + Payment + Path = sufficient for consensus
   - Adding structure validation = another layer

2. **Flexibility**: Content structure evolves
   - Enforcing schema locks us into current assumptions
   - Different use cases need different structures

3. **Oak's Nature**: Oak is schema-optional by design
   - `nt:unstructured` exists for a reason
   - AEM uses it extensively for flexible content

4. **First Principles**: What's the fundamental constraint?
   - **Consensus needs**: Who wrote it? Did they pay? Is path valid?
   - **Consensus doesn't need**: What the content means

5. **Utility × Impact**: 
   - **High cost**: Schema validation, versioning, migration
   - **Low impact**: Most content works fine without it
   - **Exception**: AEM integration might need it (but that's client-side)

### The Rick Rubin Test

> "The best way to get a good idea is to get a lot of ideas and throw the bad ones away."

**Question**: Is content structure enforcement a good idea or a bad idea?

**Analysis**:
- **Good idea IF**: It solves a real problem (data corruption, queryability)
- **Bad idea IF**: It's "nice to have" but adds complexity without clear benefit

**Current reality**: Content works fine without structure enforcement. Oak stores it. Clients read it. Consensus is achieved.

**Verdict**: **Don't enforce** unless there's a specific problem it solves.

---

## Recommendation: Minimal Enforcement, Maximum Flexibility

### What to Enforce (Keep Current)

✅ **Write path** - Wallet signature, payment, path ownership  
✅ **Basic format** - Path format, signature format  
✅ **Oak compatibility** - Store as `nt:unstructured` (Oak's flexible type)

### What NOT to Enforce (Rick Rubin Minimalism)

❌ **Content schema** - Let clients structure content as needed  
❌ **JSON validation** - If message is JSON, accept it as-is  
❌ **Node types** - Don't force `nt:file`, `nt:folder`, etc.  
❌ **Property validation** - Don't require specific properties

### Optional: Client-Side Validation

**For AEM integration** (`oak-chain-connector`):
- Connector can validate content structure before sending
- This is **client-side**, not consensus-layer
- Keeps consensus minimal, validation where it's needed

**For SDK** (`oak-chain-sdk`):
- SDK can provide TypeScript types for type safety
- Runtime validation is optional (opt-in)
- Doesn't enforce at consensus layer

---

## API Mapping: Current vs Proposed

### Current State

| Component | Enforces | Stores |
|-----------|----------|--------|
| **oak-segment-consensus** | Wallet, Payment, Path | `nt:unstructured` with flat properties |
| **oak-chain-connector** | None (passes through) | N/A (read-only mount) |
| **oak-chain-sdk** | TypeScript types (compile-time) | N/A (HTTP client) |

### Proposed State (No Change)

| Component | Enforces | Stores |
|-----------|----------|--------|
| **oak-segment-consensus** | Wallet, Payment, Path | `nt:unstructured` with flat properties |
| **oak-chain-connector** | Optional client-side validation | N/A (read-only mount) |
| **oak-chain-sdk** | TypeScript types (compile-time) | N/A (HTTP client) |

**Decision**: **Keep current state**. Don't add structure enforcement at consensus layer.

---

## First Principles Analysis

### What Are the Atoms?

1. **Consensus needs**: Who wrote it? Did they pay? Is path valid?
2. **Storage needs**: Can Oak store it? (Yes - `nt:unstructured` handles anything)
3. **Client needs**: Can clients read it? (Yes - JCR API works)

### What Are the Constraints?

- **Physics**: None - Oak can store any structure
- **Engineering**: Adding validation adds complexity, testing, maintenance
- **Business**: Different use cases need different structures
- **Indexing**: ⚠️ **Oak indexes require consistent structure** - This is the key constraint

**The indexing constraint changes everything**:
- If content is unstructured chaos, queries fail
- Indexes expect consistent property names
- Cross-wallet queries need some commonality
- **Solution**: Namespace-level style guides (per wallet/shard)

### What Would Optimal Look Like?

**If cost didn't exist**: Full schema validation, versioning, migration tools

**If we cut 90%**: Just wallet + payment + path (current state)

**Current state = optimal** for consensus layer. Validation belongs in clients.

---

## Revised Analysis: Namespace-Level Style Guides

### The Indexing Constraint

**Critical insight**: Upstream Oak indexes require consistent structure to function efficiently.

**Current indexes** (`oak-chain-indexes-repoinit.txt`):
- **Wallet index**: Expects `wallet`, `contentType`, `timestamp` properties
- **Property indexes**: Expect consistent property names across content
- **Full-text index**: Expects searchable content in consistent fields

**Problem**: If content is "wild west slop" with inconsistent structure:
- ❌ **Queries WILL fail** - Oak query engine aborts with read limits (no index hits = full traversal = limit exceeded)
- ❌ Full-text search doesn't work (no consistent fields)
- ❌ Aggregations break (missing properties)
- ❌ Cross-wallet queries impossible (different structures)

**Example of broken queries**:
```sql
-- This works if all content has 'contentType' property
SELECT * FROM [nt:unstructured] WHERE [contentType] = 'page'

-- This FAILS if some content uses 'type', others use 'contentType', others have neither
SELECT * FROM [nt:unstructured] WHERE [contentType] = 'page'
-- Result: Oak query engine aborts with read limit exceeded (no index hits = full traversal = limit exceeded)
```

**Critical**: Oak Lucene indexing is a niche topic. Without consistent property names, indexes can't be built effectively, and queries abort rather than returning partial results.

**The constraint**: Indexes need **some** consistency. Not consensus-wide (different brands need different structures), but **namespace-wide** (each wallet/brand maintains consistency within their namespace).

**Important note**: Indexing is an **upstream concern** (not handled at consensus layer). An Oak index layer may be added in time per need, but for now, namespace-level style guides ensure queries work. Oak queries **WILL fail** (abort with read limits) if indexes can't be used due to inconsistent structure.

**Current indexes** (`oak-chain-indexes-repoinit.txt`):
- Wallet index: `wallet`, `contentType`, `timestamp` properties
- Property indexes: Expect consistent property names
- Full-text index: Expects searchable content structure

**Problem**: If content is "wild west slop" with inconsistent structure:
- ❌ Queries fail or are slow (no index hits)
- ❌ Full-text search doesn't work (no consistent fields)
- ❌ Aggregations break (missing properties)
- ❌ Cross-wallet queries impossible (different structures)

### The Solution: Namespace-Level Style Guides

**Not consensus-wide enforcement** - but **per-wallet/shard style guides** enforced by brand maintainers.

```
┌─────────────────────────────────────────────────────────────────┐
│                    CONSENSUS LAYER                              │
│  ✅ Enforces: Wallet, Payment, Path                             │
│  ❌ Does NOT enforce: Content structure                         │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│              NAMESPACE LAYER (Per Wallet/Shard)                 │
│                                                                 │
│  Wallet: 0x742d... (Brand Maintainer)                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Style Guide (Enforced by Brand Maintainer):             │   │
│  │ - Pages: {title, body, author, publishedDate}           │   │
│  │ - Assets: {name, mimeType, ipfsCid, altText}            │   │
│  │ - Products: {sku, name, price, description}             │   │
│  │                                                          │   │
│  │ Enforcement: Client-side validation before write        │   │
│  │ Rejection: Invalid structure rejected at API layer      │   │
│  └─────────────────────────────────────────────────────────┘   │
│                                                                 │
│  Wallet: 0xabcd... (Different Brand)                          │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │ Different Style Guide:                                   │   │
│  │ - Articles: {headline, byline, content, tags}          │   │
│  │ - Media: {title, url, thumbnail, duration}             │   │
│  └─────────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────────┘
                            │
                            ▼
┌─────────────────────────────────────────────────────────────────┐
│                    INDEXING LAYER                               │
│  Oak Lucene indexes work because each namespace has            │
│  consistent structure (per style guide)                        │
└─────────────────────────────────────────────────────────────────┘
```

### Implementation: Style Guide Enforcement

**Option 1: Client-Side Validation (Recommended)**

Brand maintainer provides style guide (JSON schema, TypeScript types, etc.). Clients validate before sending:

```typescript
// oak-chain-sdk with style guide
const styleGuide = await fetch(`/style-guides/${wallet}`);
const validator = new StyleGuideValidator(styleGuide);

if (!validator.validate(content)) {
  throw new Error("Content doesn't match style guide");
}

// Only send if valid
await client.proposeWrite({...});
```

**Option 2: Validator-Side Validation (Optional)**

Validators can enforce style guides per wallet (configurable):

```java
// Validator config
{
  "wallet": "0x742d...",
  "styleGuide": {
    "pages": {
      "required": ["title", "body"],
      "optional": ["author", "publishedDate"]
    }
  }
}

// Validation in ConsensusApiHandler
if (styleGuide != null && !styleGuide.validate(content)) {
  return Response.status(400).entity("Content doesn't match style guide");
}
```

**Recommendation**: **Option 1** (client-side) keeps consensus minimal. Option 2 (validator-side) provides stronger enforcement but adds complexity.

### Style Guide Format (Proposed)

**JSON Schema** (for validation):
```json
{
  "wallet": "0x742d...",
  "version": "1.0",
  "contentTypes": {
    "page": {
      "required": ["title", "body"],
      "optional": ["author", "publishedDate", "tags"],
      "properties": {
        "title": {"type": "string", "maxLength": 200},
        "body": {"type": "string"},
        "author": {"type": "string"},
        "publishedDate": {"type": "number"},
        "tags": {"type": "array", "items": {"type": "string"}}
      }
    },
    "asset": {
      "required": ["name", "ipfsCid"],
      "optional": ["mimeType", "altText", "width", "height"]
    }
  }
}
```

**TypeScript Types** (for SDK):
```typescript
interface BrandStyleGuide {
  wallet: WalletAddress;
  contentTypes: {
    [type: string]: {
      required: string[];
      optional: string[];
      properties: Record<string, PropertyDefinition>;
    };
  };
}

// SDK validates against style guide before sending
const validator = new StyleGuideValidator(styleGuide);
if (!validator.validate(content, contentType)) {
  throw new Error(`Content doesn't match style guide for ${contentType}`);
}
```

**Registry Location**: Style guides stored at `/oak-chain/{wallet}/.style-guide` (self-documenting)

---

## Conclusion

**Do we enforce content structure?** ⚠️ **Namespace-level, not consensus-wide**

**Do we need to?** ✅ **Yes** - For indexing to work efficiently

**Does it matter?** ✅ **Critical** - Without structure, queries fail

**Does it align with Rick Rubin ethos?** ✅ **Yes** - Minimal consensus enforcement, style guides at namespace level

**Recommendation**: 
- **Keep consensus layer minimal** (wallet, payment, path)
- **Add namespace-level style guides** (enforced by brand maintainers)
- **Client-side validation** (SDK/connector validates against style guide)
- **Optional validator-side validation** (for stronger enforcement)
- **Indexing is upstream** - An Oak index layer may be added in time per need, but style guides ensure queries work now

---

## Next Steps

1. ✅ **Document current state** (this document)
2. ⚠️ **Design style guide format** (JSON schema, TypeScript types, etc.)
3. ⚠️ **Add style guide registry** (per-wallet style guides)
4. ⚠️ **Implement client-side validation** (SDK validates before sending)
5. ⚠️ **Optional: Validator-side validation** (configurable per wallet)
6. ⚠️ **Document indexing requirements** (what properties indexes expect)
7. ⚠️ **Consider Oak index layer** (upstream concern, may be added in time per need)

**Status**: Analysis updated. Namespace-level style guides required for indexing. Indexing is upstream - Oak index layer may be added in time per need, but style guides ensure queries work now.

**Note**: Access to Oak core maintainers available for accurate indexing guidance as needed.
