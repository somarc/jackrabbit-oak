# SegmentHttpServer Refactoring Plan

## Current State
- **File**: `SegmentHttpServer.java` (4,496 lines)
- **Main Issues**:
  - Single massive class handling all concerns
  - Hard to navigate and maintain
  - Difficult to test individual components
  - Mixed responsibilities (HTTP routing, UI rendering, consensus logic, file serving)

## Proposed Structure

### Package: `org.apache.jackrabbit.oak.segment.http.server`

```
http/server/
├── SegmentHttpServer.java              (Main coordinator, ~200 lines)
├── handlers/
│   ├── DashboardHandler.java           (UI rendering - dashboard, explorer, API browser)
│   ├── FileHandler.java                (File serving - journal.log, manifest, segments)
│   ├── HealthHandler.java              (Health checks - /health, /health/deep)
│   ├── MetricsHandler.java             (Metrics - /api/metrics, /metrics)
│   ├── ExplorerApiHandler.java         (Explorer APIs - /api/explore, /api/segments/*)
│   ├── ConsensusApiHandler.java        (Consensus APIs - /v1/propose, /v1/vote, /v1/test-write)
│   ├── RegistrationHandler.java        (Registration - /v1/register-client, /v1/register-validator)
│   ├── PeerDiscoveryHandler.java       (Peer discovery - /v1/peers, /v1/ngrok-url)
│   ├── LeaderConsensusHandler.java     (Leader consensus - /v1/follower/*, /v1/heartbeat, /v1/consensus/*)
│   └── DagConsensusHandler.java        (DAG consensus - /v1/dag/*)
├── model/
│   ├── ClientRegistration.java          (Data class - extracted from inner class)
│   ├── ValidatorRegistration.java       (Data class - extracted from inner class)
│   └── WriteMetadata.java              (Data class - extracted from inner class)
├── util/
│   ├── JsonParser.java                 (JSON parsing utilities)
│   ├── HtmlRenderer.java               (HTML rendering utilities)
│   └── FormatUtils.java                (formatBytes, escapeHtml, escapeJson)
└── routing/
    └── RequestRouter.java               (Central routing logic)

```

## Refactoring Benefits

1. **Maintainability**: Each handler is focused on a single concern (~200-500 lines)
2. **Testability**: Handlers can be unit tested independently
3. **Readability**: Clear separation of concerns
4. **Extensibility**: Easy to add new endpoints by creating new handlers
5. **Reusability**: Utility classes can be reused across handlers

## Migration Strategy

### Phase 1: Extract Data Models
- Move `ClientRegistration`, `ValidatorRegistration`, `WriteMetadata` to `model/` package
- Update references

### Phase 2: Extract Utilities
- Move JSON parsing, HTML rendering, formatting to `util/` package
- Update references

### Phase 3: Extract Handlers (One at a time)
- Start with simplest handlers (HealthHandler, MetricsHandler)
- Move to more complex ones (FileHandler, ExplorerApiHandler)
- Finally extract consensus handlers (most complex)

### Phase 4: Refactor Main Server
- Convert `SegmentStoreHandler` to `RequestRouter`
- Delegate to appropriate handlers
- Keep server coordination logic in `SegmentHttpServer`

## Example: HealthHandler

```java
package org.apache.jackrabbit.oak.segment.http.server.handlers;

public class HealthHandler {
    private final FileStore fileStore;
    private final NodeStore nodeStore;
    private final Path storeDirectory;
    private final ConsensusEngine consensusEngine;
    // ... other dependencies
    
    public void handleHealth(HttpServletResponse response) throws IOException {
        // Simple health check logic
    }
    
    public void handleDeepHealth(HttpServletResponse response) throws IOException {
        // Comprehensive health validation
    }
}
```

## Example: RequestRouter

```java
package org.apache.jackrabbit.oak.segment.http.server.routing;

public class RequestRouter extends AbstractHandler {
    private final HealthHandler healthHandler;
    private final FileHandler fileHandler;
    private final DashboardHandler dashboardHandler;
    // ... other handlers
    
    @Override
    public void handle(String target, Request baseRequest, 
                      HttpServletRequest request, HttpServletResponse response) {
        String path = request.getPathInfo();
        String method = request.getMethod();
        
        // Route to appropriate handler
        if ("/health".equals(path)) {
            healthHandler.handleHealth(response);
        } else if ("/health/deep".equals(path)) {
            healthHandler.handleDeepHealth(response);
        }
        // ... other routes
    }
}
```

## Backward Compatibility

- Keep `SegmentHttpServer` public API unchanged
- Internal refactoring only
- No breaking changes to external callers

