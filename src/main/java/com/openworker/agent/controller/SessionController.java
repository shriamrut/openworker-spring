package com.openworker.agent.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.openworker.agent.models.publics.CreateSessionRequest;
import com.openworker.agent.models.publics.SessionResponse;
import com.openworker.agent.models.publics.SessionSummaryResponse;
import com.openworker.agent.models.publics.UpdateModeRequest;
import com.openworker.agent.services.ConversationMemoryService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/sessions")
@RequiredArgsConstructor
public class SessionController {

    private final ConversationMemoryService memoryService;

    /**
     * Create a new session.
     */
    @PostMapping
    public ResponseEntity<SessionSummaryResponse> createSession(
            @RequestBody(required = false) CreateSessionRequest request) {
        log.debug("Creating a new session with title: {}", request != null ? request.title() : null);
        String title = request != null ? request.title() : null;
        var initialMode = request != null ? request.initialMode() : null;
        String provider = request != null ? request.provider() : null;
        String model = request != null ? request.model() : null;
        SessionSummaryResponse session = memoryService.createSession(title, initialMode, provider, model);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /**
     * List all sessions.
     */
    @GetMapping
    public ResponseEntity<List<SessionSummaryResponse>> listSessions() {
        log.debug("Listing all sessions");
        return ResponseEntity.ok(memoryService.listSessions());
    }

    /**
     * Get details and message history for a session.
     */
    @GetMapping("/{sessionId}")
    public ResponseEntity<SessionResponse> getSession(@PathVariable String sessionId) {
        log.debug("Getting details of session {}", sessionId);
        return memoryService.getSessionDetails(sessionId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Update the agent mode (DISCUSS, PLAN, FULL) for a session.
     */
    @PatchMapping("/{sessionId}/mode")
    public ResponseEntity<SessionSummaryResponse> updateMode(
            @PathVariable String sessionId,
            @RequestBody UpdateModeRequest request) {
        log.debug("Updating session mode for session {} to mode {}", sessionId, request.mode());
        return memoryService.updateSessionMode(sessionId, request.mode())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Update the model provider and model name for a session.
     */
    @PatchMapping("/{sessionId}/model")
    public ResponseEntity<SessionSummaryResponse> updateModel(
            @PathVariable String sessionId,
            @RequestBody com.openworker.agent.models.publics.UpdateSessionModelRequest request) {
        log.debug("Updating session model for session {} to provider {}, model {}",
                sessionId, request.provider(), request.model());
        return memoryService.updateSessionModel(sessionId, request.provider(), request.model())
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * Delete a session and its message history.
     */
    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Void> deleteSession(@PathVariable String sessionId) {
        boolean deleted = memoryService.deleteSession(sessionId);
        return deleted ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }
}
