package com.medibook.chat.repository;

import com.medibook.chat.entity.UrgencyAlert;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UrgencyAlertRepository extends JpaRepository<UrgencyAlert, Long> {

    List<UrgencyAlert> findByConversationIdOrderByCreatedAtDesc(Long conversationId);

    List<UrgencyAlert> findByDoctorIdAndAcknowledgedAtIsNullOrderByCreatedAtDesc(Long doctorId);
}
