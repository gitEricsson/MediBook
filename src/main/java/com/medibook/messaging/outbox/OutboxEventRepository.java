package com.medibook.messaging.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("""
        SELECT e FROM OutboxEvent e
        WHERE e.status = 'PENDING'
          AND e.scheduledAfter <= :now
        ORDER BY e.createdAt ASC
        LIMIT :limit
        """)
    List<OutboxEvent> findPendingEvents(LocalDateTime now, int limit);

    @Modifying
    @Query("UPDATE OutboxEvent e SET e.status = 'PROCESSING' WHERE e.id IN :ids")
    void markProcessing(List<Long> ids);
}
