# Developer Documentation Index

**Complete developer-focused documentation for oak-segment-consensus**

**Created**: 2026-01-31  
**Purpose**: Centralized developer documentation for the standalone Oak consensus server

---

## 📚 Documentation Structure

```
docs/
├── README.md                    # Documentation overview
├── api/                         # HTTP API Reference
│   ├── README.md               # API overview
│   ├── consensus.md            # Write/delete proposals, consensus status
│   ├── aeron.md                # Aeron Cluster state, leadership
│   ├── health.md               # Health checks, metrics
│   └── osgi-config.md          # Read-only OSGi config control surface
├── architecture/                # System Architecture
│   └── README.md               # How the system works
├── development/                 # Development Guide
│   └── README.md               # How to extend the module
│   └── BLOCKCHAIN-CONFIG-KNOBS.md # OSGi/env/system/API knob mapping
├── testing/                     # Testing Guide
│   └── README.md               # How to test
├── troubleshooting/            # Troubleshooting
│   └── README.md               # Common issues and solutions
└── integration/                 # Integration Guide
    └── README.md               # Sling, Docker, Kubernetes integration
```

---

## 🎯 Quick Navigation

### Getting Started
1. **[Quick Start](../QUICK-START.md)** - 30-second start guide
2. **[Configuration](../CONFIGURATION.md)** - Environment variables
3. **[Architecture Overview](architecture/README.md)** - How it works

### Development
1. **[Development Guide](development/README.md)** - Extending the module
2. **[Blockchain Config Knobs](development/BLOCKCHAIN-CONFIG-KNOBS.md)** - Runtime knobs/gears map for dashboards
3. **[API Reference](api/README.md)** - Complete API docs
4. **[OSGi Config Introspection](api/osgi-config.md)** - Effective values, sources, and drift
5. **[Testing Guide](testing/README.md)** - Testing strategies

### Operations
1. **[Troubleshooting](troubleshooting/README.md)** - Common issues
2. **[Integration Guide](integration/README.md)** - Deployment
3. **[Health & Metrics](api/health.md)** - Monitoring

---

## 📖 Documentation Categories

### API Reference (`api/`)
Complete HTTP API documentation:
- **Consensus APIs** - Write/delete proposals, status
- **Aeron APIs** - Cluster state, leadership, metrics
- **Health APIs** - Health checks, Prometheus metrics
- **OSGi Config APIs** - Effective values, schema, source provenance, coverage, drift
- **Content APIs** - Content operations, exploration
- **GC APIs** - Garbage collection, account management

### Architecture (`architecture/`)
System design and flow:
- Deterministic state machine
- Aeron Raft consensus
- Proposal flow diagrams
- Component architecture
- ADR-001 operations readiness and operator opus

### Development (`development/`)
How to extend:
- Adding API endpoints
- Adding proposal types
- Modifying proposal queue
- Code style guidelines
- Debugging tips

### Testing (`testing/`)
Testing strategies:
- Unit tests
- Integration tests
- Local testing
- Performance testing
- Mock mode usage

### Troubleshooting (`troubleshooting/`)
Common issues:
- Cluster health problems
- Proposal failures
- Performance issues
- Network issues
- IPFS problems

### Integration (`integration/`)
Deployment and integration:
- Sling author integration
- HTTP client integration
- Docker deployment
- Kubernetes deployment
- Monitoring integration

---

## 🔗 Related Documentation

### Module Documentation (This Repository)
- **[README.md](../README.md)** - Module overview
- **[CONFIGURATION.md](../CONFIGURATION.md)** - Configuration reference
- **[QUICK-START.md](../QUICK-START.md)** - Quick start
- **[IPFS-DATASTORE.md](../IPFS-DATASTORE.md)** - IPFS setup
- **[DELETE-QUICK-REFERENCE.md](../DELETE-QUICK-REFERENCE.md)** - Delete/GC reference

### Project Documentation (Blockchain-AEM Repository)
- Architecture deep dives
- Gap analysis vs OakRS
- Package maps
- Technical specifications
- ADRs (Architecture Decision Records)

---

## 📊 Documentation Coverage

| Category | Status | Coverage |
|----------|--------|----------|
| **API Reference** | ✅ Complete | All endpoints documented |
| **Architecture** | ✅ Complete | Core concepts covered |
| **Development** | ✅ Complete | Common tasks covered |
| **Testing** | ✅ Complete | Testing strategies covered |
| **Troubleshooting** | ✅ Complete | Common issues covered |
| **Integration** | ✅ Complete | Deployment covered |

---

## 🚀 Next Steps

1. **Read**: [Quick Start](../QUICK-START.md)
2. **Configure**: [Configuration](../CONFIGURATION.md)
3. **Understand**: [Architecture Overview](architecture/README.md)
4. **Develop**: [Development Guide](development/README.md)
5. **Test**: [Testing Guide](testing/README.md)
6. **Deploy**: [Integration Guide](integration/README.md)

---

*Last Updated: 2026-01-31*
