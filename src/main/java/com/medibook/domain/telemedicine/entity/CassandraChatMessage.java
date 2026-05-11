package com.medibook.domain.telemedicine.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Cassandra-backed chat message for telemedicine sessions.
 * Partition key: session_id — all messages for a session in one partition.
 * Clustering key: sent_at DESC, message_id — most recent first.
 *
 * Kept as a separate entity from the MySQL ChatMessage so the write path
 * (high frequency during active sessions) goes to Cassandra while the
 * MySQL entity remains for structured relational queries.
 */
@Table("telemedicine_chat_messages")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CassandraChatMessage {

    @PrimaryKeyColumn(name = "session_id", type = PrimaryKeyType.PARTITIONED)
    private Long sessionId;

    @PrimaryKeyColumn(name = "sent_at", type = PrimaryKeyType.CLUSTERED,
            ordering = org.springframework.data.cassandra.core.cql.Ordering.DESCENDING)
    private Instant sentAt;

    @PrimaryKeyColumn(name = "message_id", type = PrimaryKeyType.CLUSTERED)
    private UUID messageId;

    @Column("sender_id")
    private Long senderId;

    @Column("sender_name")
    private String senderName;

    @Column("sender_role")
    private String senderRole;

    @Column("message")
    private String message;

    @Column("is_system")
    private boolean system;
}
