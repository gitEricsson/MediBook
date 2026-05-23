package com.medibook.domain.schedule.dto;

import com.medibook.domain.schedule.entity.DoctorSlotBlock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

public record SlotBlockResponse(
        Long id,
        Long doctorId,
        String doctorName,
        String departmentName,
        LocalDate blockDate,
        LocalTime startTime,
        LocalTime endTime,
        String reason,
        String createdByName,
        Instant createdAt
) {
    public static SlotBlockResponse from(DoctorSlotBlock b) {
        var doc = b.getDoctor();
        var docUser = doc != null ? doc.getUser() : null;
        var dept = doc != null ? doc.getDepartment() : null;
        return new SlotBlockResponse(
                b.getId(),
                doc != null ? doc.getId() : null,
                docUser != null ? docUser.getFullName() : null,
                dept != null ? dept.getName() : null,
                b.getBlockDate(),
                b.getStartTime(),
                b.getEndTime(),
                b.getReason(),
                b.getCreatedBy() != null ? b.getCreatedBy().getFullName() : null,
                b.getCreatedAt()
        );
    }
}
