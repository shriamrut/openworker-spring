package com.openworker.agent.impl;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openworker.agent.interfaces.PermissionEngine;
import com.openworker.agent.interfaces.TurnEngine;
import com.openworker.agent.models.internals.services.AgentEvent;
import com.openworker.agent.models.internals.services.AgentEventType;
import com.openworker.agent.models.internals.services.AgentMode;
import com.openworker.agent.services.ChatModelRouter;
import com.openworker.agent.services.ConversationMemoryService;
import com.openworker.agent.services.ToolApprovalService;
import com.openworker.agent.tools.InstrumentedToolCallback;
import com.openworker.agent.tools.ToolRegistry;

import io.micrometer.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

/**
 * Execution engine for agent turns supporting multi-step reasoning,
 * tool chaining, real-time SSE event emission, runtime permission evaluation,
 * and persistent memory tracking.
 */
@Slf4j
@Service
@ConditionalOnProperty(name = "openworker.agent.engine.type", havingValue = "default")
public class TurnEngineImpl implements TurnEngine {

    private final ChatModelRouter chatModelRouter;
    private final ToolRegistry toolRegistry;
    private final ConversationMemoryService convMemoryService;
    private final PermissionEngine permissionEngine;
    private final ToolApprovalService toolApprovalService;
    private final ObjectMapper objectMapper;
    private final int maxSteps;
    private final String defaultSystemPrompt;

    @Autowired
    public TurnEngineImpl(
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
    }

    public TurnEngineImpl(
            ChatModelRouter chatModelRouter,
            String defaultSystemPrompt,
            ToolRegistry toolRegistry,
            ConversationMemoryService convMemoryService,
            PermissionEngine permissionEngine) {
        this(chatModelRouter, defaultSystemPrompt, toolRegistry, convMemoryService, permissionEngine, null, null, 15);
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
        log.debug("Starting multi-step turn for session with Id: {}, providerOverride={}, modelOverride={}",
                sessionId, providerOverride, modelOverride);
        eventConsumer.accept(new AgentEvent(AgentEventType.STARTED,
                "Turn started for session " + sessionId, null, null));

        try {
            // 1. Resolve ChatClient for this turn
            var sessionOpt = convMemoryService.getSession(sessionId);
            String sessionProvider = sessionOpt.map(com.openworker.agent.models.internals.db.SessionEntity::getModelProvider).orElse(null);
            String sessionModel = sessionOpt.map(com.openworker.agent.models.internals.db.SessionEntity::getModelName).orElse(null);

            ChatClient chatClient;
            try {
                chatClient = chatModelRouter.resolveChatClient(
                        sessionProvider, sessionModel, providerOverride, modelOverride, defaultSystemPrompt);
            } catch (Exception ex) {
                log.error("Failed to resolve chat model for session {}: {}", sessionId, ex.getMessage());
                eventConsumer.accept(new AgentEvent(AgentEventType.ERROR, ex.getMessage(), null, null));
                return;
            }

            // 2. Record user prompt in persistent memory
            convMemoryService.saveMessage(sessionId, MessageType.USER.name(), userPrompt);
            List<Message> conversationMessages = new ArrayList<>(
                    convMemoryService.getConversationHistory(sessionId));

            // 3. Fetch session mode (DISCUSS, PLAN, or FULL)
            AgentMode sessionMode = convMemoryService.getSessionMode(sessionId);
            log.debug("Executing turn in session mode: {}", sessionMode);

            // 4. Prepare step counter and instrument tool callbacks
            AtomicInteger stepCounter = new AtomicInteger(0);
            List<ToolCallback> instrumentedTools = toolRegistry.getAllTools().stream()
                    .filter(Objects::nonNull)
                    .map(tool -> (ToolCallback) new InstrumentedToolCallback(
                            tool,
                            permissionEngine,
                            sessionMode,
                            eventConsumer,
                            stepCounter,
                            maxSteps,
                            objectMapper,
                            (input, output) -> {
                                // Persist each intermediate tool step to memory
                                convMemoryService.saveMessage(
                                        sessionId,
                                        "TOOL_" + tool.getName(),
                                        "Input: " + input + "\nOutput: " + output
                                );
                            },
                            toolApprovalService,
                            sessionId))
                    .toList();

            log.debug("Instrumented {} tools for multi-step execution (maxSteps={})",
                    instrumentedTools.size(), maxSteps);

            // 5. Invoke LLM with instrumented tools
            ChatResponse response = chatClient.prompt()
                    .messages(conversationMessages)
                    .tools(instrumentedTools.toArray(Object[]::new))
                    .call()
                    .chatResponse();

            String content = response != null && response.getResult() != null
                    ? response.getResult().getOutput().getText()
                    : "";

            // 6. Emit final narration and record in memory
            if (StringUtils.isNotBlank(content)) {
                convMemoryService.saveMessage(sessionId, MessageType.ASSISTANT.name(), content);
                eventConsumer.accept(new AgentEvent(AgentEventType.NARRATION, content, null, null));
            }

            log.debug("Multi-step turn finished successfully after {} tool steps", stepCounter.get());

            // 7. Mark as completed
            eventConsumer.accept(new AgentEvent(AgentEventType.COMPLETED,
                    "Turn completed for session " + sessionId, null, null));

        } catch (Exception ex) {
            log.error("Error occurred during turn execution for session {}", sessionId, ex);
            eventConsumer.accept(new AgentEvent(AgentEventType.ERROR, ex.getMessage(), null, null));
        }
    }
}