# SegmentHttpServer Refactoring Progress

## Status: IN PROGRESS

**Started**: 2025-01-XX  
**Goal**: Break down 4,496-line SegmentHttpServer.java into maintainable components

## Completed ✅

### Phase 1: Data Models
- ✅ `model/ClientRegistration.java` - Client registration data
- ✅ `model/ValidatorRegistration.java` - Validator registration data  
- ✅ `model/WriteMetadata.java` - Write metadata for dashboard

### Phase 2: Utilities
- ✅ `util/FormatUtils.java` - formatBytes, escapeHtml, escapeJson
- ✅ `util/JsonParser.java` - extractField, extractObject

### Phase 3: Handlers (Partial)
- ✅ `handlers/HealthHandler.java` - Health check endpoints (/health, /health/deep)

## In Progress 🔄

### Phase 3: Handlers (Remaining)
- ⏳ `handlers/MetricsHandler.java` - Metrics endpoints (/api/metrics, /metrics)
- ⏳ `handlers/FileHandler.java` - File serving (journal.log, manifest, segments)
- ⏳ `handlers/ExplorerApiHandler.java` - Explorer APIs (/api/explore, /api/segments/*)
- ⏳ `handlers/DashboardHandler.java` - UI rendering (dashboard, explorer, API browser)
- ⏳ `handlers/ConsensusApiHandler.java` - Consensus APIs (/v1/propose, /v1/vote, /v1/test-write)
- ⏳ `handlers/RegistrationHandler.java` - Registration (/v1/register-client, /v1/register-validator)
- ⏳ `handlers/PeerDiscoveryHandler.java` - Peer discovery (/v1/peers, /v1/ngrok-url)
- ⏳ `handlers/LeaderConsensusHandler.java` - Leader consensus (/v1/follower/*, /v1/heartbeat, /v1/consensus/*)
- ⏳ `handlers/DagConsensusHandler.java` - DAG consensus (/v1/dag/*)

### Phase 4: Router
- ⏳ `routing/RequestRouter.java` - Central routing logic

### Phase 5: Main Server Refactor
- ⏳ Update `SegmentHttpServer.java` to use RequestRouter and handlers

### Phase 6: Dead Code Cleanup
- ⏳ Remove demotion callbacks (per DEAD-CODE-AUDIT.md)
- ⏳ Remove unused fields

### Phase 7: Testing
- ⏳ Compile and verify all endpoints work

## File Size Reduction

**Before**: 4,496 lines (SegmentHttpServer.java)  
**Target**: 
- SegmentHttpServer.java: ~200-300 lines (coordination only)
- Each handler: ~200-500 lines (single responsibility)
- Utilities: ~50-100 lines each

## Next Steps

1. Complete MetricsHandler extraction
2. Extract FileHandler (simpler, good next step)
3. Extract ExplorerApiHandler
4. Extract DashboardHandler (largest, most complex)
5. Extract consensus handlers (ConsensusApiHandler, LeaderConsensusHandler, DagConsensusHandler)
6. Extract RegistrationHandler and PeerDiscoveryHandler
7. Create RequestRouter
8. Refactor SegmentHttpServer to use router
9. Remove dead code
10. Test compilation

## Notes

- All handlers will receive dependencies via constructor injection
- Handlers are stateless where possible
- Shared state (registeredClients, registeredValidators) will be passed as parameters
- Backward compatibility maintained - public API of SegmentHttpServer unchanged

