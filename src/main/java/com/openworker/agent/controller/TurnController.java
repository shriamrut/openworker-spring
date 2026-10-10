package com.openworker.agent.controller;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.openworker.agent.interfaces.TurnEngine;
import com.openworker.agent.models.publics.PermissionDecisionRequest;
import com.openworker.agent.models.publics.TurnRequest;
import com.openworker.agent.services.ConversationMemoryService;
import com.openworker.agent.services.ToolApprovalService;
import com.openworker.agent.services.TurnExecutionManager;
import com.openworker.agent.tools.InstrumentedToolCallback.StepLimitExceededException;
import com.openworker.agent.models.internals.services.AgentEvent;
import com.openworker.agent.models.internals.services.AgentEventType;

import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/sessions")
@Slf4j
public class TurnController {

    private final TurnEngine turnEngine;
    private final ConversationMemoryService memoryService;
    private final ToolApprovalService toolApprovalService;
    private final TurnExecutionManager turnExecutionManager;
    private final ExecutorService executorService;
    private final Long sseEmitterTimeOut;

    TurnController(TurnEngine turnEngine,
            ConversationMemoryService memoryService,
            ToolApprovalService toolApprovalService,
            TurnExecutionManager turnExecutionManager,
            @Value("${openworker.agent.engine.sseemitter.timeout:5}") Long sseEmitterTimeOut) {
        this.turnEngine = turnEngine;
        this.memoryService = memoryService;
        this.toolApprovalService = toolApprovalService;
        this.turnExecutionManager = turnExecutionManager;
        this.sseEmitterTimeOut = sseEmitterTimeOut;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PostMapping(value = "/{sessionId}/turns", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTurn(@PathVariable String sessionId, @RequestBody TurnRequest turnRequest) {
        if (turnRequest.mode() != null) {
            memoryService.updateSessionMode(sessionId, turnRequest.mode());
        }

        SseEmitter sseEmitter = new SseEmitter(sseEmitterTimeOut * 60_000L);
        if (turnExecutionManager.isTurnActive(sessionId)) {
            try {
                sseEmitter.send(SseEmitter.event().name("agent-event")
                        .data(new AgentEvent(
                                AgentEventType.ERROR,
                                "A turn is already in progress for session: " + sessionId, null, null)));
                sseEmitter.complete();
            } catch (Exception ex) {
                log.error("Unable to emit the event type due to ", ex);
            }
            return sseEmitter;
        }

        executorService.submit(() -> {
            try {
                turnEngine.executeTurn(sessionId, turnRequest.prompt(), turnRequest.provider(), turnRequest.model(), event -> {
                    try {
                        sseEmitter.send(SseEmitter.event().name("agent-event")
                                .data(event));
                    } catch (IllegalStateException e) {
                        // Emitter already completed (e.g. HTTP timeout) — safe to ignore
                        log.debug("SSE emitter closed for session {}, dropping event", sessionId);
                    } catch (IOException e) {
                        log.error("Failed to send SSE event for session {}", sessionId, e);
                        throw new RuntimeException(e);
                    }
                });
                sseEmitter.complete();
            } catch (StepLimitExceededException e) {
                // Max-steps reached: this is a controlled shutdown, complete normally
                log.warn("Step limit reached for session {}: {}", sessionId, e.getMessage());
                try {
                    sseEmitter.complete();
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                log.error("Turn execution failed for session {}", sessionId);
                try {
                    sseEmitter.completeWithError(e);
                } catch (Exception ignored) {
                }
            }
        });
        return sseEmitter;
    }

    @PostMapping(value = {"/{sessionId}/halt", "/{sessionId}/turns/halt"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> haltTurn(@PathVariable String sessionId) {
        log.info("Received request to halt turn for session {}", sessionId);
        boolean halted = turnExecutionManager.haltTurn(sessionId);
        if (halted) {
            return ResponseEntity.ok(Map.of(
                    "status", "HALTED",
                    "sessionId", sessionId,
                    "message", "Turn halted successfully."
            ));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", "NOT_FOUND",
                    "sessionId", sessionId,
                    "message", "No active turn found for session: " + sessionId
            ));
        }
    }

    @PostMapping(value = "/{sessionId}/permissions/{toolCallId}", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> submitPermissionDecision(
            @PathVariable String sessionId,
            @PathVariable String toolCallId,
            @RequestBody PermissionDecisionRequest request) {
        log.info("Received permission decision for session {}, toolCallId {}: approved={}",
                sessionId, toolCallId, request.approved());
        boolean resolved = toolApprovalService.resolveApproval(sessionId, toolCallId, request.approved());
        if (resolved) {
            return ResponseEntity.ok(Map.of(
                    "status", "RESOLVED",
                    "sessionId", sessionId,
                    "toolCallId", toolCallId,
                    "approved", request.approved()
            ));
        } else {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "status", "NOT_FOUND",
                    "message", "No active approval request found for toolCallId: " + toolCallId
            ));
        }
    }
}
