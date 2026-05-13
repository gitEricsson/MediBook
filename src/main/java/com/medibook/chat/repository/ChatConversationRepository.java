package com.medibook.chat.repository;

import com.medibook.chat.entity.ChatConversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {

    Optional<ChatConversation> findByTwilioConversationSid(String sid);

    Optional<ChatConversation> findByAppointmentId(Long appointmentId);

    List<ChatConversation> findByPatientId(Long patientId);

    List<ChatConversation> findByDoctorId(Long doctorId);

    boolean existsByPatientIdAndId(Long patientId, Long conversationId);

    boolean existsByDoctorIdAndId(Long doctorId, Long conversationId);
}
