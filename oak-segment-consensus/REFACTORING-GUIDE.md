# SegmentHttpServer Refactoring Guide

## Current Status

**File Size**: 4,057 lines  
**Completed**: Data models, utilities, HealthHandler, MetricsHandler  
**Remaining**: 9 handlers + router + main server refactor

## Completed Components ✅

1. **Data Models** (`model/` package)
   - `ClientRegistration.java`
   - `ValidatorRegistration.java`
   - `WriteMetadata.java`

2. **Utilities** (`util/` package)
   - `FormatUtils.java` - formatBytes, escapeHtml, escapeJson
   - `JsonParser.java` - extractField, extractObject

3. **Handlers** (`handlers/` package)
   - `HealthHandler.java` - /health, /health/deep

4. **Handlers** (`handlers/` package)
   - `MetricsHandler.java` - /api/metrics, /metrics

## Remaining Handlers to Extract

### 1. FileHandler
**Endpoints**: 
- GET /journal.log
- GET /manifest
- GET /gc.log
- GET /segments/{id}
- HEAD /segments/{id}

**Methods to extract**:
- `handleFile()` (line ~1658)
- `handleSegmentHead()` (line ~2311)
- `handleSegmentGet()` (line ~2326)
- `readSegmentFromTar()` (line ~2375)
- `readSegmentFromTarFile()` (line ~2404)
- `findSegmentInTarFiles()` (line ~2435)

**Dependencies**:
- FileStore
- Path storeDirectory
- Logger

### 2. ExplorerApiHandler
**Endpoints**:
- GET /api/explore?path={path}
- GET /api/segments/recent
- GET /api/segments/tars

**Methods to extract**:
- `handleExploreNode()` (line ~2107)
- `handleRecentSegments()` (line ~2202)
- `handleTarFiles()` (line ~2235)

**Dependencies**:
- NodeStore
- Path storeDirectory
- FormatUtils (for formatBytes, escapeJson)

### 3. DashboardHandler
**Endpoints**:
- GET / (dashboard homepage)
- GET /explorer (explorer UI)
- GET /api-browser (API browser UI)

**Methods to extract**:
- `handleDashboard()` (line ~1046) - **LARGEST METHOD** (~500 lines)
- `handleExplorerUI()` (line ~1692) - **LARGE** (~150 lines)
- `handleApiBrowserUI()` (line ~1848) - **LARGE** (~250 lines)
- `addApiEndpoint()` (line ~2096)

**Dependencies**:
- FileStore
- NodeStore
- ConsensusEngine (all 3 types)
- registeredClients, registeredValidators
- recentWriteMetadata
- selfUrl
- FormatUtils

### 4. ConsensusApiHandler
**Endpoints**:
- POST /v1/propose
- POST /v1/vote
- POST /v1/test-write
- GET /v1/head

**Methods to extract**:
- `handleWriteProposal()` (line ~2452)
- `handleVote()` (line ~2482)
- `handleTestWrite()` (line ~2636) - **VERY LARGE** (~380 lines)
- `parseProposal()` (line ~3910)
- `parseVote()` (line ~3933)
- `voteToJson()` (line ~4045)
- `getNextValidatorInRotation()` (line ~3958)

**Dependencies**:
- ConsensusEngine
- DagConsensusEngine
- LeaderConsensusEngine
- FileStore
- NodeStore
- registeredClients
- recentWriteMetadata
- selfUrl
- JsonParser

### 5. RegistrationHandler
**Endpoints**:
- POST /v1/register-client
- POST /v1/register-validator

**Methods to extract**:
- `handleClientRegistration()` (line ~3028)
- `handleValidatorRegistration()` (line ~3106)

**Dependencies**:
- registeredClients (Map)
- registeredValidators (Map)
- selfUrl
- JsonParser

### 6. PeerDiscoveryHandler
**Endpoints**:
- GET /v1/peers
- GET /v1/ngrok-url

**Methods to extract**:
- `handlePeerList()` (line ~3195) - **LARGE** (~140 lines)
- `extractValidatorId()` (line ~3338)

**Dependencies**:
- LeaderConsensusEngine
- registeredValidators
- selfUrl
- FormatUtils (for escapeJson)

### 7. LeaderConsensusHandler
**Endpoints**:
- POST /v1/follower/head-update
- POST /v1/heartbeat
- POST /v1/consensus/peer-joined
- POST /v1/consensus/claim-leadership
- POST /v1/consensus/claim-ack
- POST /v1/consensus/request-handover
- POST /v1/consensus/register-public-key

**Methods to extract**:
- `handleFollowerHeadUpdate()` (line ~3428)
- `handleHeartbeat()` (line ~3499)
- `handlePeerJoined()` (line ~3585) - **LARGE** (~100 lines)
- `handleLeadershipClaim()` (line ~3706) - **LARGE** (~80 lines)
- `handleClaimAck()` (line ~3795)
- `handleHandoverRequest()` (line ~4160) - **NEEDS TO BE FOUND**
- `handlePublicKeyRegistration()` (line ~3861)

**Dependencies**:
- LeaderConsensusEngine
- registeredValidators
- proofVerifier
- selfUrl
- myValidatorId, myValidatorUrl, myPeerUrls (for bidirectional broadcast)
- JsonParser

### 8. DagConsensusHandler
**Endpoints**:
- POST /v1/dag/head

**Methods to extract**:
- `handleDagHeadUpdate()` (line ~3363)

**Dependencies**:
- DagConsensusEngine
- JsonParser

### 9. RequestRouter
**Purpose**: Central routing logic that delegates to handlers

**Structure**:
```java
public class RequestRouter extends AbstractHandler {
    private final HealthHandler healthHandler;
    private final MetricsHandler metricsHandler;
    private final FileHandler fileHandler;
    // ... other handlers
    
    @Override
    public void handle(String target, Request baseRequest, 
                      HttpServletRequest request, HttpServletResponse response) {
        // Route to appropriate handler based on path
    }
}
```

## Refactoring Steps

### Step 1: Update SegmentHttpServer to use extracted models
- Replace inner classes with imports from `model` package
- Update all references to use new model classes

### Step 2: Update SegmentHttpServer to use utilities
- Replace `formatBytes()`, `escapeHtml()`, `escapeJson()` calls with `FormatUtils.*`
- Replace `extractJsonField()`, `extractJsonObject()` calls with `JsonParser.*`

### Step 3: Extract handlers one by one
- Start with simpler handlers (FileHandler, ExplorerApiHandler)
- Move to complex handlers (DashboardHandler, ConsensusApiHandler)
- Finally extract consensus handlers

### Step 4: Create RequestRouter
- Create router that instantiates all handlers
- Move routing logic from SegmentStoreHandler.handle() to RequestRouter

### Step 5: Refactor SegmentHttpServer
- Replace SegmentStoreHandler with RequestRouter
- Keep only coordination logic (start/stop, setConsensusEngine, etc.)

### Step 6: Dead Code Cleanup
- Remove demotion callbacks per DEAD-CODE-AUDIT.md
- Remove unused fields

### Step 7: Test
- Compile
- Verify all endpoints work
- Test with running system

## Key Considerations

1. **Shared State**: Handlers need access to:
   - `registeredClients` (Map<String, ClientRegistration>)
   - `registeredValidators` (Map<String, ValidatorRegistration>)
   - `recentWriteMetadata` (Map<String, WriteMetadata>)
   - `connectedPeers` (Set<String>)
   
   **Solution**: Pass these as constructor parameters or create a shared context object

2. **Dependencies**: Many handlers need:
   - FileStore
   - NodeStore
   - ConsensusEngine(s)
   - Path storeDirectory
   - String selfUrl
   
   **Solution**: Create a `ServerContext` class that holds all shared dependencies

3. **Backward Compatibility**: 
   - Keep public API of SegmentHttpServer unchanged
   - Internal refactoring only

4. **Testing**: 
   - Each handler can be unit tested independently
   - Integration tests verify routing works

## Estimated File Sizes After Refactoring

- SegmentHttpServer.java: ~200-300 lines (coordination only)
- RequestRouter.java: ~200-300 lines (routing logic)
- HealthHandler.java: ~200 lines ✅
- MetricsHandler.java: ~150 lines ✅
- FileHandler.java: ~200-300 lines
- ExplorerApiHandler.java: ~200-300 lines
- DashboardHandler.java: ~800-1000 lines (largest, but focused on UI)
- ConsensusApiHandler.java: ~500-600 lines
- RegistrationHandler.java: ~150-200 lines
- PeerDiscoveryHandler.java: ~200-250 lines
- LeaderConsensusHandler.java: ~400-500 lines
- DagConsensusHandler.java: ~100-150 lines

**Total**: ~3,500-4,000 lines (similar to original, but organized)

## Next Actions

1. Continue extracting handlers systematically
2. Create ServerContext for shared dependencies
3. Build RequestRouter
4. Refactor main server
5. Test and verify

