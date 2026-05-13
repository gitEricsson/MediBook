package com.medibook.ai.audit.repository;

import com.medibook.ai.audit.entity.AiMessageAudit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiMessageAuditRepository extends JpaRepository<AiMessageAudit, Long> {

    List<AiMessageAudit> findByConversationIdOrderByCreatedAtDesc(Long conversationId);

    List<AiMessageAudit> findByActorIdOrderByCreatedAtDesc(Long actorId);
}
