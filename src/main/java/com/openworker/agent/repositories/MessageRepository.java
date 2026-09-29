package com.openworker.agent.repositories;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import java.util.List;
import com.openworker.agent.models.internals.db.MessageEntity;

public interface MessageRepository extends CrudRepository<MessageEntity, Long> {

    @Query("SELECT * from messages WHERE session_id = :sessionId ORDER BY created_at ASC, id ASC")
    List<MessageEntity> findSessionByIdOrderByCreatedAtAsc(@Param("sessionId") String sessionId);
}
