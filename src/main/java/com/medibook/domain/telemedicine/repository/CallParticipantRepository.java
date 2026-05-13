package com.medibook.domain.telemedicine.repository;

import com.medibook.domain.telemedicine.entity.CallParticipant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CallParticipantRepository extends JpaRepository<CallParticipant, Long> {

    Optional<CallParticipant> findBySessionIdAndUserId(Long sessionId, Long userId);

    List<CallParticipant> findBySessionId(Long sessionId);
}
