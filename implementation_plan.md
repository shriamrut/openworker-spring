# OpenWorker Spring AI Implementation Plan (Self-Guided Implementation)

This plan details the architecture and step-by-step implementation roadmap for porting the OpenWorker coworker platform from Python (`coworker`) to Java using Spring AI and Spring Boot. 

Since you want to do the coding and heavy lifting to understand Spring AI, **our roles are structured as follows**:
* **Antigravity (Your Guide)**: Design the architecture, provide detailed structural layouts, explain Spring AI core concepts (like `ChatModel`, `ChatClient`, `ToolCallback`, and `spring-ai-mcp`), provide skeleton templates/configurations, and help debug runtime or compilation errors.
* **You (The Builder)**: Write and refine the Java classes, implement the core business logic, wire the components, and configure the application.

## Tech Stack Choices
* **Framework**: Spring Boot 4.0.0 & Spring Framework 7.0 (Leveraging Java 21+ features).
* **AI Library**: Spring AI 2.0.0 (Supports Jackson 3, updated `ChatClient` APIs, and official Model Context Protocol integrations).
* **Build System**: Maven (`pom.xml`).
* **Web Layer**: Spring MVC (`spring-boot-starter-web`) with `SseEmitter` for Server-Sent Events streaming.
* **Concurrency**: Project Loom Virtual Threads (`spring.threads.virtual.enabled=true`) for non-blocking I/O execution. All async work (agent loops, tool execution) runs on virtual threads via `Executors.newVirtualThreadPerTaskExecutor()`.
* **Database**: SQLite (`sqlite-jdbc` + Spring Data JDBC) for lightweight local persistence.

> [!IMPORTANT]
> **Why Spring MVC + Virtual Threads instead of WebFlux?**
> - The agent loop is inherently imperative: query LLM → check tool calls → ask permission → execute → loop. This maps naturally to `while` loops and `if-else`, not reactive pipelines.
> - Spring AI's core APIs (`ChatModel.call(...)`, `ChatClient`) are synchronous by design.
> - Virtual threads eliminate thread-exhaustion concerns — when the agent blocks on an LLM HTTP call, the JVM parks the virtual thread and frees the carrier OS thread.
> - SSE streaming is achieved via `SseEmitter` with the agent loop pushing events from a virtual thread.
> - Result: WebFlux-like scalability with dramatically simpler code.

> [!IMPORTANT]
> **Java 21 Requirement for Virtual Threads**
> Ensure your local JDK version is **Java 21** or higher. While Spring Boot 4.0 supports Java 17, virtual threads require Java 21+.

---

## Proposed Scaffolding Steps

### Phase 1: Project Bootstrapping
1. **Initialize Project Directory**: Create directory structure for a standard Maven project.
2. **Build Configuration (`pom.xml`)**: Configure the parent POM to Spring Boot 4.0.0 and import the Spring AI 2.0.0 BOM with `spring-boot-starter-web` (NOT `webflux`).
3. **Application Entrypoint (`OpenWorkerApplication.java`)**: Create the main class.
4. **Application Properties (`application.properties`)**: Enable virtual threads with `spring.threads.virtual.enabled=true`.

### Phase 2: Core Agent Engine (The `TurnEngine`)
1. **Understand Spring AI `ChatModel`**: Explore manual tool invocation and context assembly.
2. **Implement `TurnEngine`**: Construct the imperative agent loop using standard `while`/`if-else` control flow. Push progress events via a `Consumer<AgentEvent>` callback.
3. **Implement `PermissionEngine`**: Add Discuss/Plan state machines and verify tools before invocation.
4. **SSE Controller (`TurnController`)**: Return `SseEmitter` from the endpoint. Submit the `TurnEngine.executeTurn(...)` call to a virtual thread executor. The callback pushes `AgentEvent` objects to the emitter as SSE data frames.

### Phase 3: Built-in Tools
1. **Shell Executor Tool** (`@Tool`): Execute shell commands via `ProcessBuilder`, capture stdout/stderr.
2. **File Operations Tool** (`@Tool`): Read, write, list, search files.
3. **Web Search / Fetch Tool** (`@Tool`): HTTP client for fetching URLs.

### Phase 4: Memory & Persistence
1. **SQLite Schema**: Sessions, messages, tool results.
2. **Spring Data JDBC Repositories**: `SessionRepository`, `MessageRepository`.
3. **Context Assembly**: Load conversation history from DB before each LLM call.

### Phase 5: API & Integration
1. **REST API**: Session CRUD, turn submission (SSE stream), approval/rejection endpoints.
2. **MCP Server** (optional): Expose the agent's tools as an MCP server for external clients.

---

## Key Architecture: TurnEngine with Spring MVC

```java
// TurnController.java — SSE endpoint using SseEmitter + Virtual Threads
@RestController
public class TurnController {
    private final TurnEngine turnEngine;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public TurnController(TurnEngine turnEngine) {
        this.turnEngine = turnEngine;
    }

    @GetMapping(value = "/api/sessions/{sessionId}/turns", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTurn(@PathVariable String sessionId, @RequestParam String prompt) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5 min timeout
        executor.submit(() -> {
            try {
                turnEngine.executeTurn(sessionId, prompt, event -> {
                    try {
                        emitter.send(SseEmitter.event().name("message").data(event));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
