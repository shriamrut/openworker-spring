package com.openworker.agent.langgraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphDefinition;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.StateGraph;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openworker.agent.interfaces.AgentTool;
import com.openworker.agent.interfaces.PermissionEngine;
import com.openworker.agent.interfaces.TurnEngine;
import com.openworker.agent.models.internals.db.SessionEntity;
import com.openworker.agent.models.internals.services.AgentEvent;
import com.openworker.agent.models.internals.services.AgentEventType;
import com.openworker.agent.models.internals.services.AgentMode;
import com.openworker.agent.models.internals.services.PermissionDecision;
import com.openworker.agent.services.ChatModelRouter;
import com.openworker.agent.services.ConversationMemoryService;
import com.openworker.agent.services.ToolApprovalService;
import com.openworker.agent.tools.ToolRegistry;

import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * High-performance LangGraph-powered ReAct execution engine for agent turns.
 * <p>
 * Implements an explicit stateful graph:
 * [START] -> [agent] (Reason) -> [shouldContinue] -?-> [tools] (Act & Observe) -> [agent]
 *                                                 \-> [END]
 * <p>
 * Key enhancements over traditional synchronous tool loops:
 * <ul>
 *   <li>Concurrent tool execution when LLM outputs multiple tool calls.</li>
 *   <li>Deterministic ReAct cycle with fine-grained state inspection.</li>
 *   <li>Safe self-correction on tool failure (errors fed back as observations).</li>
 *   <li>Configurable fallback to default TurnEngineImpl via {@code openworker.agent.engine.type}.</li>
 * </ul>
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "openworker.agent.engine.type", havingValue = "langgraph", matchIfMissing = true)
public class LangGraphTurnEngineImpl implements TurnEngine {

    private final ChatModelRouter chatModelRouter;
    private final ToolRegistry toolRegistry;
    private final ConversationMemoryService convMemoryService;
    private final PermissionEngine permissionEngine;
    private final ToolApprovalService toolApprovalService;
    private final ObjectMapper objectMapper;
    private final int maxSteps;
    private final String defaultSystemPrompt;
    private final ExecutorService toolExecutor;

    @Autowired
    public LangGraphTurnEngineImpl(
            ChatModelRouter chatModelRouter,
            @Value("${openworker.agent.engine.default.prompt}") String defaultSystemPrompt,
            ToolRegistry toolRegistry,
            ConversationMemoryService convMemoryService,
            PermissionEngine permissionEngine,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            ToolApprovalService toolApprovalService,
            @Value("${openworker.agent.engine.max-steps:15}") int maxSteps) {
        this.chatModelRouter = chatModelRouter;
        this.defaultSystemPrompt = defaultSystemPrompt;
        this.toolRegistry = toolRegistry;
        this.convMemoryService = convMemoryService;
        this.permissionEngine = permissionEngine;
        this.toolApprovalService = toolApprovalService;
        this.objectMapper = (objectMapperProvider != null && objectMapperProvider.getIfAvailable() != null)
                ? objectMapperProvider.getIfAvailable()
                : new ObjectMapper();
        this.maxSteps = maxSteps > 0 ? maxSteps : 15;
        this.toolExecutor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public LangGraphTurnEngineImpl(
            ChatModelRouter chatModelRouter,
            String defaultSystemPrompt,
            ToolRegistry toolRegistry,
            ConversationMemoryService convMemoryService,
            PermissionEngine permissionEngine,
            ObjectProvider<ObjectMapper> objectMapperProvider,
            int maxSteps) {
        this(chatModelRouter, defaultSystemPrompt, toolRegistry, convMemoryService, permissionEngine, objectMapperProvider, null, maxSteps);
    }

    @Override
    public void executeTurn(String sessionId,
                            String userPrompt,
                            Consumer<AgentEvent> eventConsumer) {
        executeTurn(sessionId, userPrompt, null, null, eventConsumer);
    }

    @Override
    public void executeTurn(String sessionId,
                            String userPrompt,
                            String providerOverride,
                            String modelOverride,
                            Consumer<AgentEvent> eventConsumer) {
        log.info("Starting LangGraph ReAct turn for session: {}, providerOverride={}, modelOverride={}",
                sessionId, providerOverride, modelOverride);
        eventConsumer.accept(new AgentEvent(AgentEventType.STARTED,
                "Turn started for session " + sessionId, null, null));

        try {
            // 1. Resolve target ChatModel
            var sessionOpt = convMemoryService.getSession(sessionId);
            String sessionProvider = sessionOpt.map(SessionEntity::getModelProvider).orElse(null);
            String sessionModel = sessionOpt.map(SessionEntity::getModelName).orElse(null);

            ChatModel chatModel;
            try {
                chatModel = chatModelRouter.resolveChatModel(
                        sessionProvider, sessionModel, providerOverride, modelOverride);
            } catch (Exception ex) {
                log.error("Failed to resolve chat model for session {}: {}", sessionId, ex.getMessage());
                eventConsumer.accept(new AgentEvent(AgentEventType.ERROR, ex.getMessage(), null, null));
                return;
            }

            // 2. Persist user prompt to persistent memory
            convMemoryService.saveMessage(sessionId, MessageType.USER.name(), userPrompt);
            List<Message> initialMessages = new ArrayList<>(
                    convMemoryService.getConversationHistory(sessionId));

            // 3. Resolve session mode (DISCUSS, PLAN, or FULL)
            AgentMode sessionMode = convMemoryService.getSessionMode(sessionId);
            log.debug("Executing LangGraph ReAct turn in session mode: {}", sessionMode);

            // 4. Catalog tools
            List<AgentTool> tools = toolRegistry.getAllTools().stream()
                    .filter(Objects::nonNull)
                    .toList();
            Map<String, AgentTool> toolsByName = tools.stream()
                    .collect(Collectors.toMap(AgentTool::getName, Function.identity(), (a, b) -> a));

            List<ToolCallback> toolCallbacks = tools.stream()
                    .map(AgentTool::toToolCallback)
                    .filter(Objects::nonNull)
                    .toList();

            ChatOptions chatOptions = buildOptions(chatModel, toolCallbacks);

            // 5. Construct LangGraph ReAct StateGraph
            StateGraph<AgentGraphState> workflow = new StateGraph<>(AgentGraphState.SCHEMA, AgentGraphState::new)
                    .addNode("agent", node_async(state -> callModel(state, chatModel, chatOptions, eventConsumer)))
                    .addNode("tools", node_async(state -> executeTools(state, sessionId, sessionMode, toolsByName, eventConsumer)))
                    .addEdge(GraphDefinition.START, "agent")
                    .addConditionalEdges("agent",
                            edge_async(state -> shouldContinue(state, sessionId, eventConsumer)),
                            Map.of("tools", "tools", GraphDefinition.END, GraphDefinition.END))
                    .addEdge("tools", "agent");

            CompiledGraph<AgentGraphState> compiledApp = workflow.compile();

            RunnableConfig config = RunnableConfig.builder()
                    .threadId(sessionId)
                    .recursionLimit(maxSteps)
                    .disableCloneState()
                    .build();

            // 6. Execute Graph
            compiledApp.invoke(
                    GraphInput.args(Map.of(
                            MessagesState.MESSAGES_STATE, initialMessages,
                            AgentGraphState.STEP_COUNT_KEY, 0
                    )),
                    config
            );

            log.info("LangGraph ReAct turn completed successfully for session: {}", sessionId);
            eventConsumer.accept(new AgentEvent(AgentEventType.COMPLETED,
                    "Turn completed for session " + sessionId, null, null));

        } catch (Exception ex) {
            log.error("Error occurred during LangGraph turn execution for session {}", sessionId, ex);
            eventConsumer.accept(new AgentEvent(AgentEventType.ERROR, ex.getMessage(), null, null));
        }
    }

    /**
     * Agent Node: invokes the ChatModel with current messages and tool options.
     */
    private Map<String, Object> callModel(
            AgentGraphState state,
            ChatModel chatModel,
            ChatOptions chatOptions,
            Consumer<AgentEvent> eventConsumer) {

        List<Message> currentMessages = new ArrayList<>(state.messages());

        // Prepend system prompt if missing
        if (StringUtils.isNotBlank(defaultSystemPrompt)) {
            boolean hasSystem = !currentMessages.isEmpty() && currentMessages.get(0).getMessageType() == MessageType.SYSTEM;
            if (!hasSystem) {
                currentMessages.add(0, new SystemMessage(defaultSystemPrompt));
            }
        }

        Prompt prompt = new Prompt(currentMessages, chatOptions);
        ChatResponse response = chatModel.call(prompt);

        AssistantMessage assistantMessage = (response != null && response.getResult() != null)
                ? response.getResult().getOutput()
                : new AssistantMessage("");

        String text = assistantMessage.getText();
        if (StringUtils.isNotBlank(text)) {
            eventConsumer.accept(new AgentEvent(AgentEventType.NARRATION, text, null, null));
        }

        return Map.of(MessagesState.MESSAGES_STATE, List.of(assistantMessage));
    }

    /**
     * Preserves provider-specific ChatOptions (OpenAiChatOptions, OllamaChatOptions, etc.)
     * by mutating the model's default options so each provider's internal downcast succeeds.
     */
    private ChatOptions buildOptions(ChatModel chatModel, List<ToolCallback> toolCallbacks) {
        ChatOptions defaultOptions = chatModel.getDefaultOptions();
        if (defaultOptions instanceof ToolCallingChatOptions toolCalling) {
            return toolCalling.mutate()
                    .toolCallbacks(toolCallbacks)
                    .build();
        }
        return ToolCallingChatOptions.builder()
                .toolCallbacks(toolCallbacks)
                .build();
    }

    /**
     * Conditional Edge: determines whether to execute tools or terminate the turn.
     */
    private String shouldContinue(
            AgentGraphState state,
            String sessionId,
            Consumer<AgentEvent> eventConsumer) {

        Optional<Message> lastMessageOpt = state.lastMessage();
        if (lastMessageOpt.isEmpty()) {
            return GraphDefinition.END;
        }

        Message lastMessage = lastMessageOpt.get();
        if (lastMessage instanceof AssistantMessage assistantMessage && assistantMessage.hasToolCalls()) {
            if (state.getStepCount() >= maxSteps) {
                String limitMsg = "Execution stopped: Maximum step limit of " + maxSteps + " exceeded.";
                log.warn(limitMsg);
                eventConsumer.accept(new AgentEvent(AgentEventType.ERROR, limitMsg, null, null));
                return GraphDefinition.END;
            }
            return "tools";
        }

        // Final assistant response: persist to conversation memory
        if (lastMessage instanceof AssistantMessage assistantMessage && StringUtils.isNotBlank(assistantMessage.getText())) {
            convMemoryService.saveMessage(sessionId, MessageType.ASSISTANT.name(), assistantMessage.getText());
        }

        return GraphDefinition.END;
    }

    /**
     * Tool Node: executes requested tools (with parallel dispatch) and updates state.
     */
    private Map<String, Object> executeTools(
            AgentGraphState state,
            String sessionId,
            AgentMode sessionMode,
            Map<String, AgentTool> toolsByName,
            Consumer<AgentEvent> eventConsumer) {

        Message lastMessage = state.lastMessage().orElseThrow();
        if (!(lastMessage instanceof AssistantMessage assistantMessage)) {
            return Map.of();
        }

        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        if (toolCalls == null || toolCalls.isEmpty()) {
            return Map.of();
        }

        // Execute tool calls concurrently via virtual threads
        List<CompletableFuture<ToolResponseMessage.ToolResponse>> futures = toolCalls.stream()
                .map(toolCall -> CompletableFuture.supplyAsync(() -> executeSingleTool(
                        toolCall, sessionId, sessionMode, toolsByName, eventConsumer), toolExecutor))
                .toList();

        List<ToolResponseMessage.ToolResponse> responses = futures.stream()
                .map(CompletableFuture::join)
                .toList();

        ToolResponseMessage toolResponseMessage = ToolResponseMessage.builder()
                .responses(responses)
                .build();

        int updatedStepCount = state.getStepCount() + toolCalls.size();

        return Map.of(
                MessagesState.MESSAGES_STATE, List.of(toolResponseMessage),
                AgentGraphState.STEP_COUNT_KEY, updatedStepCount
        );
    }

    /**
     * Executes an individual tool call, performing permission evaluation, event streaming,
     * error boundary handling, and persistent memory recording.
     */
    private ToolResponseMessage.ToolResponse executeSingleTool(
            AssistantMessage.ToolCall toolCall,
            String sessionId,
            AgentMode sessionMode,
            Map<String, AgentTool> toolsByName,
            Consumer<AgentEvent> eventConsumer) {

        String toolName = toolCall.name();
        String toolCallId = toolCall.id();
        String input = toolCall.arguments();

        log.debug("Executing tool '{}' (callId={}) with input: {}", toolName, toolCallId, input);

        // 1. Emit TOOL_CALL event
        eventConsumer.accept(new AgentEvent(AgentEventType.TOOL_CALL, input != null ? input : "{}", toolName, toolCallId));

        AgentTool tool = toolsByName.get(toolName);
        if (tool == null) {
            String notFound = "Error: Tool '" + toolName + "' not found in registry.";
            log.warn(notFound);
            eventConsumer.accept(new AgentEvent(AgentEventType.ERROR, notFound, toolName, toolCallId));
            return new ToolResponseMessage.ToolResponse(toolCallId, toolName, notFound);
        }

        // 2. Parse arguments and evaluate permission
        Map<String, Object> arguments = parseArguments(input);
        PermissionDecision decision = permissionEngine.evaluate(sessionMode, toolName, arguments);
        if (decision.needsUserApproval()) {
            if (toolApprovalService != null) {
                log.info("Tool '{}' (callId={}) requires user approval in mode {}", toolName, toolCallId, sessionMode);
                boolean approved = toolApprovalService.requestApproval(
                        sessionId,
                        toolCallId,
                        toolName,
                        input,
                        decision.reason(),
                        eventConsumer,
                        180
                );
                if (!approved) {
                    String deniedMsg = "Permission Denied: User rejected execution of tool " + toolName;
                    log.warn("User rejected tool '{}' (callId={})", toolName, toolCallId);
                    convMemoryService.saveMessage(sessionId, "TOOL_" + toolName, "Input: " + input + "\nOutput: " + deniedMsg);
                    return new ToolResponseMessage.ToolResponse(toolCallId, toolName, deniedMsg);
                }
            } else {
                String deniedMsg = "Permission Denied: " + decision.reason();
                log.warn("No approval service configured, blocking tool '{}' in mode {}: {}", toolName, sessionMode, decision.reason());
                eventConsumer.accept(new AgentEvent(AgentEventType.PERMISSION_REQUIRED, deniedMsg, toolName, toolCallId));
                convMemoryService.saveMessage(sessionId, "TOOL_" + toolName, "Input: " + input + "\nOutput: " + deniedMsg);
                return new ToolResponseMessage.ToolResponse(toolCallId, toolName, deniedMsg);
            }
        } else if (!decision.allowed()) {
            String deniedMsg = "Permission Denied: " + decision.reason();
            log.warn("Blocked tool '{}' in mode {}: {}", toolName, sessionMode, decision.reason());
            eventConsumer.accept(new AgentEvent(AgentEventType.PERMISSION_REQUIRED, deniedMsg, toolName, toolCallId));
            convMemoryService.saveMessage(sessionId, "TOOL_" + toolName, "Input: " + input + "\nOutput: " + deniedMsg);
            return new ToolResponseMessage.ToolResponse(toolCallId, toolName, deniedMsg);
        }

        // 3. Tool execution with fault tolerance
        String output;
        try {
            ToolCallback callback = tool.toToolCallback();
            output = callback.call(input);
            if (output == null) {
                output = "Tool executed successfully with no output.";
            }
        } catch (Exception ex) {
            log.error("Tool execution failed for '{}': {}", toolName, ex.getMessage(), ex);
            output = "Tool execution failed: " + ex.getMessage();
            eventConsumer.accept(new AgentEvent(AgentEventType.ERROR, output, toolName, toolCallId));
            return new ToolResponseMessage.ToolResponse(toolCallId, toolName, output);
        }

        // 4. Emit TOOL_RESULT event
        eventConsumer.accept(new AgentEvent(AgentEventType.TOOL_RESULT, output, toolName, toolCallId));

        // 5. Persist step to conversation memory
        convMemoryService.saveMessage(sessionId, "TOOL_" + toolName, "Input: " + input + "\nOutput: " + output);

        return new ToolResponseMessage.ToolResponse(toolCallId, toolName, output);
    }

    private Map<String, Object> parseArguments(String input) {
        if (input == null || input.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(input, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.debug("Failed to parse tool input JSON '{}': {}", input, e.getMessage());
            return Collections.emptyMap();
        }
    }
}
