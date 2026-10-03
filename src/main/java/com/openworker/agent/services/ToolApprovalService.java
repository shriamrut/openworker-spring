package com.openworker.agent.services;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import org.springframework.stereotype.Service;

import com.openworker.agent.models.internals.services.AgentEvent;
import com.openworker.agent.models.internals.services.AgentEventType;

import lombok.extern.slf4j.Slf4j;

@Service
@Slf4j
public class ToolApprovalService {

    public record ApprovalRequestDetails(
            String sessionId,
            String toolCallId,
            String toolName,
            String input,
            String reason,
            long createdAt
    ) {}

    private final Map<String, CompletableFuture<Boolean>> pendingApprovals = new ConcurrentHashMap<>();
    private final Map<String, ApprovalRequestDetails> pendingDetails = new ConcurrentHashMap<>();

    /**
     * Request approval from the user for a tool execution.
     * Emits a PERMISSION_REQUIRED event and blocks the calling virtual thread until resolved or timed out.
     */
    public boolean requestApproval(
            String sessionId,
            String toolCallId,
            String toolName,
            String input,
            String reason,
            Consumer<AgentEvent> eventConsumer,
            long timeoutSeconds) {

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        pendingApprovals.put(toolCallId, future);
        pendingDetails.put(toolCallId, new ApprovalRequestDetails(
                sessionId,
                toolCallId,
                toolName,
                input,
                reason,
                System.currentTimeMillis()
        ));

        try {
            log.info("Requesting user approval for tool '{}' (toolCallId={}, session={})", toolName, toolCallId, sessionId);
            if (eventConsumer != null) {
                eventConsumer.accept(new AgentEvent(
                        AgentEventType.PERMISSION_REQUIRED,
                        input != null && !input.isBlank() ? input : reason,
                        toolName,
                        toolCallId
                ));
            }

            Boolean approved = future.get(timeoutSeconds, TimeUnit.SECONDS);
            log.info("User approval response for tool '{}' (toolCallId={}): {}", toolName, toolCallId, approved);
            return Boolean.TRUE.equals(approved);
        } catch (TimeoutException e) {
            log.warn("User approval timed out after {}s for tool '{}' (toolCallId={})", timeoutSeconds, toolName, toolCallId);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Approval wait interrupted for tool '{}' (toolCallId={})", toolName, toolCallId);
            return false;
        } catch (Exception e) {
            log.error("Error awaiting approval for tool '{}' (toolCallId={}): {}", toolName, toolCallId, e.getMessage(), e);
            return false;
        } finally {
            pendingApprovals.remove(toolCallId);
            pendingDetails.remove(toolCallId);
        }
    }

    /**
     * Resolve a pending approval request with the user's decision.
     */
    public boolean resolveApproval(String sessionId, String toolCallId, boolean approved) {
        CompletableFuture<Boolean> future = pendingApprovals.get(toolCallId);
        if (future != null) {
            ApprovalRequestDetails details = pendingDetails.get(toolCallId);
            if (details != null && !details.sessionId().equals(sessionId)) {
                log.warn("Session ID mismatch for approval request: expected {}, provided {}", details.sessionId(), sessionId);
                return false;
            }
            log.info("Resolving approval for toolCallId={} with decision={}", toolCallId, approved);
            future.complete(approved);
            return true;
        }
        log.warn("No pending approval found for toolCallId={}", toolCallId);
        return false;
    }

    /**
     * Checks if there is an active pending approval for the given toolCallId.
     */
    public boolean hasPendingApproval(String toolCallId) {
        return pendingApprovals.containsKey(toolCallId);
    }
}
