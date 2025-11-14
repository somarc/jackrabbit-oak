# Oak Segment Agentic - LLM Chat Module

**Status**: 🧪 MVP / Exploration  
**Purpose**: Optional LLM chat interface for Oak Segment Consensus validators

## Overview

This module provides an optional LLM-powered chat interface that helps developers understand Oak internals and query validator state. It integrates seamlessly with `oak-segment-consensus` via reflection, so it's truly optional - the validator works fine without it.

## Features

- ✅ **LLM Chat Endpoint** (`POST /v1/chat`) - Ask questions about Oak
- ✅ **Ollama Integration** - Uses local LLM (no external API calls)
- ✅ **Enhanced RAG** - Scans and indexes actual Oak codebase for semantic code search
- ✅ **Agentic Tools** - Can query validator APIs autonomously
- ✅ **Log Access Tool** - Read and analyze log files with filtering
- 🔜 **Metrics Tool** - Query Prometheus metrics

## Setup

### 1. Install Ollama

Download and install Ollama from https://ollama.ai

### 2. Pull a Model

```bash
# Recommended: Phi-3 Mini (fast, good for code)
ollama pull phi3

# Or: Llama 3.1 8B (better understanding, slower)
ollama pull llama3.1:8b
```

### 3. Build the Module

```bash
cd jackrabbit-oak
mvn clean install -pl oak-segment-agentic -am -DskipTests
```

### 4. Include in Validator JAR

To include the agentic module in the standalone validator JAR, add it as a dependency in `oak-segment-consensus/pom.xml`:

```xml
<dependency>
    <groupId>org.apache.jackrabbit</groupId>
    <artifactId>oak-segment-agentic</artifactId>
    <version>${project.version}</version>
</dependency>
```

Then rebuild the validator JAR:

```bash
cd oak-segment-consensus
mvn clean package -DskipTests
```

## Usage

### Start Validator with Chat Enabled

```bash
java -jar oak-segment-consensus.jar --port 8090 --store /var/oak-chain/segmentstore
```

### Chat via HTTP API

```bash
curl -X POST http://localhost:8090/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What is the current leader?",
    "context": {
      "includeMetrics": true
    }
  }'
```

### Example Queries

**Validator State:**
- "What is the current leader?"
- "What is the cluster state?"
- "Show me consensus status"

**Code Understanding (RAG):**
- "How does FileStore.cleanup() work?"
- "Explain AeronConsensusEngine"
- "Show me the consensus implementation"
- "What is SegmentNodeStore?"

**Log Analysis:**
- "Show recent errors"
- "What errors occurred in the logs?"
- "Show last 50 log entries"
- "Find logs containing 'consensus'"
- "Show warnings from the logs"

## Architecture

```
┌─────────────────────────────────────┐
│  oak-segment-consensus              │
│  ┌───────────────────────────────┐ │
│  │ RequestRouter                 │ │
│  │  - /v1/chat (optional)       │ │
│  └───────────┬───────────────────┘ │
│              │ (reflection)          │
│  ┌───────────▼───────────────────┐ │
│  │ oak-segment-agentic           │ │
│  │  ┌─────────────────────────┐  │ │
│  │  │ ChatHandler             │  │ │
│  │  │  - LLMService          │  │ │
│  │  │  - RAGService          │  │ │
│  │  │  - AgenticTools        │  │ │
│  │  └─────────────────────────┘  │ │
│  └────────────────────────────────┘ │
└─────────────────────────────────────┘
```

## Module Structure

```
oak-segment-agentic/
├── src/main/java/
│   └── org/apache/jackrabbit/oak/segment/agentic/
│       ├── chat/
│       │   ├── ChatHandler.java      # HTTP endpoint handler
│       │   ├── ChatRequest.java      # Request model
│       │   └── ChatResponse.java     # Response model
│       ├── llm/
│       │   ├── LLMService.java       # LLM interface
│       │   └── OllamaLLMService.java # Ollama implementation
│       ├── rag/
│       │   └── RAGService.java       # Code retrieval
│       └── tools/
│           ├── AgenticTool.java       # Tool interface
│           ├── ToolResult.java        # Tool result model
│           ├── ValidatorApiTool.java # Validator API tool
│           └── LogAccessTool.java    # Log file access tool
└── pom.xml
```

## RAG (Retrieval-Augmented Generation)

The RAG service automatically scans and indexes the Oak codebase on startup:

- **Automatic Discovery**: Finds Oak codebase in common locations or via `-Doak.codebase.path=<path>`
- **Module Focus**: Indexes key modules: `oak-segment-consensus`, `oak-segment-agentic`, `oak-segment-tar`, `oak-segment-http`, `oak-store-composite`, `oak-core`
- **Smart Indexing**: Extracts class names, JavaDoc summaries, and splits large files into chunks
- **Relevance Scoring**: Uses TF-IDF-like scoring for better code retrieval
- **Persistent Cache**: Index is cached to disk for fast startup (only re-indexes when files change)
- **Fallback**: If codebase not found, uses basic knowledge chunks

### Configuring Codebase Path

Set the system property to point to your Oak codebase:

```bash
java -jar oak-segment-consensus.jar -Doak.codebase.path=/path/to/jackrabbit-oak
```

### RAG Cache

The RAG index is automatically cached to disk for faster subsequent startups:

- **Cache Location**: Defaults to `$TMPDIR/.oak-rag-cache/` (configurable via `-Doak.rag.cache.dir=<path>`)
- **Cache Validation**: Checks file modification times to detect code changes
- **Automatic Refresh**: Re-indexes automatically when files are modified
- **First Run**: First startup indexes from scratch and saves to cache
- **Subsequent Runs**: Loads from cache (much faster) unless files changed

To force a fresh index, delete the cache directory:

```bash
rm -rf $TMPDIR/.oak-rag-cache
# Or if using custom location:
rm -rf /path/to/cache/.oak-rag-cache
```

## Log Access Tool

The log access tool can read and analyze log files:

- **Auto-Discovery**: Finds log files from Logback appenders (if available) or common locations
- **Filtering**: Filter by log level (ERROR, WARN, INFO, DEBUG, TRACE)
- **Search**: Search for specific terms in log entries
- **Tail Mode**: Get recent log entries (e.g., "show last 50 lines")
- **Multiple Files**: Supports multiple log files

### Configuring Log File Path

Set the system property to specify log file location:

```bash
java -jar oak-segment-consensus.jar -Dlog.file.path=/path/to/validator.log
```

### Example Log Queries

- "Show recent errors" - Gets ERROR level entries
- "Show last 100 log entries" - Tail mode with limit
- "Find logs containing 'consensus'" - Search for specific term
- "Show warnings" - Filter by log level

## Future Enhancements

- [ ] Vector embeddings for better semantic code retrieval
- [ ] Metrics tool (query Prometheus)
- [ ] ONNX Runtime support (embedded LLM)
- [ ] Streaming responses
- [ ] Conversation history
- [ ] Code change tracking (git integration)

## Notes

- This is **exploration/POC code** - not production-ready
- Requires Ollama to be running locally
- LLM responses may not always be accurate
- RAG uses enhanced keyword matching with TF-IDF scoring (future: vector embeddings)
- Log access works with or without Logback (uses reflection for optional dependency)

