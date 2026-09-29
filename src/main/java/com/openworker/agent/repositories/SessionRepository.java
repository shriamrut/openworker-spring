package com.openworker.agent.repositories;

import org.springframework.data.repository.CrudRepository;
import org.springframework.stereotype.Repository;

import com.openworker.agent.models.internals.db.SessionEntity;

@Repository
public interface SessionRepository extends CrudRepository<SessionEntity, String> {
}
