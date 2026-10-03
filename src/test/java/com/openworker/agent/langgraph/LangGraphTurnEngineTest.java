package com.openworker.agent.langgraph;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openworker.agent.interfaces.AgentTool;
import com.openworker.agent.interfaces.PermissionEngine;
import com.openworker.agent.models.internals.services.AgentEvent;
import com.openworker.agent.models.internals.services.AgentEventType;
import com.openworker.agent.models.internals.services.AgentMode;
import com.openworker.agent.models.internals.services.PermissionDecision;
import com.openworker.agent.models.internals.services.ToolRiskClass;
import com.openworker.agent.services.ChatModelRouter;
import com.openworker.agent.services.ConversationMemoryService;
import com.openworker.agent.services.ToolApprovalService;
import com.openworker.agent.tools.ToolRegistry;

@ExtendWith(MockitoExtension.class)
class LangGraphTurnEngineTest {

    @Mock
    private ChatModelRouter chatModelRouter;

    @Mock
    private ToolRegistry toolRegistry;

    @Mock
    private ConversationMemoryService convMemoryService;

    @Mock
    private PermissionEngine permissionEngine;

    @Mock
    private ToolApprovalService toolApprovalService;

    @Mock
    private ChatModel chatModel;

    private LangGraphTurnEngineImpl turnEngine;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ObjectProvider<ObjectMapper> objectProvider = mock(ObjectProvider.class);
        when(objectProvider.getIfAvailable()).thenReturn(objectMapper);

        turnEngine = new LangGraphTurnEngineImpl(
                chatModelRouter,
                "Test system prompt",
                toolRegistry,
                convMemoryService,
                permissionEngine,
                objectProvider,
                toolApprovalService,
                15
        );
    }

    @Test
    @DisplayName("Direct Answer: Model responds without tools -> Emits NARRATION and COMPLETED")
    void testDirectAnswer() {
        String sessionId = "test-session-1";
        String userPrompt = "Hello!";

        when(convMemoryService.getSession(sessionId)).thenReturn(Optional.empty());
        when(chatModelRouter.resolveChatModel(any(), any(), any(), any())).thenReturn(chatModel);
        when(convMemoryService.getConversationHistory(sessionId)).thenReturn(List.of());
        when(convMemoryService.getSessionMode(sessionId)).thenReturn(AgentMode.DISCUSS);
        when(toolRegistry.getAllTools()).thenReturn(List.of());

        AssistantMessage directResponseMsg = new AssistantMessage("Hello there, how can I help you?");
        Generation generation = new Generation(directResponseMsg);
        ChatResponse chatResponse = new ChatResponse(List.of(generation));
        when(chatModel.call(any(Prompt.class))).thenReturn(chatResponse);

        List<AgentEvent> emittedEvents = new ArrayList<>();
        turnEngine.executeTurn(sessionId, userPrompt, emittedEvents::add);

        assertFalse(emittedEvents.isEmpty());
        assertEquals(AgentEventType.STARTED, emittedEvents.get(0).type());
        assertEquals(AgentEventType.NARRATION, emittedEvents.get(1).type());
        assertEquals("Hello there, how can I help you?", emittedEvents.get(1).content());
        assertEquals(AgentEventType.COMPLETED, emittedEvents.get(emittedEvents.size() - 1).type());

        verify(convMemoryService).saveMessage(sessionId, MessageType.USER.name(), userPrompt);
        verify(convMemoryService).saveMessage(sessionId, MessageType.ASSISTANT.name(), "Hello there, how can I help you?");
    }

    @Test
    @DisplayName("Multi-Step ReAct: Model calls tool -> Tool executes -> Model produces final answer")
    void testReActToolExecutionCycle() {
        String sessionId = "test-session-react";
        String userPrompt = "What is in file.txt?";

        when(convMemoryService.getSession(sessionId)).thenReturn(Optional.empty());
        when(chatModelRouter.resolveChatModel(any(), any(), any(), any())).thenReturn(chatModel);
        when(convMemoryService.getConversationHistory(sessionId)).thenReturn(List.of());
        when(convMemoryService.getSessionMode(sessionId)).thenReturn(AgentMode.FULL);

        // Mock Tool
        AgentTool mockTool = mock(AgentTool.class);
        ToolCallback mockCallback = mock(ToolCallback.class);
        when(mockTool.getName()).thenReturn("file_read");
        when(mockTool.toToolCallback()).thenReturn(mockCallback);
        when(mockCallback.call(anyString())).thenReturn("File contents: 42");
        when(toolRegistry.getAllTools()).thenReturn(List.of(mockTool));

        // Mock PermissionEngine: Allow execution
        when(permissionEngine.evaluate(eq(AgentMode.FULL), eq("file_read"), any()))
                .thenReturn(PermissionDecision.allow());

        // Step 1: LLM returns ToolCall
        ToolCall toolCall = new ToolCall("call_001", "function", "file_read", "{\"path\": \"file.txt\"}");
        AssistantMessage step1Assistant = AssistantMessage.builder()
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse step1Response = new ChatResponse(List.of(new Generation(step1Assistant)));

        // Step 2: LLM returns final answer
        AssistantMessage step2Assistant = new AssistantMessage("The file contains 42.");
        ChatResponse step2Response = new ChatResponse(List.of(new Generation(step2Assistant)));

        AtomicInteger callCount = new AtomicInteger(0);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (callCount.getAndIncrement() == 0) {
                return step1Response;
            } else {
                return step2Response;
            }
        });

        List<AgentEvent> emittedEvents = new ArrayList<>();
        turnEngine.executeTurn(sessionId, userPrompt, emittedEvents::add);

        // Verify emitted event sequence: STARTED -> TOOL_CALL -> TOOL_RESULT -> NARRATION -> COMPLETED
        List<AgentEventType> eventTypes = emittedEvents.stream().map(AgentEvent::type).toList();
        assertTrue(eventTypes.contains(AgentEventType.STARTED));
        assertTrue(eventTypes.contains(AgentEventType.TOOL_CALL));
        assertTrue(eventTypes.contains(AgentEventType.TOOL_RESULT));
        assertTrue(eventTypes.contains(AgentEventType.NARRATION));
        assertTrue(eventTypes.contains(AgentEventType.COMPLETED));

        verify(mockCallback).call("{\"path\": \"file.txt\"}");
        verify(convMemoryService).saveMessage(eq(sessionId), eq("TOOL_file_read"), anyString());
        verify(convMemoryService).saveMessage(sessionId, MessageType.ASSISTANT.name(), "The file contains 42.");
    }

    @Test
    @DisplayName("Permission Denied: Tool execution blocked -> Error returned to model as observation")
    void testPermissionDeniedObservation() {
        String sessionId = "test-session-denied";
        String userPrompt = "Delete everything";

        when(convMemoryService.getSession(sessionId)).thenReturn(Optional.empty());
        when(chatModelRouter.resolveChatModel(any(), any(), any(), any())).thenReturn(chatModel);
        when(convMemoryService.getConversationHistory(sessionId)).thenReturn(List.of());
        when(convMemoryService.getSessionMode(sessionId)).thenReturn(AgentMode.DISCUSS);

        AgentTool mockTool = mock(AgentTool.class);
        when(mockTool.getName()).thenReturn("shell_exec");
        when(toolRegistry.getAllTools()).thenReturn(List.of(mockTool));

        // Permission denied
        when(permissionEngine.evaluate(eq(AgentMode.DISCUSS), eq("shell_exec"), any()))
                .thenReturn(PermissionDecision.deny("Command execution disallowed in DISCUSS mode"));

        // Step 1: Model tries to call shell_exec
        ToolCall toolCall = new ToolCall("call_del", "function", "shell_exec", "{\"command\": \"rm -rf /\"}");
        AssistantMessage step1Assistant = AssistantMessage.builder()
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse step1Response = new ChatResponse(List.of(new Generation(step1Assistant)));

        // Step 2: Model acknowledges error observation
        AssistantMessage step2Assistant = new AssistantMessage("I cannot execute this command due to permission restrictions.");
        ChatResponse step2Response = new ChatResponse(List.of(new Generation(step2Assistant)));

        AtomicInteger callCount = new AtomicInteger(0);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (callCount.getAndIncrement() == 0) {
                return step1Response;
            } else {
                return step2Response;
            }
        });

        List<AgentEvent> emittedEvents = new ArrayList<>();
        turnEngine.executeTurn(sessionId, userPrompt, emittedEvents::add);

        List<AgentEventType> eventTypes = emittedEvents.stream().map(AgentEvent::type).toList();
        assertTrue(eventTypes.contains(AgentEventType.PERMISSION_REQUIRED));
        assertTrue(eventTypes.contains(AgentEventType.NARRATION));
        assertTrue(eventTypes.contains(AgentEventType.COMPLETED));

        verify(convMemoryService, atLeastOnce()).saveMessage(eq(sessionId), anyString(), anyString());
    }

    @Test
    @DisplayName("User Approval in DISCUSS mode: Tool requires approval -> user approves -> tool executes")
    void testUserApprovalInLangGraphTurnEngine() {
        String sessionId = "test-session-approval";
        String userPrompt = "Check file.txt";

        when(convMemoryService.getSession(sessionId)).thenReturn(Optional.empty());
        when(chatModelRouter.resolveChatModel(any(), any(), any(), any())).thenReturn(chatModel);
        when(convMemoryService.getConversationHistory(sessionId)).thenReturn(List.of());
        when(convMemoryService.getSessionMode(sessionId)).thenReturn(AgentMode.DISCUSS);

        AgentTool mockTool = mock(AgentTool.class);
        ToolCallback mockCallback = mock(ToolCallback.class);
        when(mockTool.getName()).thenReturn("file_read");
        when(mockTool.toToolCallback()).thenReturn(mockCallback);
        when(mockCallback.call(anyString())).thenReturn("File contents: 100");
        when(toolRegistry.getAllTools()).thenReturn(List.of(mockTool));

        when(permissionEngine.evaluate(eq(AgentMode.DISCUSS), eq("file_read"), any()))
                .thenReturn(PermissionDecision.askUser("Tool requires approval in DISCUSS mode"));

        when(toolApprovalService.requestApproval(eq(sessionId), eq("call_appr"), eq("file_read"), anyString(), anyString(), any(), eq(180L)))
                .thenReturn(true);

        ToolCall toolCall = new ToolCall("call_appr", "function", "file_read", "{\"path\": \"file.txt\"}");
        AssistantMessage step1Assistant = AssistantMessage.builder()
                .toolCalls(List.of(toolCall))
                .build();
        ChatResponse step1Response = new ChatResponse(List.of(new Generation(step1Assistant)));

        AssistantMessage step2Assistant = new AssistantMessage("The file contains 100.");
        ChatResponse step2Response = new ChatResponse(List.of(new Generation(step2Assistant)));

        AtomicInteger callCount = new AtomicInteger(0);
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            if (callCount.getAndIncrement() == 0) {
                return step1Response;
            } else {
                return step2Response;
            }
        });

        List<AgentEvent> emittedEvents = new ArrayList<>();
        turnEngine.executeTurn(sessionId, userPrompt, emittedEvents::add);

        List<AgentEventType> eventTypes = emittedEvents.stream().map(AgentEvent::type).toList();
        assertTrue(eventTypes.contains(AgentEventType.TOOL_CALL));
        assertTrue(eventTypes.contains(AgentEventType.TOOL_RESULT));
        assertTrue(eventTypes.contains(AgentEventType.COMPLETED));

        verify(mockCallback).call("{\"path\": \"file.txt\"}");
    }

    @Test
    @DisplayName("Provider Options Mutation: OpenAiChatOptions mutated with toolCallbacks succeeds without ClassCastException")
    void testOpenAiChatOptionsPreserved() {
        String sessionId = "test-session-openai-options";
        String userPrompt = "Hello OpenAI";

        when(convMemoryService.getSession(sessionId)).thenReturn(Optional.empty());
        when(chatModelRouter.resolveChatModel(any(), any(), any(), any())).thenReturn(chatModel);
        when(convMemoryService.getConversationHistory(sessionId)).thenReturn(List.of());
        when(convMemoryService.getSessionMode(sessionId)).thenReturn(AgentMode.DISCUSS);

        // Simulate OpenAiChatModel returning OpenAiChatOptions as default options
        org.springframework.ai.openai.OpenAiChatOptions openAiOptions = org.springframework.ai.openai.OpenAiChatOptions.builder()
                .model("google/gemma-4-e2b")
                .build();
        when(chatModel.getDefaultOptions()).thenReturn(openAiOptions);

        AssistantMessage directMsg = new AssistantMessage("Response from OpenAI");
        ChatResponse chatResponse = new ChatResponse(List.of(new Generation(directMsg)));

        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            // Verify that options passed to Prompt are indeed an instance of OpenAiChatOptions!
            assertTrue(prompt.getOptions() instanceof org.springframework.ai.openai.OpenAiChatOptions,
                    "Expected Prompt options to be instance of OpenAiChatOptions, but was: " + prompt.getOptions().getClass());
            return chatResponse;
        });

        List<AgentEvent> emittedEvents = new ArrayList<>();
        turnEngine.executeTurn(sessionId, userPrompt, emittedEvents::add);

        assertEquals(AgentEventType.COMPLETED, emittedEvents.get(emittedEvents.size() - 1).type());
    }
}

