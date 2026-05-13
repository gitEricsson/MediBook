package com.medibook.domain.telemedicine.repository;

import com.medibook.domain.telemedicine.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findBySessionIdOrderBySentAtAsc(Long sessionId);
}
