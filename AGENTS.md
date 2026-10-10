# AGENTS.md — Developer & AI Agent Guide to `openworker-spring-ai`

Welcome! This document provides a complete, architectural, and operational guide to navigating, understanding, and modifying the `openworker-spring-ai` codebase. Read this first whenever assisting with or implementing features in this repository.

---

## 1. Project Overview & Mission

`openworker-spring-ai` is an autonomous AI software engineer and coworker backend built with **Spring Boot 4.0**, **Spring AI 2.0**, and **LangGraph4j 1.9** on **Java 25**.

Key capabilities:
- **Stateful ReAct reasoning loop** powered by LangGraph4j (`StateGraph`) with parallel tool execution.
- **Dynamic Multi-Provider Chat Routing** (Google Gemini, OpenAI-compatible / LM Studio, Ollama, Anthropic Claude) switchable per session or turn.
- **Granular Security & Permission Control** with 3 execution modes (`DISCUSS`, `PLAN`, `FULL`) and interactive human-in-the-loop tool approval.
- **Dynamic MCP Client Integration** (Model Context Protocol) to discover and invoke tools from external MCP servers at runtime.
- **Persistent Conversational Memory** via SQLite and Spring Data JDBC.
- **Real-Time Streaming** via Server-Sent Events (SSE) consumed by the companion CLI (`openworker-cli`).

---

## 2. Repository Architecture & Layout

```
openworker-spring-ai/
├── src/main/java/com/openworker/
│   ├── OpenWorkerApplication.java             # Spring Boot main entry point
│   └── agent/
│       ├── config/                            # Spring Configuration Beans
│       │   ├── DatabaseConfig.java            # SQLite schema initialization & data source setup
│       │   └── McpConfig.java                 # MCP client & tool callback configuration
│       ├── controller/                        # REST & SSE API Controllers
│       │   ├── TurnController.java            # SSE streaming for turns, tool permission decisions
│       │   ├── SessionController.java         # Session CRUD, mode/model switching
│       │   ├── ModelController.java           # Model provider listing, runtime config & switching
│       │   └── McpServerController.java       # MCP server discovery, reload, add/remove
│       ├── interfaces/                        # Core Contracts
│       │   ├── TurnEngine.java                # Execution contract for agent turns
│       │   ├── AgentTool.java                 # Unified tool contract (built-in + MCP)
│       │   └── PermissionEngine.java          # Security evaluation contract
│       ├── langgraph/                         # Primary Agent Engine (LangGraph4j)
│       │   ├── LangGraphTurnEngineImpl.java   # ReAct StateGraph implementation (default engine)
│       │   └── AgentGraphState.java           # State representation for LangGraph messages & steps
│       ├── impl/                              # Secondary/Legacy Engine & Implementations
│       │   ├── TurnEngineImpl.java            # Spring AI ChatClient / ToolCallback loop engine
│       │   └── PermissionEngineImpl.java      # Role & mode-based permission evaluator
│       ├── services/                          # Business Services
│       │   ├── ConversationMemoryService.java # Session and message persistence in SQLite
│       │   ├── ToolApprovalService.java       # Async waiting & resolution for human tool approval
│       │   ├── ChatModelRouter.java           # Model & ChatClient resolution per turn/session
│       │   └── DynamicMcpServerManager.java   # Dynamic connection & tool sync with MCP servers
│       ├── providers/                         # LLM Provider Adapters
│       │   ├── ModelProvider.java             # Interface for provider configurations
│       │   ├── ModelProviderRegistry.java     # Registry of available LLM providers
│       │   ├── OpenAiModelProvider.java       # OpenAI / LM Studio provider
│       │   ├── OllamaModelProvider.java       # Ollama provider
│       │   ├── AnthropicModelProvider.java    # Anthropic provider
│       │   └── GoogleGenAiModelProvider.java  # Google Gemini provider
│       ├── repositories/                      # Spring Data Repositories
│       │   ├── SessionRepository.java         # CRUD repository for SessionEntity
│       │   └── MessageRepository.java         # CRUD repository for MessageEntity
│       ├── tools/                             # Built-in Agent Tools & Registry
│       │   ├── ToolRegistry.java              # Combines built-in tools + dynamic MCP tools
│       │   ├── InstrumentedToolCallback.java  # Wraps tools with permissions, counting, & SSE events
│       │   ├── McpAgentTool.java              # AgentTool adapter for external MCP tools
│       │   ├── FileReadTool.java              # file_read (READ_ONLY)
│       │   ├── FileWriteTool.java             # file_write (MUTATING)
│       │   ├── FileListTool.java              # file_list (READ_ONLY)
│       │   ├── FileSearchTool.java            # file_search (READ_ONLY)
│       │   ├── ShellExecTool.java             # shell_exec (MUTATING)
│       │   └── WebFetchTool.java              # web_fetch (READ_ONLY)
│       └── models/                            # DTOs and Data Models
│           ├── internals/                     # Internal entities (db, mcp, services)
│           │   ├── db/                        # SessionEntity, MessageEntity
│           │   ├── mcp/                       # McpServerDefinition
│           │   └── services/                  # AgentEvent, AgentEventType, AgentMode, etc.
│           └── publics/                       # Public API Request/Response DTOs
├── src/main/resources/
│   ├── application.yml                        # Main configuration (providers, models, engine, DB)
│   ├── schema.sql                             # SQLite DDL schema
│   └── mcp-servers.json                       # MCP server endpoints configuration
├── src/test/java/                             # Unit and integration test suites
│   └── com/openworker/agent/
│       ├── langgraph/LangGraphTurnEngineTest.java
│       └── OpenWorkerIntegrationTest.java
├── support/scripts/                           # Convenience starter scripts for different LLMs
└── pom.xml                                    # Maven dependencies & build configuration
```

---

## 3. Core Concepts & Execution Lifecycle

### 3.1 Turn Execution Engine (LangGraph4j vs Default)

The application supports two turn engine implementations switchable via `openworker.agent.engine.type`:

1. **`langgraph` (`LangGraphTurnEngineImpl`)** — *Default & Recommended*:
   - Implements a stateful graph cycle:
     ```
     [START] ──> [agent] (Model Call) ──> [shouldContinue]
                                                │
                                    ┌───────────┴───────────┐
                                    ▼                       ▼
                            [tools] (Act & Observe)       [END]
                                    │
                                    └──> [agent]
     ```
   - **Parallel Tool Execution**: When the LLM emits multiple tool calls, `executeTools` runs them concurrently via `CompletableFuture` on a virtual thread pool (`toolExecutor`).
   - **Self-Correction**: Tool errors are caught and returned as observations to the model so it can adapt or retry.
   - **Step Limiting**: Controlled termination when `maxSteps` is reached.

2. **`default` (`TurnEngineImpl`)**:
   - Uses Spring AI's native `ChatClient.prompt().tools(...).call()` loop.
   - Wraps tools with `InstrumentedToolCallback` to enforce max steps, stream SSE events, and record intermediate outputs.

### 3.2 Agent Modes & Permission Engine

The agent operates in 3 distinct safety modes defined in `AgentMode`:

| Mode | Risk Class `READ_ONLY` | Risk Class `MUTATING` | Description |
|---|---|---|---|
| **`DISCUSS`** | Asks user approval | Asks user approval | Interactive collaboration; all tool calls pause for human confirmation via `ToolApprovalService`. |
| **`PLAN`** | Allowed | Denied | Exploration & planning only; mutating tools (`file_write`, `shell_exec`) are rejected. |
| **`FULL`** | Allowed | Allowed | Autonomous execution; all tools are allowed to execute automatically. |

### 3.3 Human-in-the-Loop Tool Approval Workflow

In `DISCUSS` mode (or whenever a tool requires approval):
1. The engine calls `toolApprovalService.requestApproval(sessionId, toolCallId, toolName, input, reason, eventConsumer, timeoutSeconds)`.
2. A `CompletableFuture<Boolean>` is stored in `ToolApprovalService.pendingApprovals` by `toolCallId`.
3. An `AgentEvent` of type `PERMISSION_REQUIRED` is pushed across the SSE stream to the client with `toolCallId` and `toolName`.
4. The virtual thread blocks on `future.get(timeoutSeconds, TimeUnit.SECONDS)` (default 180s).
5. The client prompts the user and calls `POST /api/sessions/{sessionId}/permissions/{toolCallId}` with `{ "approved": true/false }`.
6. `resolveApproval` completes the future, unblocking the engine.

### 3.4 SSE Event Stream

Turns stream events via `POST /api/sessions/{sessionId}/turns` producing `text/event-stream`. Each event has SSE name `agent-event` and data payload `AgentEvent`:

| Event Type | Description |
|---|---|
| `STARTED` | Indicates the turn has begun. |
| `NARRATION` | Text streamed from the LLM to the user. |
| `TOOL_CALL` | A tool invocation was requested by the LLM (contains `toolName`, `toolCallId`, and argument JSON). |
| `TOOL_RESULT` | The tool output (observation) returned after execution. |
| `PERMISSION_REQUIRED` | The agent needs human approval to proceed with a tool in `DISCUSS` mode. |
| `COMPLETED` | Turn completed normally. |
| `ERROR` | Turn or tool encountered an unrecoverable failure. |
| `HALTED` | Turn was halted by the user; thread execution and tool calls were cleanly cancelled. |

---

## 4. REST API Reference

Port: `8765` (configurable in `application.yml`).

### Sessions (`SessionController`)
- `POST /api/sessions` — Create session (`{ "title": "...", "initialMode": "DISCUSS|PLAN|FULL", "provider": "...", "model": "..." }`)
- `GET /api/sessions` — List all sessions
- `GET /api/sessions/{sessionId}` — Get session details and full message history
- `PATCH /api/sessions/{sessionId}/mode` — Update session mode (`{ "mode": "DISCUSS|PLAN|FULL" }`)
- `PATCH /api/sessions/{sessionId}/model` — Update provider and model for session (`{ "provider": "...", "model": "..." }`)
- `DELETE /api/sessions/{sessionId}` — Delete session and its messages

### Turns (`TurnController`)
- `POST /api/sessions/{sessionId}/turns` — Submit prompt and stream execution events (SSE `text/event-stream`). Request body: `{ "prompt": "...", "mode": "...", "provider": "...", "model": "..." }`
- `POST /api/sessions/{sessionId}/halt` — Halt an active turn (also accessible via `/turns/halt`). Cancels running tools, interrupts execution, and unblocks pending approvals.
- `POST /api/sessions/{sessionId}/permissions/{toolCallId}` — Submit user approval decision (`{ "approved": true|false }`)

### Models (`ModelController`)
- `GET /api/models/providers` — List all configured providers, status, and supported models
- `GET /api/models/active` — Get current default provider and model
- `POST /api/models/active` — Set active default provider and model (`{ "provider": "...", "model": "..." }`)
- `POST /api/models/providers/{providerId}/config` — Update credentials / endpoint for provider at runtime

### MCP Servers (`McpServerController`)
- `GET /api/mcp/servers` — List active MCP servers, connected tools, and status
- `POST /api/mcp/reload` — Reload MCP servers from `src/main/resources/mcp-servers.json`
- `POST /api/mcp/servers` — Dynamically add an MCP server
- `DELETE /api/mcp/servers/{name}` — Disconnect and remove an MCP server

---

## 5. Companion Projects & Setup

1. **CLI Client**:
   - Location: `../openworker-cli` (or [openworker-spring-cli](https://github.com/shriamrut/openworker-spring-cli))
   - Built with Node.js, TypeScript, Inquirer, Chalk, Axios.
   - Run interactive chat:
     ```bash
     cd ../openworker-cli
     npm start -- chat
     ```

2. **Reference MCP Server**:
   - [openworker-test-mcp-server](https://github.com/shriamrut/openworker-test-mcp-server/)
   - Provides sample calculator tools (`calculator_add`, etc.) over SSE.

---

## 6. Build, Test, and Run Commands

### Prerequisites
- **Java 25** (JDK 25)
- **Maven 3.9+**

### Build
```bash
# Compile and package without running tests
mvn clean package -DskipTests

# Compile only
mvn clean compile
```

### Run Tests
```bash
# Run all unit and integration tests
mvn test

# Run a single test class
mvn test -Dtest=LangGraphTurnEngineTest
```

### Run Server Locally
Using convenience scripts:
```bash
# Gemini
export GEMINI_API_KEY="your-key"
./support/scripts/run-gemini-flash-latest.sh

# LM Studio (OpenAI compatible on localhost:1234)
./support/scripts/run-lmstudio.sh

# Remote Ollama
./support/scripts/run-remote-ollama.sh
```

Or run via JAR:
```bash
java -jar target/openworker-spring-ai-1.0.0-SNAPSHOT.jar \
  --openworker.models.default-provider=openai
```

---

## 7. Developer & Agent Rules of Thumb

When writing or modifying code in this repository:

1. **Spring AI ChatOptions Downcasting**:
   - Provider implementations (`OpenAiChatModel`, `OllamaChatModel`, etc.) downcast `Prompt.getOptions()` to their provider-specific option class (e.g. `OpenAiChatOptions`).
   - If mutating options with tools in LangGraph (`LangGraphTurnEngineImpl.buildOptions`), preserve the existing default options using `toolCalling.mutate().toolCallbacks(...).build()` rather than constructing a generic `ToolCallingChatOptions` from scratch.

2. **Virtual Threads & Thread Interruption**:
   - Virtual threads are enabled (`spring.threads.virtual.enabled: true`).
   - All tool executions and turns run in virtual threads.
   - When introducing long-running operations or waiting for external signals (e.g., approvals, turns, halt requests), handle `InterruptedException` cleanly and preserve interrupt status.

3. **Tool Registration Precedence**:
   - Built-in tools registered in `ToolRegistry` always take precedence over MCP tools with duplicate names.

4. **Database Operations**:
   - SQLite is used for local storage (`openworker.db`). Always use `@Transactional` when modifying sessions or messages in `ConversationMemoryService`.

5. **SSE Emitter Lifecycle**:
   - `SseEmitter` instances can be closed prematurely if clients disconnect or timeout.
   - Always catch `IllegalStateException` or wrap emitter calls to prevent unhandled exceptions when clients disconnect.
