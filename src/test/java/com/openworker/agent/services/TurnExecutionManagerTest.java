package com.openworker.agent.services;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TurnExecutionManagerTest {

    @Mock
    private ToolApprovalService toolApprovalService;

    private TurnExecutionManager manager;

    @BeforeEach
    void setUp() {
        manager = new TurnExecutionManager(toolApprovalService);
    }

    @Test
    @DisplayName("startTurn registers active turn; duplicate registration returns false")
    void testStartTurnAndDuplicate() {
        String sessionId = "sess-1";
        Thread current = Thread.currentThread();

        assertTrue(manager.startTurn(sessionId, current));
        assertTrue(manager.isTurnActive(sessionId));
        assertFalse(manager.isHalted(sessionId));

        // Duplicate registration should fail
        assertFalse(manager.startTurn(sessionId, current));

        manager.finishTurn(sessionId);
        assertFalse(manager.isTurnActive(sessionId));
    }

    @Test
    @DisplayName("haltTurn interrupts execution thread, cancels tool futures and pending approvals")
    void testHaltTurn() throws InterruptedException {
        String sessionId = "sess-halt";
        CountDownLatch threadStarted = new CountDownLatch(1);
        CountDownLatch threadInterrupted = new CountDownLatch(1);
        AtomicBoolean wasInterrupted = new AtomicBoolean(false);

        Thread worker = Thread.ofVirtual().start(() -> {
            threadStarted.countDown();
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                wasInterrupted.set(true);
                threadInterrupted.countDown();
            }
        });

        assertTrue(threadStarted.await(2, TimeUnit.SECONDS));
        assertTrue(manager.startTurn(sessionId, worker));

        CompletableFuture<String> toolFuture = new CompletableFuture<>();
        manager.registerToolFuture(sessionId, toolFuture);
        assertFalse(toolFuture.isDone());

        // Trigger halt
        assertTrue(manager.haltTurn(sessionId));
        assertTrue(manager.isHalted(sessionId));

        // Tool future should be cancelled
        assertTrue(toolFuture.isCancelled());

        // Approvals should be cancelled for session
        verify(toolApprovalService).cancelApprovalsForSession(sessionId);

        // Worker thread should have been interrupted
        assertTrue(threadInterrupted.await(2, TimeUnit.SECONDS));
        assertTrue(wasInterrupted.get());

        manager.finishTurn(sessionId);
        assertFalse(manager.isTurnActive(sessionId));
    }

    @Test
    @DisplayName("haltTurn returns false when session has no active turn")
    void testHaltNonExistentTurn() {
        assertFalse(manager.haltTurn("unknown-session"));
    }

    @Test
    @DisplayName("registerToolFuture cancels future immediately if turn is already halted")
    void testRegisterToolFutureAfterHalt() {
        String sessionId = "sess-prehalt";
        assertTrue(manager.startTurn(sessionId, Thread.currentThread()));
        assertTrue(manager.haltTurn(sessionId));

        CompletableFuture<String> future = new CompletableFuture<>();
        manager.registerToolFuture(sessionId, future);
        assertTrue(future.isCancelled());

        manager.finishTurn(sessionId);
    }
}
