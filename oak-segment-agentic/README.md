# Oak Segment Agentic - LLM Chat Module

**Status**: 🧪 MVP / Exploration  
**Purpose**: Optional LLM chat interface for Oak Segment Consensus validators

## Overview

This module provides an optional LLM-powered chat interface that helps developers understand Oak internals and query validator state. It integrates seamlessly with `oak-segment-consensus` via reflection, so it's truly optional - the validator works fine without it.

## Features

- ✅ **LLM Chat Endpoint** (`POST /v1/chat`) - Ask questions about Oak
- ✅ **Ollama Integration** - Uses local LLM (no external API calls)
- ✅ **Basic RAG** - Code-aware responses with Oak knowledge
- ✅ **Agentic Tools** - Can query validator APIs autonomously
- 🔜 **Log Access Tool** - Read and analyze logs
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

- "What is the current leader?"
- "How does FileStore.cleanup() work?"
- "What is the cluster state?"
- "Show me consensus status"

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
│           └── ValidatorApiTool.java # Validator API tool
└── pom.xml
```

## Future Enhancements

- [ ] Vector embeddings for better code retrieval
- [ ] Log access tool (read and analyze logs)
- [ ] Metrics tool (query Prometheus)
- [ ] Code indexing from actual Oak codebase
- [ ] ONNX Runtime support (embedded LLM)
- [ ] Streaming responses
- [ ] Conversation history

## Notes

- This is **exploration/POC code** - not production-ready
- Requires Ollama to be running locally
- LLM responses may not always be accurate
- RAG is currently keyword-based (future: vector embeddings)

