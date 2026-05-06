package com.medibook.domain.notification.repository;

import com.medibook.domain.notification.entity.Notification;
import org.springframework.data.cassandra.repository.CassandraRepository;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface NotificationRepository extends CassandraRepository<Notification, UUID> {

    @Query("SELECT * FROM notifications WHERE user_id = ?0 LIMIT 30")
    List<Notification> findRecentByUserId(Long userId);

    @Query("SELECT * FROM notifications WHERE user_id = ?0 AND is_read = false LIMIT 50")
    List<Notification> findUnreadByUserId(Long userId);
}
