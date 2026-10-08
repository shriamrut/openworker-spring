package com.openworker.agent.controller;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.openworker.agent.interfaces.TurnEngine;
import com.openworker.agent.services.ConversationMemoryService;
import com.openworker.agent.services.ToolApprovalService;
import com.openworker.agent.services.TurnExecutionManager;

@ExtendWith(MockitoExtension.class)
class TurnControllerTest {

    @Mock
    private TurnEngine turnEngine;

    @Mock
    private ConversationMemoryService memoryService;

    @Mock
    private ToolApprovalService toolApprovalService;

    @Mock
    private TurnExecutionManager turnExecutionManager;

    private TurnController turnController;

    @BeforeEach
    void setUp() {
        turnController = new TurnController(turnEngine, memoryService, toolApprovalService, turnExecutionManager, 5L);
    }

    @Test
    @DisplayName("haltTurn returns 200 OK when turn is active and halted")
    void testHaltTurnSuccess() {
        String sessionId = "sess-active";
        when(turnExecutionManager.haltTurn(sessionId)).thenReturn(true);

        ResponseEntity<Map<String, Object>> response = turnController.haltTurn(sessionId);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("HALTED", response.getBody().get("status"));
        assertEquals(sessionId, response.getBody().get("sessionId"));
        verify(turnExecutionManager).haltTurn(sessionId);
    }

    @Test
    @DisplayName("haltTurn returns 404 NOT_FOUND when no active turn exists for session")
    void testHaltTurnNotFound() {
        String sessionId = "sess-idle";
        when(turnExecutionManager.haltTurn(sessionId)).thenReturn(false);

        ResponseEntity<Map<String, Object>> response = turnController.haltTurn(sessionId);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertEquals("NOT_FOUND", response.getBody().get("status"));
        verify(turnExecutionManager).haltTurn(sessionId);
    }
}
