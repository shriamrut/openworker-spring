# openworker-spring-ai

> An autonomous AI agent worker built with **Spring Boot 4**, **Spring AI 2**, and **LangGraph4j** — supporting multiple LLM providers out of the box.

---

## ✨ Features

- 🤖 **ReAct agent loop** powered by [LangGraph4j](https://github.com/bsc-s2/langgraph4j) (StateGraph) or Spring AI's built-in `ToolCallingAdvisor`
- 🔀 **Multi-provider support** — switch between Google Gemini, OpenAI-compatible (LM Studio), Ollama, and Anthropic Claude at runtime
- 🧩 **Dynamic MCP tool injection** via [Model Context Protocol](https://modelcontextprotocol.io) client
- 💾 **SQLite persistence** for conversation history
- ⚡ **Java 25 virtual threads** for high-concurrency I/O
- 🌐 REST API on port **8765**

---

## 🏗️ Tech Stack

| Layer | Technology |
|---|---|
| Runtime | Java 25, Spring Boot 4.0 |
| AI Framework | Spring AI 2.0 |
| Agent Engine | LangGraph4j 1.9 (ReAct StateGraph) |
| Persistence | SQLite via Spring Data JDBC |
| Tool Protocol | MCP (Model Context Protocol) client |
| Build | Maven |

---

## 🚀 Quick Start

### Prerequisites

- Java 25+
- Maven 3.9+
- API key for your chosen model provider (if using a cloud provider)

### Run with a starter script

Convenience scripts live in [`support/scripts/`](support/scripts/). Pick the one matching your setup:

| Script | Provider | Notes |
|---|---|---|
| `run-gemini-flash-latest.sh` | Google Gemini | Requires `GEMINI_API_KEY` env var |
| `run-lmstudio.sh` | LM Studio (local) | Requires LM Studio running on `localhost:1234` |
| `run-remote-ollama.sh` | Ollama (remote) | Configured for a remote tunnel URL |

#### Google Gemini

```bash
export GEMINI_API_KEY=your_key_here
# optionally override the model (defaults to gemini-2.5)
# export GEMINI_MODEL=gemini-flash-latest
./support/scripts/run-gemini-flash-latest.sh
```

The script passes the key directly via `--spring.ai.google.genai.api-key` — no manual edits to `application.yml` needed.

#### LM Studio (local)

```bash
./support/scripts/run-lmstudio.sh
```

Make sure LM Studio is serving on `http://localhost:1234/v1`.

#### Remote Ollama

```bash
./support/scripts/run-remote-ollama.sh
```

---

## ⚙️ Configuration

All configuration lives in [`src/main/resources/application.yml`](src/main/resources/application.yml).

### Key properties

| Property | Default | Description |
|---|---|---|
| `server.port` | `8765` | HTTP port |
| `openworker.models.default-provider` | `openai` | Active provider (`openai`, `ollama`, `anthropic`, `google-genai`) |
| `openworker.models.default-model` | `google/gemma-4-e2b` | Model name for the active provider |
| `openworker.agent.engine.type` | `langgraph` | Agent engine: `langgraph` or `default` |
| `openworker.agent.engine.max-steps` | `1000` | Max agent reasoning steps per request |

### Provider API keys (env vars)

| Provider | Environment Variable |
|---|---|
| Google Gemini | `GEMINI_API_KEY` |
| OpenAI / LM Studio | `OPENAI_API_KEY` |
| Anthropic | `ANTHROPIC_API_KEY` |
| Ollama base URL | `OLLAMA_BASE_URL` |

### Supported models (configured in `application.yml`)

**Google Gemini**: `gemini-flash-latest`, `gemini-3.6-flash`, `gemini-flash-lite-latest`, `gemini-pro-latest`

**OpenAI / Compatible**: `google/gemma-4-e2b`, `gpt-4o`, `gpt-4o-mini`, `gpt-4.1`, `o3-mini`

**Anthropic**: `claude-3-5-sonnet-20241022`, `claude-3-5-haiku-20241022`, `claude-3-opus-20240229`

**Ollama**: `llama3.2:1b`, `qwen2.5-coder:1.5b`, `deepseek-r1:1.5b`, `mistral`

---

## 💬 Interacting with the App

Use the companion CLI to send tasks to the agent:

👉 **[openworker-spring-cli](https://github.com/shriamrut/openworker-spring-cli)**

---

## 🧩 Dynamic Tool Injection via MCP

You can extend the agent's capabilities at runtime by connecting an MCP server. Use the reference implementation:

👉 **[openworker-test-mcp-server](https://github.com/shriamrut/openworker-test-mcp-server/)**

Configure your MCP servers in [`src/main/resources/mcp-servers.json`](src/main/resources/mcp-servers.json).

---

## 🔧 Building from Source

```bash
mvn clean package -DskipTests
java -jar target/openworker-spring-ai-1.0.0-SNAPSHOT.jar \
  --openworker.models.default-provider=google-genai \
  --spring.ai.google.genai.api-key=$GEMINI_API_KEY
```

---

## 📄 License

[LICENSE](LICENSE)
