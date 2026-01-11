# Phase 1: Consolidated Refactoring Recommendations

**Date**: January 10, 2026  
**Status**: Analysis Complete  
**Scope**: AeronConsensusEngine, ConsensusApiHandler, GlobalStoreServer  
**Total Lines Analyzed**: 9,308

---

## Executive Summary

Phase 1 analysis identified significant technical debt across the three critical classes:

| Class | LOC | Tests | Debt Level | Priority |
|-------|-----|-------|------------|----------|
| AeronConsensusEngine | 4,586 | 0 | 🔴 High | Critical |
| ConsensusApiHandler | 2,293 | 0 | 🟡 Moderate | High |
| GlobalStoreServer | 2,429 | 0 | 🟡 Moderate | Medium |

### Key Findings

1. **Zero unit tests** for 9,308 lines of critical code
2. **God Class anti-pattern** in AeronConsensusEngine (12+ responsibilities)
3. **God Method anti-pattern** in multiple places (650+ line methods)
4. **Dead code** and unused fields throughout
5. **Manual JSON parsing** instead of proper libraries
6. **Mixed concerns** (HTTP handling + business logic)

---

## Prioritized Action Plan

### Phase 1A: Immediate Cleanup (1-2 days)

| # | Action | File | Effort | Impact |
|---|--------|------|--------|--------|
| 1 | Remove unused fields in AeronConsensusEngine | AeronConsensusEngine.java | Low | Medium |
| 2 | Remove unused methods in AeronConsensusEngine | AeronConsensusEngine.java | Low | Medium |
| 3 | Clean up linter warnings in GlobalStoreServer | GlobalStoreServer.java | Low | Medium |
| 4 | Remove startConsensusPrimary() | GlobalStoreServer.java | Low | Low |
| 5 | Remove debug logging markers | AeronConsensusEngine.java | Low | Low |

**Estimated Effort**: 4-8 hours

### Phase 1B: Value Objects & Validators (3-5 days)

| # | Action | New File | Effort | Impact |
|---|--------|----------|--------|--------|
| 6 | Create WriteProposal value object | WriteProposal.java | Low | High |
| 7 | Create DeleteProposal value object | DeleteProposal.java | Low | Medium |
| 8 | Extract WalletValidator | WalletValidator.java | Low | High |
| 9 | Create ServerConfig class | ServerConfig.java | Low | Medium |

**Estimated Effort**: 2-3 days

### Phase 1C: Service Extraction (1-2 weeks)

| # | Action | New File | Effort | Impact |
|---|--------|----------|--------|--------|
| 10 | Extract MessageProcessor | MessageProcessor.java | Medium | High |
| 11 | Extract WriteApplicationService | WriteApplicationService.java | Medium | High |
| 12 | Extract DeleteApplicationService | DeleteApplicationService.java | Low | Medium |
| 13 | Complete SnapshotService extraction | SnapshotService.java | Medium | Medium |
| 14 | Extract WriteIngressService | WriteIngressService.java | Medium | High |
| 15 | Extract GenesisInitializer | GenesisInitializer.java | Medium | Medium |

**Estimated Effort**: 5-10 days

### Phase 1D: Unit Tests (2-3 weeks)

| # | Action | Test File | Effort | Impact |
|---|--------|-----------|--------|--------|
| 16 | Tests for WalletValidator | WalletValidatorTest.java | Low | High |
| 17 | Tests for WriteProposal | WriteProposalTest.java | Low | Medium |
| 18 | Tests for MessageProcessor | MessageProcessorTest.java | Medium | High |
| 19 | Tests for WriteApplicationService | WriteApplicationServiceTest.java | High | Critical |
| 20 | Tests for AeronConsensusEngine core | AeronConsensusEngineTest.java | High | Critical |
| 21 | Tests for ConsensusApiHandler | ConsensusApiHandlerTest.java | High | Critical |
| 22 | Tests for GlobalStoreServer | GlobalStoreServerTest.java | Medium | High |

**Estimated Effort**: 10-15 days

---

## Detailed Refactoring Patterns

### Pattern 1: Extract Value Object

**Before** (ConsensusApiHandler.java):
```java
public void applyReplicatedWrite(
    String walletAddress, 
    String path, 
    String contentType, 
    String message, 
    String signature, 
    String intentToken, 
    String blobId, 
    String mimeType, 
    String ipfsCid  // 9 parameters!
)
```

**After**:
```java
public class WriteProposal {
    private final String walletAddress;
    private final String path;
    private final String contentType;
    private final String message;
    private final String signature;
    private final String intentToken;
    private final String blobId;
    private final String mimeType;
    private final String ipfsCid;
    
    // Builder pattern for construction
    public static Builder builder() { ... }
    
    // Validation in constructor
    public WriteProposal(Builder builder) {
        Objects.requireNonNull(builder.walletAddress, "walletAddress required");
        Objects.requireNonNull(builder.path, "path required");
        // ...
    }
}

// Usage
public void applyReplicatedWrite(WriteProposal proposal) { ... }
```

### Pattern 2: Extract Validator

**Before** (duplicated in handleProposeWrite and handleDeleteProposal):
```java
if (wallet == null || wallet.isEmpty()) {
    response.sendError(SC_BAD_REQUEST, "Missing walletAddress");
    return;
}
if (!wallet.startsWith("0x") || wallet.length() < 10) {
    response.sendError(SC_BAD_REQUEST, "Invalid format");
    return;
}
if (!walletHex.matches("[a-fA-F0-9]+")) {
    response.sendError(SC_BAD_REQUEST, "Invalid hex");
    return;
}
```

**After**:
```java
public class WalletValidator {
    public static ValidationResult validate(String wallet) {
        if (wallet == null || wallet.isEmpty()) {
            return ValidationResult.error("Missing walletAddress");
        }
        if (!wallet.startsWith("0x") || wallet.length() < 10) {
            return ValidationResult.error("Invalid format");
        }
        String hex = wallet.substring(2);
        if (!hex.matches("[a-fA-F0-9]+")) {
            return ValidationResult.error("Invalid hex");
        }
        return ValidationResult.valid(wallet.toLowerCase());
    }
}

// Usage
ValidationResult result = WalletValidator.validate(wallet);
if (!result.isValid()) {
    response.sendError(SC_BAD_REQUEST, result.getError());
    return;
}
String normalizedWallet = result.getNormalizedValue();
```

### Pattern 3: Extract Service

**Before** (AeronConsensusEngine.java - 360 lines in onSessionMessage):
```java
@Override
public void onSessionMessage(ClientSession session, long timestamp, DirectBuffer buffer, 
                             int offset, int length, Header header) {
    // 360 lines of switch/if logic
    if (templateId == WRITE_PROPOSAL) {
        // 100 lines
    } else if (templateId == WRITE_BATCH) {
        // 150 lines
    } else if (templateId == DELETE_PROPOSAL) {
        // 50 lines
    } else if (templateId == GC_PROPOSAL) {
        // 30 lines
    }
    // ...
}
```

**After**:
```java
public class MessageProcessor {
    private final WriteCallback writeCallback;
    private final GCCallback gcCallback;
    
    public void processMessage(DirectBuffer buffer, int offset, int length) {
        HeaderInfo header = SimpleMessageHeader.decode(buffer, offset);
        
        switch (header.templateId) {
            case WRITE_PROPOSAL:
                processWriteProposal(buffer, offset, length, header);
                break;
            case WRITE_BATCH:
                processWriteBatch(buffer, offset, length, header);
                break;
            case DELETE_PROPOSAL:
                processDeleteProposal(buffer, offset, length, header);
                break;
            case GC_PROPOSAL:
                processGCProposal(buffer, offset, length, header);
                break;
            default:
                log.warn("Unknown template ID: {}", header.templateId);
        }
    }
    
    private void processWriteProposal(...) { /* 30 lines */ }
    private void processWriteBatch(...) { /* 50 lines */ }
    private void processDeleteProposal(...) { /* 20 lines */ }
    private void processGCProposal(...) { /* 20 lines */ }
}

// In AeronConsensusEngine
@Override
public void onSessionMessage(...) {
    messageProcessor.processMessage(buffer, offset, length);
}
```

### Pattern 4: Replace Manual JSON with Library

**Before**:
```java
private String extractJsonField(String json, String field) {
    String fieldPrefix = "\"" + field + "\"";
    int fieldStart = json.indexOf(fieldPrefix);
    if (fieldStart == -1) return null;
    // ... fragile string parsing
}

String json = "{" +
    "\"proposalId\":\"" + status.getProposalId() + "\"," +
    "\"state\":\"" + status.getState().name() + "\"" +
    "}";
```

**After**:
```java
// Using Jackson
private final ObjectMapper objectMapper = new ObjectMapper();

private WriteProposal parseWriteProposal(byte[] jsonBytes) {
    return objectMapper.readValue(jsonBytes, WriteProposal.class);
}

private String toJson(ProposalStatus status) {
    return objectMapper.writeValueAsString(status);
}
```

---

## Target Architecture

### After Refactoring

```
oak-segment-consensus/
├── src/main/java/org/apache/jackrabbit/oak/segment/consensus/
│   ├── aeron/
│   │   ├── AeronConsensusEngine.java      (~800 lines, ClusteredService only)
│   │   ├── AeronClusterLauncher.java      (existing)
│   │   ├── AeronPerformanceMetrics.java   (existing)
│   │   ├── MessageProcessor.java          (NEW - message dispatch)
│   │   ├── WriteIngressService.java       (NEW - send writes)
│   │   ├── GCIngressService.java          (NEW - send GC)
│   │   └── SimpleMessageHeader.java       (existing)
│   │
│   ├── server/
│   │   ├── GlobalStoreServer.java         (~500 lines, orchestration only)
│   │   ├── ServerConfig.java              (NEW - configuration)
│   │   ├── ServerInitializer.java         (NEW - init phases)
│   │   └── GenesisInitializer.java        (NEW - genesis creation)
│   │
│   ├── service/
│   │   ├── WriteApplicationService.java   (NEW - apply writes)
│   │   ├── DeleteApplicationService.java  (NEW - apply deletes)
│   │   ├── SnapshotService.java           (existing - complete)
│   │   ├── LeaderDiscoveryService.java    (existing - complete)
│   │   └── HeadSyncService.java           (NEW - HEAD sync)
│   │
│   ├── model/
│   │   ├── WriteProposal.java             (NEW - value object)
│   │   ├── DeleteProposal.java            (NEW - value object)
│   │   └── ValidationResult.java          (NEW - validation result)
│   │
│   └── validation/
│       └── WalletValidator.java           (NEW - wallet validation)
│
├── http/server/handlers/
│   ├── ConsensusApiHandler.java           (~400 lines, HTTP only)
│   ├── WriteProposalHandler.java          (NEW - write HTTP)
│   ├── DeleteProposalHandler.java         (NEW - delete HTTP)
│   └── WalletQueryHandler.java            (NEW - wallet queries)
```

### Lines of Code Target

| Class | Current | Target | Reduction |
|-------|---------|--------|-----------|
| AeronConsensusEngine | 4,586 | ~800 | 82% |
| ConsensusApiHandler | 2,293 | ~400 | 83% |
| GlobalStoreServer | 2,429 | ~500 | 79% |
| **Total** | **9,308** | **~1,700** | **82%** |

---

## Test Coverage Target

### Minimum Coverage Goals

| Component | Current | Target | Priority |
|-----------|---------|--------|----------|
| WalletValidator | 0% | 100% | High |
| WriteProposal | 0% | 100% | High |
| MessageProcessor | 0% | 80% | Critical |
| WriteApplicationService | 0% | 80% | Critical |
| AeronConsensusEngine | 0% | 60% | Critical |
| ConsensusApiHandler | 0% | 60% | High |
| GlobalStoreServer | 0% | 40% | Medium |

### Test Categories

1. **Unit Tests**: Individual class behavior
2. **Integration Tests**: Component interaction
3. **Contract Tests**: API contracts
4. **State Machine Tests**: State transitions (already done for some)

---

## Risk Assessment

### High Risk Items

| Item | Risk | Mitigation |
|------|------|------------|
| Refactoring AeronConsensusEngine | Breaking consensus | Extensive integration tests first |
| Extracting MessageProcessor | Message handling bugs | Keep old code path as fallback |
| Changing JSON parsing | Compatibility issues | Parallel testing with both parsers |

### Low Risk Items

| Item | Risk | Mitigation |
|------|------|------------|
| Extracting validators | Low | Pure functions, easy to test |
| Creating value objects | Low | Additive change |
| Cleaning up dead code | Low | Already identified as unused |

---

## Success Criteria

### Phase 1 Complete When:

1. ✅ All linter warnings resolved
2. ✅ Dead code removed
3. ✅ Value objects created (WriteProposal, DeleteProposal)
4. ✅ WalletValidator extracted and tested
5. ✅ MessageProcessor extracted and tested
6. ✅ WriteApplicationService extracted and tested
7. ✅ Unit test coverage > 50% for extracted components
8. ✅ All three classes < 1,000 lines each

---

## Related Documents

- [PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md](PHASE1-ANALYSIS-AERON-CONSENSUS-ENGINE.md)
- [PHASE1-ANALYSIS-CONSENSUS-API-HANDLER.md](PHASE1-ANALYSIS-CONSENSUS-API-HANDLER.md)
- [PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md](PHASE1-ANALYSIS-GLOBAL-STORE-SERVER.md)
- [STATE-MACHINE-DIAGRAMS.md](STATE-MACHINE-DIAGRAMS.md)
- [GAPS-AND-TESTING-REQUIREMENTS.md](GAPS-AND-TESTING-REQUIREMENTS.md)

---

*Document created: January 10, 2026*  
*Next review: After Phase 1A completion*
