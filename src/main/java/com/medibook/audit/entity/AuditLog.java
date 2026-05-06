package com.medibook.audit.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Cassandra wide-column audit log.
 * Partition key: actor_id  — all audit records for a user in one partition.
 * Clustering key: occurred_at DESC — most recent records first.
 */
@Table("audit_log")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuditLog {

    @PrimaryKeyColumn(name = "actor_id", type = PrimaryKeyType.PARTITIONED)
    private Long actorId;

    @PrimaryKeyColumn(name = "occurred_at", type = PrimaryKeyType.CLUSTERED,
    ordering = org.springframework.data.cassandra.core.cql.Ordering.DESCENDING)
    private Instant occurredAt;

    @PrimaryKeyColumn(name = "event_id", type = PrimaryKeyType.CLUSTERED)
    private UUID eventId;

    @Column("action")
    private String action;

    @Column("actor_email")
    private String actorEmail;

    @Column("entity_type")
    private String entityType;

    @Column("entity_id")
    private String entityId;

    @Column("before_state")
    private String beforeState;

    @Column("after_state")
    private String afterState;

    @Column("ip_address")
    private String ipAddress;

    @Column("correlation_id")
    private String correlationId;
}
