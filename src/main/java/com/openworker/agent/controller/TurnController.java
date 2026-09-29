package com.openworker.agent.controller;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.openworker.agent.interfaces.TurnEngine;
import com.openworker.agent.models.publics.TurnRequest;
import com.openworker.agent.services.ConversationMemoryService;
import com.openworker.agent.tools.InstrumentedToolCallback.StepLimitExceededException;

import org.springframework.web.bind.annotation.RequestBody;
import lombok.extern.slf4j.Slf4j;

@RestController
@RequestMapping("/api/sessions")
@Slf4j
public class TurnController {

    private final TurnEngine turnEngine;
    private final ConversationMemoryService memoryService;
    private final ExecutorService executorService;
    private final Long sseEmitterTimeOut;

    TurnController(TurnEngine turnEngine,
            ConversationMemoryService memoryService,
            @Value("${openworker.agent.engine.sseemitter.timeout:5}") Long sseEmitterTimeOut) {
        this.turnEngine = turnEngine;
        this.memoryService = memoryService;
        this.sseEmitterTimeOut = sseEmitterTimeOut;
        this.executorService = Executors.newVirtualThreadPerTaskExecutor();
    }

    @PostMapping(value = "/{sessionId}/turns", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTurn(@PathVariable String sessionId, @RequestBody TurnRequest turnRequest) {
        if (turnRequest.mode() != null) {
            memoryService.updateSessionMode(sessionId, turnRequest.mode());
        }
        SseEmitter sseEmitter = new SseEmitter(sseEmitterTimeOut * 60_000L);
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
}
