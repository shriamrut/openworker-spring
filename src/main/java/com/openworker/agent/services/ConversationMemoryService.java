package com.openworker.agent.services;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.openworker.agent.models.internals.db.MessageEntity;
import com.openworker.agent.models.internals.db.SessionEntity;
import com.openworker.agent.models.internals.services.AgentMode;
import com.openworker.agent.models.publics.MessageResponse;
import com.openworker.agent.models.publics.SessionResponse;
import com.openworker.agent.models.publics.SessionSummaryResponse;
import com.openworker.agent.repositories.MessageRepository;
import com.openworker.agent.repositories.SessionRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationMemoryService {

    private final SessionRepository sessionRepository;
    private final MessageRepository messageRepository;

    @Transactional
    public SessionSummaryResponse createSession(String title, AgentMode initialMode, String provider, String model) {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String mode = (initialMode != null ? initialMode : AgentMode.DISCUSS).name();
        String sessionTitle = (title != null && !title.isBlank()) ? title : "Session " + id.substring(0, 8);

        SessionEntity sessionEntity = SessionEntity.builder()
                .id(id)
                .title(sessionTitle)
                .agentMode(mode)
                .modelProvider(provider)
                .modelName(model)
                .createdAt(now)
                .updatedAt(now)
                .build();

        sessionRepository.save(sessionEntity);
        return new SessionSummaryResponse(
                sessionEntity.getId(),
                sessionEntity.getTitle(),
                sessionEntity.getAgentMode(),
                sessionEntity.getModelProvider(),
                sessionEntity.getModelName(),
                sessionEntity.getCreatedAt(),
                sessionEntity.getUpdatedAt());
    }

    @Transactional
    public SessionSummaryResponse createSession(String title, AgentMode initialMode) {
        return createSession(title, initialMode, null, null);
    }

    @Transactional(readOnly = true)
    public List<SessionSummaryResponse> listSessions() {
        return StreamSupport.stream(sessionRepository.findAll().spliterator(), false)
                .map(s -> new SessionSummaryResponse(
                        s.getId(),
                        s.getTitle(),
                        s.getAgentMode(),
                        s.getModelProvider(),
                        s.getModelName(),
                        s.getCreatedAt(),
                        s.getUpdatedAt()))
                .toList();
    }

    @Transactional(readOnly = true)
    public Optional<SessionResponse> getSessionDetails(String sessionId) {
        return sessionRepository.findById(sessionId).map(session -> {
            List<MessageResponse> messageResponses = messageRepository.findSessionByIdOrderByCreatedAtAsc(sessionId).stream()
                    .map(m -> new MessageResponse(m.getId(), m.getSessionId(), m.getMessageType(), m.getContent(), m.getCreatedAt()))
                    .toList();

            return new SessionResponse(
                    session.getId(),
                    session.getTitle(),
                    session.getAgentMode(),
                    session.getModelProvider(),
                    session.getModelName(),
                    session.getCreatedAt(),
                    session.getUpdatedAt(),
                    messageResponses);
        });
    }

    @Transactional
    public Optional<SessionSummaryResponse> updateSessionMode(String sessionId, AgentMode mode) {
        return sessionRepository.findById(sessionId).map(session -> {
            session.setAgentMode(mode.name());
            session.setUpdatedAt(Instant.now());
            session.setNew(false);
            sessionRepository.save(session);
            return new SessionSummaryResponse(
                    session.getId(),
                    session.getTitle(),
                    session.getAgentMode(),
                    session.getModelProvider(),
                    session.getModelName(),
                    session.getCreatedAt(),
                    session.getUpdatedAt());
        });
    }

    @Transactional
    public Optional<SessionSummaryResponse> updateSessionModel(String sessionId, String provider, String model) {
        return sessionRepository.findById(sessionId).map(session -> {
            if (provider != null && !provider.isBlank()) {
                session.setModelProvider(provider);
            }
            if (model != null && !model.isBlank()) {
                session.setModelName(model);
            }
            session.setUpdatedAt(Instant.now());
            session.setNew(false);
            sessionRepository.save(session);
            return new SessionSummaryResponse(
                    session.getId(),
                    session.getTitle(),
                    session.getAgentMode(),
                    session.getModelProvider(),
                    session.getModelName(),
                    session.getCreatedAt(),
                    session.getUpdatedAt());
        });
    }

    @Transactional(readOnly = true)
    public Optional<SessionEntity> getSession(String sessionId) {
        return sessionRepository.findById(sessionId);
    }

    @Transactional
    public boolean deleteSession(String sessionId) {
        if (sessionRepository.existsById(sessionId)) {
            sessionRepository.deleteById(sessionId);
            return true;
        }
        return false;
    }

    @Transactional(readOnly = true)
    public AgentMode getSessionMode(String sessionId) {
        return sessionRepository.findById(sessionId)
                .map(s -> {
                    try {
                        return AgentMode.valueOf(s.getAgentMode());
                    } catch (Exception e) {
                        return AgentMode.DISCUSS;
                    }
                })
                .orElse(AgentMode.DISCUSS);
    }

    /**
     * Ensure we have the session in the database
     */
    @Transactional
    public SessionEntity ensurSessionEntity(String sessionId) {
        return sessionRepository.findById(sessionId).orElseGet(() -> {
            Instant now = Instant.now();
            SessionEntity sessionEntity = SessionEntity.builder()
                    .id(sessionId)
                    .title("Session " + sessionId)
                    .agentMode(AgentMode.DISCUSS.name())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();
            return sessionRepository.save(sessionEntity);
        });
    }

    /**
     * Appends a new message to the session history
     */
    @Transactional
    public MessageEntity saveMessage(String sessionId, String messageType, String content) {
        ensurSessionEntity(sessionId);
        MessageEntity message = MessageEntity.builder()
                .sessionId(sessionId)
                .messageType(messageType)
                .content(content)
                .createdAt(Instant.now())
                .build();
        sessionRepository.findById(sessionId).ifPresent(s -> {
            s.setUpdatedAt(Instant.now());
            s.setNew(false);
            sessionRepository.save(s);
        });
        return messageRepository.save(message);
    }

    /**
     * Assembles all previous conversation history into Spring AI message objects
     */
    @Transactional(readOnly = true)
    public List<Message> getConversationHistory(String sessionId) {
        List<MessageEntity> entities = messageRepository.findSessionByIdOrderByCreatedAtAsc(sessionId);
        List<Message> messages = new ArrayList<>();
        for (MessageEntity entity : entities) {
            String type = entity.getMessageType();
            String content = entity.getContent();
            if (MessageType.USER.name().equalsIgnoreCase(type)) {
                messages.add(new UserMessage(content));
            } else if (MessageType.ASSISTANT.name().equalsIgnoreCase(type)) {
                messages.add(new AssistantMessage(content));
            } else if (MessageType.SYSTEM.name().equalsIgnoreCase(type)) {
                messages.add(new SystemMessage(content));
            } else {
                messages.add(new AssistantMessage("[" + type + "]\n" + content));
            }
        }
        return messages;
    }
}
