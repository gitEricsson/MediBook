package com.medibook.chat.repository;

import com.medibook.chat.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {

    List<ChatMessage> findByConversationIdOrderByCreatedAtAsc(Long conversationId);

    Optional<ChatMessage> findByTwilioMessageSid(String sid);

    /** Build conversation context for AI — all non-system messages, oldest first */
    @Query("""
           SELECT m FROM ChatMessage m
           WHERE m.conversationId = :conversationId
           AND m.senderRole <> 'SYSTEM'
           ORDER BY m.createdAt ASC
           """)
    List<ChatMessage> findContextMessages(Long conversationId);

    long countByConversationId(Long conversationId);
}
