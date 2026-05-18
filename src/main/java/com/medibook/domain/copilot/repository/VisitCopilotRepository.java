package com.medibook.domain.copilot.repository;

import com.medibook.domain.copilot.entity.VisitCopilotSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface VisitCopilotRepository extends JpaRepository<VisitCopilotSession, Long> {

    Optional<VisitCopilotSession> findByTelemedicineSessionId(Long telemedicineSessionId);

    Optional<VisitCopilotSession> findByAppointmentId(Long appointmentId);
}
