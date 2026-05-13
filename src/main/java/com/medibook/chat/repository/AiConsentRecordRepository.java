package com.medibook.chat.repository;

import com.medibook.chat.entity.AiConsentRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AiConsentRecordRepository extends JpaRepository<AiConsentRecord, Long> {

    Optional<AiConsentRecord> findByPatientIdAndConversationId(Long patientId, Long conversationId);
}
