package com.medibook.audit.repository;

import com.medibook.audit.entity.AuditLog;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface AuditLogRepository extends CassandraRepository<AuditLog, UUID> {

    @Query("SELECT * FROM audit_log WHERE actor_id = ?0 LIMIT 50")
    List<AuditLog> findRecentByActorId(Long actorId);

    @Query("SELECT * FROM audit_log WHERE actor_id = ?0 AND occurred_at >= ?1 LIMIT 100")
    List<AuditLog> findByActorIdSince(Long actorId, Instant since);
}
