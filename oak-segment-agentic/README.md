# Oak Segment Agentic - LLM Chat Module

**Status**: 🚀 Enhanced MVP  
**Purpose**: Optional LLM chat interface for Oak Segment Consensus validators

## Overview

This module provides an optional LLM-powered chat interface that helps developers understand Oak internals and query validator state. It integrates seamlessly with `oak-segment-consensus` via reflection, so it's truly optional - the validator works fine without it.

## Features

- ✅ **LLM Chat Endpoint** (`POST /v1/chat`) - Ask questions about Oak
- ✅ **Ollama Integration** - Uses local LLM (no external API calls)
- ✅ **Hybrid RAG** - Combines vector embeddings + keyword search for superior code retrieval
- ✅ **Dynamic Model Selection** - Automatically selects best model based on query complexity
- ✅ **Conversation Memory** - Maintains context across multi-turn conversations
- ✅ **Agentic Tools** - Can query validator APIs autonomously
- ✅ **Log Access Tool** - Read and analyze log files with filtering
- ✅ **API Documentation Tool** - Provides comprehensive API docs for agent-to-agent communication
- ✅ **Agent-to-Agent Mode** - Enhanced mode for inter-agent communication with structured API knowledge
- 🔜 **Metrics Tool** - Query Prometheus metrics
- 🔜 **Code Knowledge Graph** - Graph-based code relationship traversal

## Setup

### 1. Install Ollama

Download and install Ollama from https://ollama.ai

### 2. Pull Models (All Apache 2.0 Licensed)

```bash
# Required: Code specialist model (fast responses)
ollama pull qwen2.5-coder:7b

# Recommended: Reasoning model (complex queries)
ollama pull qwen3:8b

# Required: Embedding model for vector RAG
ollama pull nomic-embed-text
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
# Simple query
curl -X POST http://localhost:8090/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What is the current leader?",
    "context": {
      "includeMetrics": true
    }
  }'

# With conversation memory (multi-turn)
curl -X POST http://localhost:8090/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "How does it handle failover?",
    "sessionId": "my-session-123"
  }'
```

### Example Queries

**Validator State:**
- "What is the current leader?"
- "What is the cluster state?"
- "Show me consensus status"

**Code Understanding (Hybrid RAG):**
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
┌─────────────────────────────────────────────────────────────────────────────┐
│  oak-segment-consensus                                                       │
│  ┌───────────────────────────────────────────────────────────────────────┐ │
│  │ RequestRouter                                                          │ │
│  │  - /v1/chat (optional)                                                │ │
│  └───────────┬───────────────────────────────────────────────────────────┘ │
│              │ (reflection)                                                  │
│  ┌───────────▼───────────────────────────────────────────────────────────┐ │
│  │ oak-segment-agentic                                                    │ │
│  │  ┌─────────────────────────────────────────────────────────────────┐  │ │
│  │  │ ChatHandler                                                      │  │ │
│  │  │  ├── OllamaLLMService (dynamic model selection)                 │  │ │
│  │  │  ├── HybridRAGService (vector + keyword)                        │  │ │
│  │  │  ├── ConversationMemory (session context)                       │  │ │
│  │  │  └── AgenticTools (API queries, logs, etc.)                     │  │ │
│  │  └─────────────────────────────────────────────────────────────────┘  │ │
│  └────────────────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Module Structure

```
oak-segment-agentic/
├── src/main/java/
│   └── org/apache/jackrabbit/oak/segment/agentic/
│       ├── chat/
│       │   ├── ChatHandler.java        # HTTP endpoint handler
│       │   ├── ChatRequest.java        # Request model (with sessionId)
│       │   ├── ChatResponse.java       # Response model (with metadata)
│       │   └── ConversationMemory.java # Session-based conversation history
│       ├── llm/
│       │   ├── LLMService.java         # LLM interface
│       │   └── OllamaLLMService.java   # Ollama impl with model selection
│       ├── rag/
│       │   ├── RAGService.java         # Keyword-based retrieval (TF-IDF)
│       │   ├── VectorRAGService.java   # Vector embeddings (nomic-embed-text)
│       │   └── HybridRAGService.java   # Combined retrieval with RRF
│       └── tools/
│           ├── AgenticTool.java         # Tool interface
│           ├── ToolResult.java          # Tool result model
│           ├── ValidatorApiTool.java   # Validator API tool
│           ├── ApiDocumentationTool.java # API documentation tool
│           ├── LogAccessTool.java      # Log file access tool
│           ├── AgentDiscoveryTool.java # Agent discovery tool
│           └── ValidatorLLMChatTool.java # Validator LLM chat tool
└── pom.xml
```

## Indexed Knowledge Base

The RAG system indexes the following Blockchain-AEM modules and documentation:

### Java Modules (Code Understanding)

| Module | Purpose | Key Classes |
|--------|---------|-------------|
| `oak-segment-consensus` | Aeron Raft consensus | `AeronConsensusEngine`, `GlobalStoreServer`, `ConsensusApiHandler` |
| `oak-segment-agentic` | This LLM chat module | `ChatHandler`, `RAGService`, `HybridRAGService` |
| `oak-segment-tar` | TAR-based segment storage | `FileStore`, `SegmentNodeStore` |
| `oak-segment-http` | HTTP segment transfer | `SegmentHttpClient`, `SegmentHttpServer` |
| `oak-store-composite` | Composite mounts | `CompositeNodeStore` |
| `oak-blob-cloud-ipfs` | IPFS binary storage | `IPFSBackend`, `IPFSDataStore` |
| `oak-auth-web3` | Web3 biometric auth | `Web3BiometricAuthentication`, `ChallengeService` |
| `oak-core` | Core Oak APIs | `NodeStore`, `PropertyState` |
| `oak-api` | Public Oak API | `Tree`, `Root`, `ContentSession` |

### Documentation (Architecture Understanding)

| Path | Content |
|------|---------|
| `Blockchain-AEM/adr/` | Architecture Decision Records (46+ ADRs) |
| `Blockchain-AEM/02-architecture/` | System architecture docs |
| `Blockchain-AEM/08-technical-notes/` | Technical implementation notes |
| `jackrabbit-oak/docs/` | Phase 1 analysis, state machines, gaps |

### Example Queries

**Blockchain-AEM Specific:**
- "How does IPFS binary storage work?" → Retrieves `IPFSBackend.java`
- "Explain Web3 biometric authentication" → Retrieves `Web3BiometricAuthentication.java`, `ChallengeService.java`
- "What is ADR 015?" → Retrieves ADR documentation
- "How does the 5-layer architecture work?" → Retrieves architecture docs

**Consensus & Storage:**
- "How does AeronConsensusEngine handle leader election?" → Retrieves consensus code + state machine docs
- "Explain FileStore cleanup" → Retrieves `oak-segment-tar` code

## Hybrid RAG (Retrieval-Augmented Generation)

The RAG system uses a **hybrid approach** combining vector embeddings and keyword search:

### How It Works

```
Query: "How does AeronConsensusEngine handle leader election?"
  │
  ├─────────────────────────────────────────┐
  │                                         │
  ▼                                         ▼
┌──────────────────────┐     ┌──────────────────────┐
│ VECTOR RETRIEVAL     │     │ KEYWORD RETRIEVAL    │
│ (Semantic Similarity)│     │ (TF-IDF Scoring)     │
│                      │     │                      │
│ • nomic-embed-text   │     │ • Term frequency     │
│ • Cosine similarity  │     │ • Class name boost   │
│ • Top-K chunks       │     │ • Exact match boost  │
└──────────┬───────────┘     └──────────┬───────────┘
           │                            │
           │ Semantic matches           │ Keyword matches
           └─────────────┬──────────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │ RECIPROCAL RANK      │
              │ FUSION (RRF)         │
              │                      │
              │ Combined score =     │
              │ 0.55 * vector_rrf +  │
              │ 0.45 * keyword_rrf   │
              └──────────┬───────────┘
                         │
                         ▼
              ┌──────────────────────┐
              │ Top-10 Results       │
              │ (Best of both)       │
              └──────────────────────┘
```

### Benefits

- **Semantic Understanding**: Vector search finds conceptually related code even without exact keyword matches
- **Exact Matching**: Keyword search ensures class names and specific terms are found
- **Best of Both**: RRF fusion combines results for superior retrieval quality
- **Cached Embeddings**: Embeddings are cached to disk for fast startup

### Configuration

```bash
# Set codebase path
java -jar oak-segment-consensus.jar -Doak.codebase.path=/path/to/jackrabbit-oak

# Set cache directory
java -jar oak-segment-consensus.jar -Doak.rag.cache.dir=/path/to/cache

# Set embedding model (default: nomic-embed-text)
java -jar oak-segment-consensus.jar -Dollama.embedding.model=nomic-embed-text
```

### Cache Files

```
$TMPDIR/.oak-rag-cache/
├── rag-index.json                    # Keyword index
├── rag-metadata.json                 # Index metadata
└── embeddings-nomic-embed-text.json  # Vector embeddings
```

## Dynamic Model Selection

The LLM service automatically selects the best model based on query complexity:

| Query Type | Model | Reason |
|------------|-------|--------|
| Simple questions | `qwen2.5-coder:7b` | Fast responses |
| "How does X work?" | `qwen3:8b` | Better reasoning |
| "Explain..." | `qwen3:8b` | Better explanations |
| "Why..." | `qwen3:8b` | Better reasoning |
| Agent-to-agent | `qwen3:8b` | More accurate data synthesis |
| Long queries (>200 chars) | `qwen3:8b` | Complex context handling |

### Configuration

```bash
# Set default model
java -jar oak-segment-consensus.jar -Dollama.model=qwen2.5-coder:7b

# Set Ollama URL (for Docker)
java -jar oak-segment-consensus.jar -Dollama.url=http://host.docker.internal:11434
```

## Conversation Memory

The chat endpoint supports multi-turn conversations via session IDs:

### Usage

```bash
# First turn
curl -X POST http://localhost:8090/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "What is AeronConsensusEngine?",
    "sessionId": "session-123"
  }'

# Follow-up (remembers context)
curl -X POST http://localhost:8090/v1/chat \
  -H "Content-Type: application/json" \
  -d '{
    "query": "How does it handle leader election?",
    "sessionId": "session-123"
  }'
```

### Features

- **Session Isolation**: Each session ID has its own conversation history
- **Automatic Trimming**: Keeps last 10 turns to prevent context overflow
- **TTL Cleanup**: Stale sessions (>30 min inactive) are automatically cleaned up
- **Response Metadata**: Response includes session info and turn count

### Response Example

```json
{
  "answer": "AeronConsensusEngine handles leader election through...",
  "sources": [...],
  "metadata": {
    "ragMode": "hybrid (vector + keyword)",
    "chunksRetrieved": 8,
    "sessionId": "session-123",
    "conversationTurns": 2
  }
}
```

## Agent-to-Agent Communication

The module supports enhanced **two-way agent-to-agent communication** mode. When enabled (via UI toggle or context flag), agents engage in actual conversations where they:

1. **Execute API Calls Automatically** - Agents proactively make API calls to fetch data
2. **Include Actual Results** - Responses contain real data from API calls, not just instructions
3. **Have Natural Conversations** - Agents synthesize tool results into conversational responses
4. **Share Agent Metadata** - Information about requesting and responding agents (ID, type, capabilities)

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

## Apache 2.0 Licensed Models

All recommended models are Apache 2.0 licensed for compatibility with Apache projects:

| Model | Size | Purpose | License |
|-------|------|---------|---------|
| `qwen2.5-coder:7b` | 4.7 GB | Code specialist (fast) | Apache 2.0 |
| `qwen3:8b` | 5.2 GB | Reasoning + code | Apache 2.0 |
| `nomic-embed-text` | 274 MB | Vector embeddings | Apache 2.0 |

## Future Enhancements

- [x] ~~Vector embeddings for better semantic code retrieval~~
- [x] ~~Dynamic model selection~~
- [x] ~~Conversation history~~
- [ ] Code Knowledge Graph (class hierarchy, method calls)
- [ ] Metrics tool (query Prometheus)
- [ ] Streaming responses
- [ ] Git integration (code change tracking)
- [ ] Function calling / tool use

## Notes

- This is **exploration/POC code** - not production-ready
- Requires Ollama to be running locally
- LLM responses may not always be accurate
- First startup generates embeddings (may take 1-2 minutes)
- Subsequent startups load from cache (fast)
