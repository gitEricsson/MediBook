package com.medibook.domain.schedule.dto;

import com.medibook.domain.schedule.entity.DoctorLeave;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Admin-facing leave response. Includes the requesting doctor's identity so the
 * review queue can render "Dr. X — Cardiology" without a separate fetch, and
 * audit metadata (who submitted, who reviewed, when). Avoids serialising the
 * lazy JPA proxies on {@link DoctorLeave} directly.
 */
public record AdminLeaveResponse(
        Long id,
        Long doctorId,
        String doctorName,
        String doctorEmail,
        String departmentName,
        LocalDate startDate,
        LocalDate endDate,
        String reason,
        String leaveType,
        String status,
        String createdByName,
        String reviewedByName,
        Instant reviewedAt
) {
    public static AdminLeaveResponse from(DoctorLeave l) {
        var doc = l.getDoctor();
        var docUser = doc != null ? doc.getUser() : null;
        var dept = doc != null ? doc.getDepartment() : null;
        return new AdminLeaveResponse(
                l.getId(),
                doc != null ? doc.getId() : null,
                docUser != null ? docUser.getFullName() : null,
                docUser != null ? docUser.getEmail() : null,
                dept != null ? dept.getName() : null,
                l.getStartDate(),
                l.getEndDate(),
                l.getReason(),
                l.getLeaveType(),
                l.getStatus(),
                l.getCreatedBy() != null ? l.getCreatedBy().getFullName() : null,
                l.getReviewedBy() != null ? l.getReviewedBy().getFullName() : null,
                l.getReviewedAt()
        );
    }
}
