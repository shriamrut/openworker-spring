package com.openworker.agent.tools;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openworker.agent.interfaces.AgentTool;
import com.openworker.agent.interfaces.PermissionEngine;
import com.openworker.agent.models.internals.services.AgentEvent;
import com.openworker.agent.models.internals.services.AgentEventType;
import com.openworker.agent.models.internals.services.AgentMode;
import com.openworker.agent.models.internals.services.PermissionDecision;

import lombok.extern.slf4j.Slf4j;

/**
 * Decorator for {@link ToolCallback} that provides real-time event streaming via SSE,
 * dynamic {@link PermissionEngine} enforcement per tool step, loop bounding (max-steps),
 * and step recording for multi-step agent reasoning.
 */
@Slf4j
public class InstrumentedToolCallback implements ToolCallback {

    /**
     * Thrown when the configured max-steps limit is exceeded.
     * This is a RuntimeException so it propagates out of Spring AI's
     * {@code ToolCallingAdvisor} loop and terminates the turn immediately.
     */
    public static class StepLimitExceededException extends RuntimeException {
        public StepLimitExceededException(String message) {
            super(message);
        }
    }

    private final AgentTool delegateTool;
    private final ToolCallback delegateCallback;
    private final PermissionEngine permissionEngine;
    private final AgentMode sessionMode;
    private final Consumer<AgentEvent> eventConsumer;
    private final AtomicInteger stepCounter;
    private final int maxSteps;
    private final ObjectMapper objectMapper;
    private final BiConsumer<String, String> stepRecorder;

    public InstrumentedToolCallback(
            AgentTool delegateTool,
            PermissionEngine permissionEngine,
            AgentMode sessionMode,
            Consumer<AgentEvent> eventConsumer,
            AtomicInteger stepCounter,
            int maxSteps,
            ObjectMapper objectMapper,
            BiConsumer<String, String> stepRecorder) {
        this.delegateTool = Objects.requireNonNull(delegateTool, "delegateTool must not be null");
        this.delegateCallback = Objects.requireNonNull(delegateTool.toToolCallback(), "delegateCallback must not be null");
        this.permissionEngine = Objects.requireNonNull(permissionEngine, "permissionEngine must not be null");
        this.sessionMode = sessionMode != null ? sessionMode : AgentMode.DISCUSS;
        this.eventConsumer = eventConsumer != null ? eventConsumer : event -> {};
        this.stepCounter = stepCounter != null ? stepCounter : new AtomicInteger(0);
        this.maxSteps = maxSteps > 0 ? maxSteps : 15;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
        this.stepRecorder = stepRecorder;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegateCallback.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegateCallback.getToolMetadata();
    }

    @Override
    public String call(String input) {
        String toolName = delegateTool.getName();
        String toolCallId = "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        log.debug("Step intercepted for tool '{}', input: {}", toolName, input);

        // 1. Emit real-time TOOL_CALL event to consumer (e.g. SSE stream)
        safeEmit(eventConsumer, new AgentEvent(AgentEventType.TOOL_CALL, input != null ? input : "{}", toolName, toolCallId));

        // 2. Check maximum reasoning steps limit — throw to stop Spring AI's loop
        int currentStep = stepCounter.incrementAndGet();
        if (currentStep > maxSteps) {
            String limitMsg = "Execution stopped: Maximum step limit of " + maxSteps + " exceeded.";
            log.warn(limitMsg);
            safeEmit(eventConsumer, new AgentEvent(AgentEventType.ERROR, limitMsg, toolName, toolCallId));
            throw new StepLimitExceededException(limitMsg);
        }

        // 3. Parse input arguments
        Map<String, Object> arguments = parseArguments(input);

        // 4. Enforce PermissionEngine
        PermissionDecision decision = permissionEngine.evaluate(sessionMode, toolName, arguments);
        if (!decision.allowed()) {
            String denialMsg = "Permission Denied: " + decision.reason();
            log.warn("Blocked tool '{}' in mode {}: {}", toolName, sessionMode, decision.reason());
            safeEmit(eventConsumer, new AgentEvent(AgentEventType.PERMISSION_REQUIRED, denialMsg, toolName, toolCallId));
            if (stepRecorder != null) {
                stepRecorder.accept(input, denialMsg);
            }
            return denialMsg;
        }

        // 5. Execute the tool
        String output;
        try {
            output = delegateCallback.call(input);
            if (output == null) {
                output = "";
            }
        } catch (Exception ex) {
            log.error("Error executing tool '{}': {}", toolName, ex.getMessage(), ex);
            output = "Tool execution error: " + ex.getMessage();
        }

        // 6. Emit real-time TOOL_RESULT event
        safeEmit(eventConsumer, new AgentEvent(AgentEventType.TOOL_RESULT, output, toolName, toolCallId));

        // 7. Record step into persistent history
        if (stepRecorder != null) {
            stepRecorder.accept(input, output);
        }

        return output;
    }

    /**
     * Safely emit an event, swallowing any exception if the SSE emitter is
     * already closed (e.g. due to HTTP timeout). This prevents a cascade of
     * IllegalStateException errors after the client disconnects.
     */
    private static void safeEmit(Consumer<AgentEvent> consumer, AgentEvent event) {
        try {
            consumer.accept(event);
        } catch (Exception e) {
            log.debug("SSE emitter closed, dropping event {}: {}", event.type(), e.getMessage());
        }
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
