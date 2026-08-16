
---

#### Updated `task.md`

```markdown
- [x] Phase 1: Project Bootstrapping
  - [x] Create Maven directory structure
  - [x] Create `pom.xml` with Spring Boot 4.0.0 + Spring AI 2.0.0 BOM + `spring-boot-starter-web`
  - [x] Create `OpenWorkerApplication.java`
  - [ ] Add `application.properties` with `spring.threads.virtual.enabled=true`

- [ ] Phase 2: Core Agent Engine (TurnEngine) — Spring MVC + Virtual Threads
  - [ ] Define `AgentEvent` record/class (event types: THINKING, TOOL_CALL, TOOL_RESULT, TEXT, DONE, ERROR)
  - [ ] Implement `TurnEngine.executeTurn(sessionId, prompt, Consumer<AgentEvent>)` — imperative loop
  - [ ] Create `TurnController` with `SseEmitter` endpoint + virtual thread executor
  - [ ] Implement `PermissionEngine` for tool approval gating
  - [ ] Wire `ChatClient` with `@Tool`-annotated beans

- [ ] Phase 3: Built-in Tools
  - [ ] Shell Executor (`ProcessBuilder`-based)
  - [ ] File Operations (read, write, list, search)
  - [ ] Web Fetch (HttpClient-based URL fetcher)

- [ ] Phase 4: Memory & Persistence
  - [ ] SQLite schema (sessions, messages, tool_results)
  - [ ] Spring Data JDBC repositories
  - [ ] Context assembly from DB for LLM calls

- [ ] Phase 5: API & Integration
  - [ ] REST API: Session CRUD, approval/rejection endpoints
  - [ ] (Optional) MCP Server exposure
