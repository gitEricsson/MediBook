package com.medibook.chat.repository;

import com.medibook.chat.entity.AiDraftResponse;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AiDraftResponseRepository extends JpaRepository<AiDraftResponse, Long> {

    List<AiDraftResponse> findByConversationIdAndStatusOrderByCreatedAtDesc(
            Long conversationId, AiDraftResponse.DraftStatus status);

    List<AiDraftResponse> findByDoctorIdAndStatusOrderByCreatedAtDesc(
            Long doctorId, AiDraftResponse.DraftStatus status);
}
