package com.medibook.domain.notification.entity;

import lombok.*;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.core.mapping.*;

import java.time.Instant;
import java.util.UUID;

/**
 * Cassandra notification table.
 * Partition key: user_id — all notifications for a user in one partition.
 * Clustering key: created_at DESC — newest first.
 */
@Table("notifications")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Notification {

    @PrimaryKeyColumn(name = "user_id", type = PrimaryKeyType.PARTITIONED)
    private Long userId;

    @PrimaryKeyColumn(name = "created_at", type = PrimaryKeyType.CLUSTERED,
    ordering = org.springframework.data.cassandra.core.cql.Ordering.DESCENDING)
    private Instant createdAt;

    @PrimaryKeyColumn(name = "notification_id", type = PrimaryKeyType.CLUSTERED)
    private UUID notificationId;

    @Column("title")
    private String title;

    @Column("message")
    private String message;

    @Column("type")
    private String type;   // APPOINTMENT_BOOKED | APPOINTMENT_CONFIRMED | APPOINTMENT_CANCELLED

    @Column("is_read")
    private boolean read;

    @Column("read_at")
    private Instant readAt;

    @Column("appointment_id")
    private Long appointmentId;

    @Column("reference_id")
    private String referenceId;
}
