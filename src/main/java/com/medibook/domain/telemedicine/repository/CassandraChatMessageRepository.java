package com.medibook.domain.telemedicine.repository;

import com.medibook.domain.telemedicine.entity.CassandraChatMessage;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface CassandraChatMessageRepository
        extends CassandraRepository<CassandraChatMessage, UUID> {

    @Query("SELECT * FROM telemedicine_chat_messages WHERE session_id = ?0 ORDER BY sent_at ASC")
    List<CassandraChatMessage> findBySessionIdOrderBySentAtAsc(Long sessionId);

    @Query("SELECT * FROM telemedicine_chat_messages WHERE session_id = ?0 LIMIT ?1")
    List<CassandraChatMessage> findRecentBySessionId(Long sessionId, int limit);
}
