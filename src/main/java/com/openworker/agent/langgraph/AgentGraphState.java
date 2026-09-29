package com.openworker.agent.langgraph;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.bsc.langgraph4j.prebuilt.MessagesState;
import org.bsc.langgraph4j.state.Channel;
import org.bsc.langgraph4j.state.Channels;
import org.springframework.ai.chat.messages.Message;

/**
 * State container for LangGraph-powered ReAct agent turns.
 * Inherits the standard message list channel from {@link MessagesState}
 * and tracks the reasoning step count across iterations.
 */
public class AgentGraphState extends MessagesState<Message> {

    public static final String STEP_COUNT_KEY = "stepCount";

    public static final Map<String, Channel<?>> SCHEMA;

    static {
        Map<String, Channel<?>> schema = new LinkedHashMap<>(MessagesState.SCHEMA);
        schema.put(STEP_COUNT_KEY, Channels.base((oldVal, newVal) -> newVal, () -> 0));
        SCHEMA = Collections.unmodifiableMap(schema);
    }

    public AgentGraphState(Map<String, Object> data) {
        super(data);
    }

    public int getStepCount() {
        return this.<Integer>value(STEP_COUNT_KEY).orElse(0);
    }
}
