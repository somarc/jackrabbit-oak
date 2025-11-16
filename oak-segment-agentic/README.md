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
- ✅ **API Documentation Tool** - Provides comprehensive API docs for agent-to-agent communication
- ✅ **Agent-to-Agent Mode** - Enhanced mode for inter-agent communication with structured API knowledge
- 🔜 **Metrics Tool** - Query Prometheus metrics

## Setup

### 1. Install Ollama

Download and install Ollama from https://ollama.ai

### 2. Pull a Model

```bash
# Default: Qwen2.5 Coder 7B (Apache 2.0 licensed, optimized for code)
ollama pull qwen2.5-coder:7b

# Alternative: Phi-3 Mini (fast, good for code, MIT licensed)
ollama pull phi3

# Alternative: Llama 3.1 8B (better understanding, slower, Meta license)
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
│           ├── ApiDocumentationTool.java # API documentation tool
│           ├── LogAccessTool.java    # Log file access tool
│           ├── AgentDiscoveryTool.java # Agent discovery tool
│           └── ValidatorLLMChatTool.java # Validator LLM chat tool
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

## Agent-to-Agent Communication

The module supports enhanced **two-way agent-to-agent communication** mode. When enabled (via UI toggle or context flag), agents engage in actual conversations where they:

1. **Execute API Calls Automatically** - Agents proactively make API calls to fetch data
2. **Include Actual Results** - Responses contain real data from API calls, not just instructions
3. **Have Natural Conversations** - Agents synthesize tool results into conversational responses
4. **Share Agent Metadata** - Information about requesting and responding agents (ID, type, capabilities)

### How It Works

When agent-to-agent mode is enabled:

1. **Automatic Tool Execution**: The system automatically executes relevant API tools based on the query
2. **Data-First Responses**: The LLM receives actual API results and synthesizes them into natural responses
3. **Proactive Data Fetching**: If a query asks for data (e.g., "What is the current leader?"), the system proactively calls the appropriate API
4. **Conversational Format**: Responses are natural conversations, not just API instructions

### API Documentation Tool

The `ApiDocumentationTool` provides structured documentation for all available APIs:

- **Oak Segment Consensus APIs**: Explorer, Health, Consensus, Aeron Cluster, Registration, Oak Files
- **Oak Segment HTTP APIs**: Client-side usage patterns and endpoint consumption
- **Agent-to-Agent Guidance**: Best practices for inter-agent communication

This tool is automatically included in agent-to-agent mode to ensure the LLM knows what APIs are available.

### Example Agent-to-Agent Conversation

**Agent A (Sling Author) asks:**
```bash
curl -X POST http://localhost:8090/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What is the current leader?",
    "context": {
      "agentToAgent": true
    }
  }'
```

**Agent B (Validator) responds:**
```
I've checked the cluster state. The current leader is node-0 (term 5). 
The cluster has 3 members: node-0, node-1, and node-2. 
All nodes are healthy and responding. Node-0 has been the leader for the last 2 minutes.
```

**Key Differences from Instruction Mode:**
- ✅ **Before**: "To check the leader, query GET /v1/aeron/cluster-state"
- ✅ **After**: "The current leader is node-0 (term 5)" - includes actual data!

### Two-Way Interaction Flow

1. **Agent A** sends a query with `agentToAgent: true`
2. **Agent B** receives the query and:
   - Automatically executes relevant API tools (e.g., `ValidatorApiTool`)
   - Receives actual API response data
   - Synthesizes the data into a natural response
   - Includes the actual results in the answer
3. **Agent A** receives a response with real data, not just instructions
4. **Conversation continues** - agents can ask follow-up questions and get actual data

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

