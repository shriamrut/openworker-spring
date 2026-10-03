package com.openworker.agent;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.openworker.agent.interfaces.TurnEngine;
import com.openworker.agent.models.internals.services.AgentEvent;
import com.openworker.agent.models.internals.services.AgentMode;
import com.openworker.agent.models.publics.CreateSessionRequest;
import com.openworker.agent.models.publics.SessionSummaryResponse;
import com.openworker.agent.models.publics.UpdateModeRequest;
import com.openworker.agent.services.ConversationMemoryService;
import com.openworker.agent.tools.FileListTool;
import com.openworker.agent.tools.FileReadTool;
import com.openworker.agent.tools.FileSearchTool;
import com.openworker.agent.tools.FileWriteTool;
import com.openworker.agent.tools.ShellExecTool;
import com.openworker.agent.tools.ToolRegistry;

import org.springframework.test.web.servlet.MvcResult;
import com.openworker.agent.models.publics.TurnRequest;
import com.openworker.agent.tools.McpAgentTool;
import com.openworker.agent.models.internals.services.ToolRiskClass;
import com.openworker.agent.impl.PermissionEngineImpl;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import com.openworker.agent.models.internals.mcp.McpServerDefinition;
import com.openworker.agent.services.DynamicMcpServerManager;
import com.openworker.agent.controller.McpServerController.AddServerRequest;
import com.openworker.agent.interfaces.AgentTool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import java.time.Duration;
import org.springframework.ai.chat.client.ChatClient;
import com.openworker.agent.models.internals.services.AgentEventType;
import com.openworker.agent.impl.TurnEngineImpl;
import com.openworker.agent.tools.InstrumentedToolCallback;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.Map;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class OpenWorkerIntegrationTest {

        @Autowired
        private WebApplicationContext webApplicationContext;

        private MockMvc mockMvc;

        private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

        @Autowired
        private ConversationMemoryService memoryService;

        @Autowired
        private ToolRegistry toolRegistry;

        @Autowired
        private TurnEngine turnEngine;

        @Autowired
        private com.openworker.agent.providers.ModelProviderRegistry modelProviderRegistry;

        @Autowired
        private com.openworker.agent.services.ChatModelRouter chatModelRouter;

        @Autowired
        private ChatClient.Builder chatClientBuilder;

        @Autowired
        private PermissionEngineImpl permissionEngine;

        @Autowired
        private com.openworker.agent.services.ToolApprovalService toolApprovalService;

        @Autowired
        private FileReadTool fileReadTool;

        @Autowired
        private FileListTool fileListTool;

        @Autowired
        private FileWriteTool fileWriteTool;

        @Autowired
        private FileSearchTool fileSearchTool;

        @Autowired
        private ShellExecTool shellExecTool;

        @BeforeEach
        void setUp() {
                this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        }

        @Nested
        @DisplayName("1. Session REST API & SQLite Persistence Tests")
        class SessionApiTests {

                @Test
                @DisplayName("Full Session Lifecycle: Create -> List -> Update Mode -> Get -> Delete")
                void testSessionLifecycle() throws Exception {
                        // 1. Create Session
                        CreateSessionRequest createReq = new CreateSessionRequest("Integration Test Session",
                                        AgentMode.DISCUSS);
                        String createJson = objectMapper.writeValueAsString(createReq);

                        String responseBody = mockMvc.perform(post("/api/sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(createJson))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.id").isNotEmpty())
                                        .andExpect(jsonPath("$.title").value("Integration Test Session"))
                                        .andExpect(jsonPath("$.agentMode").value("DISCUSS"))
                                        .andReturn().getResponse().getContentAsString();

                        SessionSummaryResponse session = objectMapper.readValue(responseBody,
                                        SessionSummaryResponse.class);
                        String sessionId = session.id();

                        // 2. List Sessions
                        mockMvc.perform(get("/api/sessions"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$").isArray());

                        // 3. Update Mode to FULL
                        UpdateModeRequest updateReq = new UpdateModeRequest(AgentMode.FULL);
                        mockMvc.perform(patch("/api/sessions/" + sessionId + "/mode")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(updateReq)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.agentMode").value("FULL"));

                        // 4. Save a mock message & Retrieve Session Details with History
                        memoryService.saveMessage(sessionId, "USER", "Hello agent from test!");
                        memoryService.saveMessage(sessionId, "ASSISTANT", "Hello user, I am ready.");

                        mockMvc.perform(get("/api/sessions/" + sessionId))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.id").value(sessionId))
                                        .andExpect(jsonPath("$.messages.length()").value(2))
                                        .andExpect(jsonPath("$.messages[0].content").value("Hello agent from test!"))
                                        .andExpect(jsonPath("$.messages[1].content").value("Hello user, I am ready."));

                        // 5. Delete Session
                        mockMvc.perform(delete("/api/sessions/" + sessionId))
                                        .andExpect(status().isNoContent());

                        // 6. Verify deleted
                        mockMvc.perform(get("/api/sessions/" + sessionId))
                                        .andExpect(status().isNotFound());
                }
        }

        @Nested
        @DisplayName("2. Built-in Tools Execution Tests")
        class BuiltInToolsTests {

                @Test
                @DisplayName("ToolRegistry should contain all registered AgentTools")
                void testToolRegistry() {
                        assertThat(toolRegistry.getAllTools()).hasSizeGreaterThanOrEqualTo(5);
                        assertThat(toolRegistry.getTool("file_read")).isPresent();
                        assertThat(toolRegistry.getTool("file_write")).isPresent();
                        assertThat(toolRegistry.getTool("file_list")).isPresent();
                        assertThat(toolRegistry.getTool("file_search")).isPresent();
                        assertThat(toolRegistry.getTool("shell_exec")).isPresent();
                }

                @Test
                @DisplayName("File Write and Read Tool execution")
                void testFileWriteAndReadTool() throws Exception {
                        Path tempFile = Files.createTempFile("openworker_test_", ".txt");
                        try {
                                // Write
                                String writeResult = fileWriteTool.execute(tempFile.toAbsolutePath().toString(),
                                                "Line 1\nLine 2\nLine 3", true);
                                assertThat(writeResult).contains("Successfully");

                                // Read
                                String readResult = fileReadTool.execute(tempFile.toAbsolutePath().toString(), 1, 2);
                                assertThat(readResult).contains("Line 1").contains("Line 2");
                        } finally {
                                Files.deleteIfExists(tempFile);
                        }
                }

                @Test
                @DisplayName("FileListTool and FileSearchTool execution")
                void testFileListAndSearchTool() {
                        String listResult = fileListTool.execute(".", false);
                        assertThat(listResult).contains("pom.xml");

                        String searchResult = fileSearchTool.execute(".", "pom.xml", "openworker-spring-ai");
                        assertThat(searchResult).contains("pom.xml");
                }

                @Test
                @DisplayName("ShellExecTool execution")
                void testShellExecTool() {
                        String result = shellExecTool.execute("echo 'OpenWorker Shell Test'", ".", 5);
                        assertThat(result).contains("Exit Code: 0");
                        assertThat(result).contains("OpenWorker Shell Test");
                }
        }

        @Nested
        @Disabled
        @DisplayName("3. Live LLM Turn Execution API Tests (LM studio)")
        class LiveTurnExecutionTests {

                @Test
                @DisplayName("Test tool use via API")
                void testLiveTurnExecutionForToolUseViaApi() throws Exception {
                        // 1. Create Session via API
                        CreateSessionRequest createReq = new CreateSessionRequest("SSE API Live Test Session",
                                        AgentMode.DISCUSS);
                        String responseBody = mockMvc.perform(post("/api/sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                                        .andExpect(status().isCreated())
                                        .andReturn().getResponse().getContentAsString();

                        SessionSummaryResponse session = objectMapper.readValue(responseBody,
                                        SessionSummaryResponse.class);
                        String sessionId = session.id();

                        // 2. Stream Turn via SSE endpoint
                        TurnRequest turnRequest = new TurnRequest(
                                        "Check the number of files in /Users/<username>/Desktop/codes directory?",
                                        AgentMode.FULL);
                        MvcResult mvcResult = mockMvc.perform(post("/api/sessions/" + sessionId + "/turns")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(turnRequest)))
                                        .andExpect(request().asyncStarted())
                                        .andReturn();

                        // Wait for async execution
                        mockMvc.perform(asyncDispatch(mvcResult))
                                        .andExpect(status().isOk());

                        String sseContent = mvcResult.getResponse().getContentAsString();
                        System.out.println("SSE Content: " + sseContent);
                }

                @Test
                // @Disabled
                @DisplayName("Stream live Turn via SSE endpoint (POST /api/sessions/{id}/turns)")
                void testLiveTurnExecutionViaApi() throws Exception {
                        // 1. Create Session via API
                        CreateSessionRequest createReq = new CreateSessionRequest("SSE API Live Test Session",
                                        AgentMode.DISCUSS);
                        String responseBody = mockMvc.perform(post("/api/sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                                        .andExpect(status().isCreated())
                                        .andReturn().getResponse().getContentAsString();

                        SessionSummaryResponse session = objectMapper.readValue(responseBody,
                                        SessionSummaryResponse.class);
                        String sessionId = session.id();

                        // 2. Stream Turn via SSE endpoint
                        TurnRequest turnRequest = new TurnRequest("Say 'api test passed' in three words.",
                                        AgentMode.DISCUSS);
                        MvcResult mvcResult = mockMvc.perform(post("/api/sessions/" + sessionId + "/turns")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(turnRequest)))
                                        .andExpect(request().asyncStarted())
                                        .andReturn();

                        // Wait for async execution
                        mockMvc.perform(asyncDispatch(mvcResult))
                                        .andExpect(status().isOk());

                        String sseContent = mvcResult.getResponse().getContentAsString();

                        // 3. Validate SSE event stream frames
                        assertThat(sseContent).contains("event:agent-event");
                        assertThat(sseContent).contains("\"type\":\"STARTED\"");
                        assertThat(sseContent).contains("\"type\":\"NARRATION\"");
                        assertThat(sseContent).contains("\"type\":\"COMPLETED\"");

                        // 4. Verify message history was persisted into DB through the API call
                        mockMvc.perform(get("/api/sessions/" + sessionId))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.messages.length()").value(2))
                                        .andExpect(jsonPath("$.messages[0].messageType").value("USER"))
                                        .andExpect(jsonPath("$.messages[0].content")
                                                        .value("Say 'api test passed' in three words."))
                                        .andExpect(jsonPath("$.messages[1].messageType").value("ASSISTANT"));
                }
        }

        @Nested
        @DisplayName("4. MCP Integration & Dynamic Tool Discovery Tests")
        class McpIntegrationTests {

                @Test
                @DisplayName("McpAgentTool should default to CONSEQUENTIAL risk class for security and isolation")
                void testMcpAgentToolDefaultConsequentialRisk() {
                        ToolCallback mcpCallback = mock(ToolCallback.class);
                        ToolDefinition def = mock(ToolDefinition.class);
                        when(mcpCallback.getToolDefinition()).thenReturn(def);
                        when(def.name()).thenReturn("sqlite_read_query");
                        when(def.description()).thenReturn("Execute read-only SQL query");

                        // By default, external MCP tools are treated as CONSEQUENTIAL
                        McpAgentTool defaultTool = new McpAgentTool(mcpCallback);
                        assertThat(defaultTool.getName()).isEqualTo("sqlite_read_query");
                        assertThat(defaultTool.getDescription()).isEqualTo("Execute read-only SQL query");
                        assertThat(defaultTool.getRiskClass()).isEqualTo(ToolRiskClass.CONSEQUENTIAL);

                        // Allows explicit override if an allowlisted tool is marked READ_ONLY
                        McpAgentTool overriddenTool = new McpAgentTool(mcpCallback, ToolRiskClass.READ_ONLY);
                        assertThat(overriddenTool.getRiskClass()).isEqualTo(ToolRiskClass.READ_ONLY);
                }

                @Test
                @DisplayName("ToolRegistry should dynamically blend MCP tools with built-in tools")
                @SuppressWarnings("unchecked")
                void testToolRegistryMcpBlending() {
                        ToolCallback mcpCallback1 = mock(ToolCallback.class);
                        ToolDefinition def1 = mock(ToolDefinition.class);
                        when(mcpCallback1.getToolDefinition()).thenReturn(def1);
                        when(def1.name()).thenReturn("external_weather_get");
                        when(def1.description()).thenReturn("Fetch weather report");

                        ToolCallback mcpCallback2 = mock(ToolCallback.class);
                        ToolDefinition def2 = mock(ToolDefinition.class);
                        when(mcpCallback2.getToolDefinition()).thenReturn(def2);
                        when(def2.name()).thenReturn("external_notification_send");
                        when(def2.description()).thenReturn("Send push notification");

                        SyncMcpToolCallbackProvider mcpProvider = mock(SyncMcpToolCallbackProvider.class);
                        when(mcpProvider.getToolCallbacks())
                                        .thenReturn(new ToolCallback[] { mcpCallback1, mcpCallback2 });

                        ObjectProvider<SyncMcpToolCallbackProvider> objectProvider = mock(ObjectProvider.class);
                        when(objectProvider.getIfAvailable()).thenReturn(mcpProvider);

                        ToolRegistry registry = new ToolRegistry(List.of(fileReadTool, fileWriteTool), objectProvider);

                        assertThat(registry.hasMcpProvider()).isTrue();
                        assertThat(registry.getBuiltInTools()).hasSize(2);
                        assertThat(registry.getMcpTools()).hasSize(2);

                        // Unified catalog has 4 tools
                        assertThat(registry.getAllTools()).hasSize(4);
                        assertThat(registry.getTool("external_weather_get")).isPresent();
                        assertThat(registry.getTool("external_notification_send")).isPresent();
                        assertThat(registry.getTool("file_read")).isPresent();
                        assertThat(registry.getTool("file_write")).isPresent();
                        assertThat(registry.getTool("non_existent")).isEmpty();
                }

                @Test
                @DisplayName("ToolRegistry should give precedence to built-in tools if MCP tool collides")
                @SuppressWarnings("unchecked")
                void testToolRegistryCollisionPrecedence() {
                        ToolCallback collidingCallback = mock(ToolCallback.class);
                        ToolDefinition def = mock(ToolDefinition.class);
                        when(collidingCallback.getToolDefinition()).thenReturn(def);
                        when(def.name()).thenReturn("file_read"); // Collides with built-in

                        SyncMcpToolCallbackProvider mcpProvider = mock(SyncMcpToolCallbackProvider.class);
                        when(mcpProvider.getToolCallbacks()).thenReturn(new ToolCallback[] { collidingCallback });

                        ObjectProvider<SyncMcpToolCallbackProvider> objectProvider = mock(ObjectProvider.class);
                        when(objectProvider.getIfAvailable()).thenReturn(mcpProvider);

                        ToolRegistry registry = new ToolRegistry(List.of(fileReadTool), objectProvider);

                        // Built-in tool takes precedence, total count is still 1
                        assertThat(registry.getAllTools()).hasSize(1);
                        assertThat(registry.getTool("file_read").orElseThrow()).isSameAs(fileReadTool);
                }

                @Test
                @DisplayName("PermissionEngine should enforce safety policies on MCP tools in DISCUSS, PLAN, and FULL modes")
                @SuppressWarnings("unchecked")
                void testPermissionEngineWithMcpTools() {
                        ToolCallback mcpCallback = mock(ToolCallback.class);
                        ToolDefinition mcpDef = mock(ToolDefinition.class);
                        when(mcpCallback.getToolDefinition()).thenReturn(mcpDef);
                        when(mcpDef.name()).thenReturn("mcp_service_call");

                        SyncMcpToolCallbackProvider mcpProvider = mock(SyncMcpToolCallbackProvider.class);
                        when(mcpProvider.getToolCallbacks()).thenReturn(new ToolCallback[] { mcpCallback });

                        ObjectProvider<SyncMcpToolCallbackProvider> objectProvider = mock(ObjectProvider.class);
                        when(objectProvider.getIfAvailable()).thenReturn(mcpProvider);

                        ToolRegistry registry = new ToolRegistry(List.of(fileReadTool), objectProvider);
                        PermissionEngineImpl permissionEngine = new PermissionEngineImpl(registry);

                        // Default CONSEQUENTIAL MCP tool is denied in DISCUSS and PLAN, allowed in FULL
                        assertThat(permissionEngine.evaluate(AgentMode.DISCUSS, "mcp_service_call", Map.of()).allowed())
                                        .isFalse();
                        assertThat(permissionEngine.evaluate(AgentMode.PLAN, "mcp_service_call", Map.of()).allowed())
                                        .isFalse();
                        assertThat(permissionEngine.evaluate(AgentMode.FULL, "mcp_service_call", Map.of()).allowed())
                                        .isTrue();

                        // In DISCUSS mode, all tools require user approval; in PLAN mode, read-only tools are allowed
                        assertThat(permissionEngine.evaluate(AgentMode.DISCUSS, "file_read", Map.of()).needsUserApproval())
                                        .isTrue();
                        assertThat(permissionEngine.evaluate(AgentMode.PLAN, "file_read", Map.of()).allowed())
                                        .isTrue();

                        // Unregistered tool denied
                        assertThat(permissionEngine.evaluate(AgentMode.FULL, "unknown_tool", Map.of()).allowed())
                                        .isFalse();
                }

                @Test
                @DisplayName("ToolRegistry should dynamically discover tools from DynamicMcpServerManager")
                @SuppressWarnings("unchecked")
                void testDynamicMcpServerManagerToolBlending() {
                        ToolCallback mcpCallback = mock(ToolCallback.class);
                        ToolDefinition def = mock(ToolDefinition.class);
                        when(mcpCallback.getToolDefinition()).thenReturn(def);
                        when(def.name()).thenReturn("dynamic_fetch_metrics");
                        when(def.description()).thenReturn("Fetch runtime metrics");

                        DynamicMcpServerManager dynamicManager = mock(DynamicMcpServerManager.class);
                        when(dynamicManager.getToolCallbacks()).thenReturn(new ToolCallback[] { mcpCallback });

                        ObjectProvider<DynamicMcpServerManager> dynamicProvider = mock(ObjectProvider.class);
                        when(dynamicProvider.getIfAvailable()).thenReturn(dynamicManager);

                        ToolRegistry registry = new ToolRegistry(List.of(fileReadTool), null, dynamicProvider);

                        assertThat(registry.hasMcpProvider()).isTrue();
                        assertThat(registry.getMcpTools()).hasSize(1);
                        assertThat(registry.getTool("dynamic_fetch_metrics")).isPresent();
                        assertThat(registry.getTool("dynamic_fetch_metrics").get().getRiskClass())
                                        .isEqualTo(ToolRiskClass.CONSEQUENTIAL);
                }
        }

        @Nested
        @DisplayName("5. Dynamic MCP Server Management REST API Tests")
        class DynamicMcpManagementTests {

                @Test
                @DisplayName("GET /api/mcp/servers should report configured MCP servers and status")
                void testGetMcpServers() throws Exception {
                        mockMvc.perform(get("/api/mcp/servers"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$").isArray())
                                        .andExpect(jsonPath("$[0].name").value("test-mcp-server"))
                                        .andExpect(jsonPath("$[0].type").value("sse"))
                                        .andExpect(jsonPath("$[0].target").value("http://localhost:8000/sse"));
                }

                @Test
                @DisplayName("POST /api/mcp/reload should reload server configurations without error")
                void testReloadMcpServers() throws Exception {
                        mockMvc.perform(post("/api/mcp/reload"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.status").value("RELOADED"))
                                        .andExpect(jsonPath("$.servers").isArray());
                }

                @Test
                @DisplayName("Dynamic Add and Remove server via REST API without file persistence")
                void testAddAndRemoveMcpServer() throws Exception {
                        // 1. Add server dynamically with persist=false
                        McpServerDefinition def = new McpServerDefinition("echo", List.of("hello"), null, null, null);
                        AddServerRequest req = new AddServerRequest("ephemeral-test-server", def, false);

                        mockMvc.perform(post("/api/mcp/servers")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                                        .andExpect(status().is2xxSuccessful())
                                        .andExpect(jsonPath("$.name").value("ephemeral-test-server"))
                                        .andExpect(jsonPath("$.connected").value(false));

                        // 2. Verify server appears in list
                        mockMvc.perform(get("/api/mcp/servers"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$[?(@.name == 'ephemeral-test-server')]").exists());

                        // 3. Remove server
                        mockMvc.perform(delete("/api/mcp/servers/ephemeral-test-server?persist=false"))
                                        .andExpect(status().isNoContent());

                        // 4. Verify removed
                        mockMvc.perform(get("/api/mcp/servers"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$[?(@.name == 'ephemeral-test-server')]").doesNotExist());
                }
        }

        @Nested
        @Disabled
        @DisplayName("5. Live MCP Server Integration Tests")
        class LivePythonMcpServerTests {
                @Test
                @DisplayName("Connect to test MCP server via HTTP/SSE endpoint, discover tools, and execute calculator_add")
                @SuppressWarnings("unchecked")
                void testLivePythonMcpServerSse() throws Exception {
                        HttpClientSseClientTransport transport = HttpClientSseClientTransport
                                        .builder("http://localhost:8000/sse")
                                        .build();

                        McpSyncClient mcpClient = McpClient.sync(transport)
                                        .requestTimeout(Duration.ofSeconds(10))
                                        .build();

                        try {
                                mcpClient.initialize();

                                SyncMcpToolCallbackProvider mcpProvider = new SyncMcpToolCallbackProvider(
                                                List.of(mcpClient));
                                ObjectProvider<SyncMcpToolCallbackProvider> providerMock = mock(ObjectProvider.class);
                                when(providerMock.getIfAvailable()).thenReturn(mcpProvider);

                                ToolRegistry liveRegistry = new ToolRegistry(List.of(fileReadTool), providerMock);

                                assertThat(liveRegistry.getMcpTools()).isNotEmpty();
                                assertThat(liveRegistry.getTool("calculator_add")).isPresent();

                                AgentTool calcTool = liveRegistry.getTool("calculator_add").orElseThrow();
                                String calcResult = calcTool.toToolCallback().call("{\"a\": 50, \"b\": 50}");
                                assertThat(calcResult).contains("100");
                        } finally {
                                mcpClient.close();
                        }
                }
        }

        @Nested
        @DisplayName("6. Multi-Step Reasoning & InstrumentedToolCallback Tests")
        class MultiStepReasoningTests {

                /**
                 * Verifies that a successful tool execution emits both TOOL_CALL and
                 * TOOL_RESULT events in that order, and increments the step counter.
                 * This is the core contract for real-time SSE streaming of tool steps.
                 */
                @Test
                @DisplayName("Successful tool call emits TOOL_CALL then TOOL_RESULT events in order")
                void testToolCallAndResultEventsEmittedInOrder() {
                        List<AgentEvent> events = new ArrayList<>();
                        AtomicInteger stepCounter = new AtomicInteger(0);

                        // file_read is READ_ONLY → allowed in all modes including DISCUSS
                        InstrumentedToolCallback instrumented = new InstrumentedToolCallback(
                                        fileReadTool,
                                        permissionEngine,
                                        AgentMode.FULL,
                                        events::add,
                                        stepCounter,
                                        15,
                                        new com.fasterxml.jackson.databind.ObjectMapper(),
                                        null);

                        instrumented.call("{\"path\": \"pom.xml\", \"startLine\": 1, \"endLine\": 3}");

                        // Both call and result events must be present
                        List<AgentEventType> eventTypes = events.stream().map(AgentEvent::type).toList();
                        assertThat(eventTypes).contains(AgentEventType.TOOL_CALL, AgentEventType.TOOL_RESULT);

                        // TOOL_CALL must come before TOOL_RESULT
                        int callIdx = eventTypes.indexOf(AgentEventType.TOOL_CALL);
                        int resultIdx = eventTypes.indexOf(AgentEventType.TOOL_RESULT);
                        assertThat(callIdx).isLessThan(resultIdx);

                        // Step counter must have been incremented exactly once
                        assertThat(stepCounter.get()).isEqualTo(1);

                        // The TOOL_CALL event should carry the tool name
                        AgentEvent callEvent = events.get(callIdx);
                        assertThat(callEvent.toolName()).isEqualTo("file_read");
                }

                /**
                 * Verifies that a CONSEQUENTIAL (mutating) tool is blocked in DISCUSS mode:
                 * - emits TOOL_CALL (the attempt is recorded)
                 * - emits PERMISSION_REQUIRED (the denial reason)
                 * - does NOT emit TOOL_RESULT (tool was never executed)
                 * - returns a "Permission Denied" string back to the LLM so it can explain
                 */
                @Test
                @DisplayName("CONSEQUENTIAL tool blocked in DISCUSS mode: emits PERMISSION_REQUIRED, no TOOL_RESULT")
                void testPermissionRequiredEventInDiscussMode() {
                        List<AgentEvent> events = new ArrayList<>();
                        AtomicInteger stepCounter = new AtomicInteger(0);

                        // shell_exec is DESTRUCTIVE → denied in DISCUSS and PLAN modes
                        InstrumentedToolCallback instrumented = new InstrumentedToolCallback(
                                        shellExecTool,
                                        permissionEngine,
                                        AgentMode.DISCUSS, // DISCUSS → mutating tools are blocked
                                        events::add,
                                        stepCounter,
                                        15,
                                        new com.fasterxml.jackson.databind.ObjectMapper(),
                                        null);

                        String result = instrumented.call("{\"command\": \"rm -rf /tmp/test\"}");

                        List<AgentEventType> eventTypes = events.stream().map(AgentEvent::type).toList();

                        // Permission denial must be surfaced
                        assertThat(result).contains("Permission Denied");
                        assertThat(eventTypes).contains(AgentEventType.TOOL_CALL);
                        assertThat(eventTypes).contains(AgentEventType.PERMISSION_REQUIRED);

                        // The tool must NOT have been executed
                        assertThat(eventTypes).doesNotContain(AgentEventType.TOOL_RESULT);
                }

                @Test
                @DisplayName("DISCUSS mode: tool requires approval, user approves via API -> tool executes and emits TOOL_RESULT")
                void testDiscussModeApprovalFlow() throws Exception {
                        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
                        AtomicInteger stepCounter = new AtomicInteger(0);
                        String sessionId = "discuss-approval-test-" + UUID.randomUUID();

                        InstrumentedToolCallback instrumented = new InstrumentedToolCallback(
                                        fileReadTool,
                                        permissionEngine,
                                        AgentMode.DISCUSS,
                                        events::add,
                                        stepCounter,
                                        15,
                                        objectMapper,
                                        null,
                                        toolApprovalService,
                                        sessionId);

                        java.util.concurrent.CompletableFuture<String> callFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                                        instrumented.call("{\"path\": \"pom.xml\"}"));

                        long deadline = System.currentTimeMillis() + 5000;
                        AgentEvent permEvent = null;
                        while (System.currentTimeMillis() < deadline) {
                                permEvent = events.stream()
                                                .filter(e -> e.type() == AgentEventType.PERMISSION_REQUIRED)
                                                .findFirst()
                                                .orElse(null);
                                if (permEvent != null) break;
                                Thread.sleep(50);
                        }
                        assertThat(permEvent).isNotNull();
                        assertThat(permEvent.toolCallId()).isNotNull();

                        mockMvc.perform(post("/api/sessions/" + sessionId + "/permissions/" + permEvent.toolCallId())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(new com.openworker.agent.models.publics.PermissionDecisionRequest(true))))
                                        .andExpect(status().isOk());

                        String result = callFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                        assertThat(result).contains("openworker-spring-ai");

                        List<AgentEventType> eventTypes = events.stream().map(AgentEvent::type).toList();
                        assertThat(eventTypes).contains(AgentEventType.TOOL_CALL);
                        assertThat(eventTypes).contains(AgentEventType.PERMISSION_REQUIRED);
                        assertThat(eventTypes).contains(AgentEventType.TOOL_RESULT);
                }

                @Test
                @DisplayName("DISCUSS mode: tool requires approval, user rejects via API -> tool is blocked")
                void testDiscussModeRejectionFlow() throws Exception {
                        List<AgentEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
                        AtomicInteger stepCounter = new AtomicInteger(0);
                        String sessionId = "discuss-rejection-test-" + UUID.randomUUID();

                        InstrumentedToolCallback instrumented = new InstrumentedToolCallback(
                                        fileReadTool,
                                        permissionEngine,
                                        AgentMode.DISCUSS,
                                        events::add,
                                        stepCounter,
                                        15,
                                        objectMapper,
                                        null,
                                        toolApprovalService,
                                        sessionId);

                        java.util.concurrent.CompletableFuture<String> callFuture = java.util.concurrent.CompletableFuture.supplyAsync(() ->
                                        instrumented.call("{\"path\": \"pom.xml\"}"));

                        long deadline = System.currentTimeMillis() + 5000;
                        AgentEvent permEvent = null;
                        while (System.currentTimeMillis() < deadline) {
                                permEvent = events.stream()
                                                .filter(e -> e.type() == AgentEventType.PERMISSION_REQUIRED)
                                                .findFirst()
                                                .orElse(null);
                                if (permEvent != null) break;
                                Thread.sleep(50);
                        }
                        assertThat(permEvent).isNotNull();

                        mockMvc.perform(post("/api/sessions/" + sessionId + "/permissions/" + permEvent.toolCallId())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(new com.openworker.agent.models.publics.PermissionDecisionRequest(false))))
                                        .andExpect(status().isOk());

                        String result = callFuture.get(5, java.util.concurrent.TimeUnit.SECONDS);
                        assertThat(result).contains("Permission Denied");

                        List<AgentEventType> eventTypes = events.stream().map(AgentEvent::type).toList();
                        assertThat(eventTypes).contains(AgentEventType.TOOL_CALL);
                        assertThat(eventTypes).contains(AgentEventType.PERMISSION_REQUIRED);
                        assertThat(eventTypes).doesNotContain(AgentEventType.TOOL_RESULT);
                }

                @Test
                @DisplayName("POST /api/sessions/{id}/permissions/{toolCallId} with unknown toolCallId returns 404")
                void testPermissionDecisionEndpointNotFound() throws Exception {
                        mockMvc.perform(post("/api/sessions/non-existent-session/permissions/non-existent-call")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(new com.openworker.agent.models.publics.PermissionDecisionRequest(true))))
                                        .andExpect(status().isNotFound());
                }

                /**
                 * Verifies the ReAct loop's safety bound: once the step counter exceeds
                 * max-steps the callback throws {@link StepLimitExceededException}, which
                 * propagates out of Spring AI's ToolCallingAdvisor to terminate the loop.
                 */
                @Test
                @DisplayName("max-steps limit is enforced: third call throws StepLimitExceededException and emits ERROR")
                void testMaxStepsLimitEnforced() {
                        List<AgentEvent> events = new ArrayList<>();
                        AtomicInteger stepCounter = new AtomicInteger(0);

                        // maxSteps = 2; the third call must throw
                        InstrumentedToolCallback instrumented = new InstrumentedToolCallback(
                                        fileReadTool,
                                        permissionEngine,
                                        AgentMode.FULL,
                                        events::add,
                                        stepCounter,
                                        2, // maxSteps = 2
                                        new com.fasterxml.jackson.databind.ObjectMapper(),
                                        null);

                        String args = "{\"path\": \"pom.xml\", \"startLine\": 1, \"endLine\": 1}";
                        instrumented.call(args); // step 1 → OK
                        instrumented.call(args); // step 2 → OK

                        // Step 3 must throw StepLimitExceededException to kill the Spring AI loop
                        org.assertj.core.api.Assertions.assertThatThrownBy(() -> instrumented.call(args))
                                        .isInstanceOf(InstrumentedToolCallback.StepLimitExceededException.class)
                                        .hasMessageContaining("Maximum step limit");

                        // Step counter must reflect all three attempts
                        assertThat(stepCounter.get()).isEqualTo(3);

                        // An ERROR event must have been emitted on the blocked step
                        long errorCount = events.stream()
                                        .filter(e -> e.type() == AgentEventType.ERROR)
                                        .count();
                        assertThat(errorCount).isGreaterThanOrEqualTo(1);

                        // The blocked call must NOT produce a TOOL_RESULT
                        // (count TOOL_RESULT events — should be exactly 2, not 3)
                        long resultCount = events.stream()
                                        .filter(e -> e.type() == AgentEventType.TOOL_RESULT)
                                        .count();
                        assertThat(resultCount).isEqualTo(2);
                }
        }

        @Nested
        @DisplayName("7. Multi-Model Provider & Runtime Switching Tests")
        class ModelProviderTests {

                @Test
                @DisplayName("All four providers are registered in the registry")
                void testAllFourProvidersAreRegistered() {
                        assertThat(modelProviderRegistry.getAllProviders()).hasSize(4);
                        assertThat(modelProviderRegistry.getProvider("openai")).isPresent();
                        assertThat(modelProviderRegistry.getProvider("ollama")).isPresent();
                        assertThat(modelProviderRegistry.getProvider("anthropic")).isPresent();
                        assertThat(modelProviderRegistry.getProvider("google-genai")).isPresent();
                }

                @Test
                @DisplayName("Provider availability reflects configuration")
                void testProviderAvailabilityReflectsConfiguration() {
                        // anthropic has no real API key in test env
                        assertThat(modelProviderRegistry.getProvider("anthropic").get().isAvailable()).isFalse();
                        // google-genai, openai, and ollama have credentials or endpoints configured
                        assertThat(modelProviderRegistry.getProvider("google-genai").get().isAvailable()).isTrue();
                        assertThat(modelProviderRegistry.getProvider("openai").get().isAvailable()).isTrue();
                        assertThat(modelProviderRegistry.getProvider("ollama").get().isAvailable()).isTrue();
                }

                @Test
                @DisplayName("GET /api/models/providers returns all 4 providers")
                void testGetProvidersViaApi() throws Exception {
                        mockMvc.perform(get("/api/models/providers"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$").isArray())
                                        .andExpect(jsonPath("$.length()").value(4))
                                        .andExpect(jsonPath("$[?(@.id == 'openai')]").exists())
                                        .andExpect(jsonPath("$[?(@.id == 'ollama')]").exists())
                                        .andExpect(jsonPath("$[?(@.id == 'anthropic')]").exists())
                                        .andExpect(jsonPath("$[?(@.id == 'google-genai')]").exists());
                }

                @Test
                @DisplayName("GET /api/models/active returns current active provider and model")
                void testGetActiveModelViaApi() throws Exception {
                        mockMvc.perform(get("/api/models/active"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.provider").isNotEmpty())
                                        .andExpect(jsonPath("$.model").isNotEmpty());
                }

                @Test
                @DisplayName("POST /api/models/active switches the active provider/model")
                void testSetActiveModelViaApi() throws Exception {
                        com.openworker.agent.models.publics.SetActiveModelRequest req =
                                        new com.openworker.agent.models.publics.SetActiveModelRequest("openai", "gpt-4o-mini");
                        mockMvc.perform(post("/api/models/active")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.provider").value("openai"))
                                        .andExpect(jsonPath("$.model").value("gpt-4o-mini"));
                }

                @Test
                @DisplayName("Create session with explicit provider and model persists them")
                void testCreateSessionWithProviderAndModel() throws Exception {
                        CreateSessionRequest req = new CreateSessionRequest(
                                        "Provider Test Session", AgentMode.DISCUSS, "openai", "gpt-4o-mini");
                        String body = mockMvc.perform(post("/api/sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                                        .andExpect(status().isCreated())
                                        .andExpect(jsonPath("$.modelProvider").value("openai"))
                                        .andExpect(jsonPath("$.modelName").value("gpt-4o-mini"))
                                        .andReturn().getResponse().getContentAsString();

                        SessionSummaryResponse session = objectMapper.readValue(body, SessionSummaryResponse.class);
                        // cleanup
                        mockMvc.perform(delete("/api/sessions/" + session.id()))
                                        .andExpect(status().isNoContent());
                }

                @Test
                @DisplayName("PATCH /api/sessions/{id}/model switches session model")
                void testUpdateSessionModelViaApi() throws Exception {
                        // Create a plain session
                        CreateSessionRequest createReq = new CreateSessionRequest("Model Switch Test", AgentMode.DISCUSS);
                        String createBody = mockMvc.perform(post("/api/sessions")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(createReq)))
                                        .andExpect(status().isCreated())
                                        .andReturn().getResponse().getContentAsString();
                        SessionSummaryResponse session = objectMapper.readValue(createBody, SessionSummaryResponse.class);
                        String sessionId = session.id();

                        // Switch model
                        com.openworker.agent.models.publics.UpdateSessionModelRequest updateReq =
                                        new com.openworker.agent.models.publics.UpdateSessionModelRequest("openai", "gpt-4o");
                        mockMvc.perform(patch("/api/sessions/" + sessionId + "/model")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(updateReq)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.modelProvider").value("openai"))
                                        .andExpect(jsonPath("$.modelName").value("gpt-4o"));

                        // Cleanup
                        mockMvc.perform(delete("/api/sessions/" + sessionId))
                                        .andExpect(status().isNoContent());
                }

                @Test
                @DisplayName("POST /api/models/providers/{id}/config updates runtime credentials")
                void testUpdateProviderConfigViaApi() throws Exception {
                        com.openworker.agent.models.publics.UpdateProviderConfigRequest req =
                                        new com.openworker.agent.models.publics.UpdateProviderConfigRequest(
                                                        "test-api-key-12345", null, null);
                        mockMvc.perform(post("/api/models/providers/anthropic/config")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(req)))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.id").value("anthropic"))
                                        .andExpect(jsonPath("$.available").value(true));

                        // Reset so other tests aren't affected
                        com.openworker.agent.models.publics.UpdateProviderConfigRequest reset =
                                        new com.openworker.agent.models.publics.UpdateProviderConfigRequest("", null, null);
                        mockMvc.perform(post("/api/models/providers/anthropic/config")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(reset)));
                }

                @Test
                @DisplayName("ChatModelRouter setActiveDefault is reflected in GET /api/models/active")
                void testRouterSetActiveDefaultReflectedInApi() throws Exception {
                        chatModelRouter.setActiveDefault("ollama", "llama3.2");
                        mockMvc.perform(get("/api/models/active"))
                                        .andExpect(status().isOk())
                                        .andExpect(jsonPath("$.provider").value("ollama"))
                                        .andExpect(jsonPath("$.model").value("llama3.2"));

                        // restore default
                        chatModelRouter.setActiveDefault("google-genai", "gemini-flash-latest");
                }

                @Test
                @DisplayName("OpenAiModelProvider builds ChatModel without observationRegistry NPE")
                void testOpenAiModelProviderBuildsChatModel() {
                        var provider = modelProviderRegistry.getProvider("openai").orElseThrow();
                        org.springframework.ai.chat.model.ChatModel chatModel = provider.getChatModel("google/gemma-4-e2b");
                        assertThat(chatModel).isNotNull();
                }

                @Test
                @DisplayName("ChatModelRouter resolves ChatClient for OpenAI provider")
                void testChatModelRouterResolvesChatClient() {
                        org.springframework.ai.chat.client.ChatClient client = chatModelRouter.resolveChatClient(
                                        "openai", "google/gemma-4-e2b", null, null, "You are a helpful assistant");
                        assertThat(client).isNotNull();
                }

                @Test
                @DisplayName("GoogleGenAiModelProvider builds ChatModel without errors")
                void testGoogleGenAiModelProviderBuildsChatModel() {
                        var provider = modelProviderRegistry.getProvider("google-genai").orElseThrow();
                        org.springframework.ai.chat.model.ChatModel chatModel = provider.getChatModel("gemini-flash-latest");
                        assertThat(chatModel).isNotNull();
                }

                @Test
                @DisplayName("ChatModelRouter resolves ChatClient for Google GenAI provider")
                void testChatModelRouterResolvesGoogleGenAiChatClient() {
                        org.springframework.ai.chat.client.ChatClient client = chatModelRouter.resolveChatClient(
                                        "google-genai", "gemini-flash-latest", null, null, "You are a helpful assistant");
                        assertThat(client).isNotNull();
                }
        }
}
