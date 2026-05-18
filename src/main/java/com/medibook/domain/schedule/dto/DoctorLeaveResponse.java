package com.medibook.domain.schedule.dto;

import com.medibook.domain.schedule.entity.DoctorLeave;

import java.time.LocalDate;

/**
 * Lean DTO for doctor-leave list responses. Avoids serializing the lazy-loaded
 * {@code doctor}, {@code createdBy}, {@code reviewedBy} JPA proxies, which would
 * otherwise blow up with {@code LazyInitializationException} once the transactional
 * boundary ends before Jackson runs.
 */
public record DoctorLeaveResponse(
        Long id,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        String leaveType,
        String status
) {
    public static DoctorLeaveResponse from(DoctorLeave l) {
        return new DoctorLeaveResponse(
                l.getId(),
                l.getStartDate(),
                l.getEndDate(),
                l.getReason(),
                l.getLeaveType(),
                l.getStatus()
        );
    }
}
