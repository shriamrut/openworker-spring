package com.openworker.agent.interfaces;

import java.util.function.Consumer;

import com.openworker.agent.models.internals.services.AgentEvent;

public interface TurnEngine {
    /**
     * Execute a turn in the agent session.
     * 
     * @param sessionId     the session id
     * @param userPrompt    the user prompt
     * @param eventConsumer the event consumer
     */
    void executeTurn(String sessionId,
            String userPrompt,
            Consumer<AgentEvent> eventConsumer);

    /**
     * Execute a turn in the agent session with optional provider and model overrides.
     *
     * @param sessionId        the session id
     * @param userPrompt       the user prompt
     * @param providerOverride the provider override (e.g. openai, ollama, anthropic, google-genai)
     * @param modelOverride    the model override
     * @param eventConsumer    the event consumer
     */
    default void executeTurn(String sessionId,
            String userPrompt,
            String providerOverride,
            String modelOverride,
            Consumer<AgentEvent> eventConsumer) {
        executeTurn(sessionId, userPrompt, eventConsumer);
    }

    /**
     * Halts an active turn for the given session.
     *
     * @param sessionId the session id
     * @return true if an active turn was found and halt was initiated, false otherwise
     */
    default boolean haltTurn(String sessionId) {
        return false;
    }

    /**
     * Checks if a turn is actively running for the given session.
     *
     * @param sessionId the session id
     * @return true if a turn is in progress, false otherwise
     */
    default boolean isTurnActive(String sessionId) {
        return false;
    }
}