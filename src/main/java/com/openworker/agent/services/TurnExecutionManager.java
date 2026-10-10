package com.openworker.agent.services;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

/**
 * Manages the lifecycle and concurrency of active agent turns per session.
 * Tracks execution threads, tool futures, and coordinates graceful halting
 * and thread interruption.
 */
@Service
@Slf4j
public class TurnExecutionManager {

    public static class ActiveTurn {
        private final String sessionId;
        private final Thread turnThread;
        private final AtomicBoolean halted = new AtomicBoolean(false);
        private final List<CompletableFuture<?>> activeToolFutures = new CopyOnWriteArrayList<>();
        private final Instant startTime = Instant.now();

        public ActiveTurn(String sessionId, Thread turnThread) {
            this.sessionId = sessionId;
            this.turnThread = turnThread;
        }

        public String getSessionId() {
            return sessionId;
        }

        public Thread getTurnThread() {
            return turnThread;
        }

        public boolean isHalted() {
            return halted.get();
        }

        public Instant getStartTime() {
            return startTime;
        }
    }

    private final Map<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();
    private final ToolApprovalService toolApprovalService;

    public TurnExecutionManager(ToolApprovalService toolApprovalService) {
        this.toolApprovalService = toolApprovalService;
    }

    /**
     * Attempts to register and start an active turn for the given session.
     *
     * @param sessionId the session identifier
     * @param turnThread the thread executing the turn (usually a virtual thread)
     * @return true if registered successfully, false if a turn is already in progress
     */
    public boolean startTurn(String sessionId, Thread turnThread) {
        ActiveTurn turn = new ActiveTurn(sessionId, turnThread);
        ActiveTurn existing = activeTurns.putIfAbsent(sessionId, turn);
        if (existing != null) {
            log.warn("Turn already active for session {}. Started at: {}", sessionId, existing.getStartTime());
            return false;
        }
        log.debug("Registered active turn for session {}", sessionId);
        return true;
    }

    /**
     * Checks if a turn is currently executing for the session.
     */
    public boolean isTurnActive(String sessionId) {
        return sessionId != null && activeTurns.containsKey(sessionId);
    }

    /**
     * Checks if the active turn for this session has been marked as halted.
     */
    public boolean isHalted(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        ActiveTurn turn = activeTurns.get(sessionId);
        return turn != null && turn.isHalted();
    }

    /**
     * Registers an in-flight tool future so it can be interrupted/cancelled if a halt occurs.
     */
    public void registerToolFuture(String sessionId, CompletableFuture<?> future) {
        if (sessionId == null || future == null) {
            return;
        }
        ActiveTurn turn = activeTurns.get(sessionId);
        if (turn != null) {
            if (turn.isHalted()) {
                future.cancel(true);
                return;
            }
            turn.activeToolFutures.add(future);
            future.whenComplete((res, err) -> turn.activeToolFutures.remove(future));
        }
    }

    /**
     * Halts an active turn for the given session.
     * Sets the halt flag, cancels tool futures, unblocks pending approvals,
     * and interrupts the turn execution thread.
     *
     * @param sessionId the session identifier
     * @return true if an active turn was found and halted, false if no turn is running
     */
    public boolean haltTurn(String sessionId) {
        if (sessionId == null) {
            return false;
        }
        ActiveTurn turn = activeTurns.get(sessionId);
        if (turn == null) {
            log.warn("No active turn found to halt for session {}", sessionId);
            return false;
        }

        log.info("Initiating halt for session {}", sessionId);
        turn.halted.set(true);

        // 1. Cancel all in-flight child tool futures
        for (CompletableFuture<?> toolFuture : turn.activeToolFutures) {
            if (!toolFuture.isDone()) {
                toolFuture.cancel(true);
            }
        }
        turn.activeToolFutures.clear();

        // 2. Cancel any pending approvals in ToolApprovalService
        if (toolApprovalService != null) {
            toolApprovalService.cancelApprovalsForSession(sessionId);
        }

        // 3. Interrupt the turn thread
        Thread thread = turn.getTurnThread();
        if (thread != null && thread.isAlive()) {
            thread.interrupt();
        }

        return true;
    }

    /**
     * Unregisters the session once the turn finishes execution.
     */
    public void finishTurn(String sessionId) {
        if (sessionId != null) {
            ActiveTurn removed = activeTurns.remove(sessionId);
            if (removed != null) {
                log.debug("Finished active turn for session {}", sessionId);
            }
        }
    }
}
