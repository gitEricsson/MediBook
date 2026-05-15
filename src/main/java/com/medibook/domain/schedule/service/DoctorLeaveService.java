package com.medibook.domain.schedule.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.schedule.dto.DoctorLeaveRequest;
import com.medibook.domain.schedule.entity.DoctorLeave;
import com.medibook.domain.schedule.entity.HospitalHoliday;
import com.medibook.domain.schedule.repository.DoctorLeaveRepository;
import com.medibook.domain.schedule.repository.HospitalHolidayRepository;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorLeaveService {

    private final DoctorLeaveRepository     leaveRepository;
    private final HospitalHolidayRepository holidayRepository;
    private final DoctorRepository          doctorRepository;
    private final UserRepository            userRepository;

    @Transactional
    public DoctorLeave createLeave(Long doctorId, DoctorLeaveRequest req, UserPrincipal principal) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));

        // Doctor can only manage their own leave; admin/super_admin can manage any
        boolean isOwnLeave = doctor.getUser().getId().equals(principal.getId());
        boolean isAdmin = principal.hasRole("ROLE_ADMIN") || principal.hasRole("ROLE_SUPER_ADMIN");
        if (!isOwnLeave && !isAdmin) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        if (req.getEndDate().isBefore(req.getStartDate())) {
            throw new MediBookException("End date cannot be before start date",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }

        if (req.getStartDate().isBefore(LocalDate.now())) {
            throw new MediBookException("Start date cannot be in the past",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }

        // Check for overlapping leave requests (PENDING or APPROVED)
        List<DoctorLeave> overlapping = leaveRepository.findOverlapping(
                doctorId, req.getStartDate(), req.getEndDate());
        if (!overlapping.isEmpty()) {
            throw new MediBookException("Overlapping leave request already exists",
                    HttpStatus.CONFLICT, "LEAVE_OVERLAP");
        }

        // Admin-created leave is auto-approved; doctor-created leave is PENDING
        String initialStatus = isAdmin ? "APPROVED" : "PENDING";

        DoctorLeave leave = DoctorLeave.builder()
                .doctor(doctor)
                .startDate(req.getStartDate())
                .endDate(req.getEndDate())
                .reason(req.getReason())
                .leaveType(req.getLeaveType())
                .status(initialStatus)
                .createdBy(userRepository.getReferenceById(principal.getId()))
                .build();

        if (isAdmin) {
            leave.setReviewedBy(userRepository.getReferenceById(principal.getId()));
            leave.setReviewedAt(Instant.now());
        }

        DoctorLeave saved = leaveRepository.save(leave);
        log.info("Doctor [{}] leave [{}] created with status {}: {} to {}",
                doctorId, saved.getId(), initialStatus, req.getStartDate(), req.getEndDate());
        return saved;
    }

    @Transactional
    public DoctorLeave approveLeave(Long leaveId, UserPrincipal principal) {
        DoctorLeave leave = leaveRepository.findById(leaveId)
                .orElseThrow(() -> new ResourceNotFoundException("DoctorLeave", "id", leaveId));

        if (!"PENDING".equals(leave.getStatus())) {
            throw new MediBookException("Only PENDING leave requests can be approved",
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }

        leave.setStatus("APPROVED");
        leave.setReviewedBy(userRepository.getReferenceById(principal.getId()));
        leave.setReviewedAt(Instant.now());

        DoctorLeave saved = leaveRepository.save(leave);
        log.info("Leave [{}] approved by user [{}]", leaveId, principal.getId());
        return saved;
    }

    @Transactional
    public DoctorLeave rejectLeave(Long leaveId, UserPrincipal principal) {
        DoctorLeave leave = leaveRepository.findById(leaveId)
                .orElseThrow(() -> new ResourceNotFoundException("DoctorLeave", "id", leaveId));

        if (!"PENDING".equals(leave.getStatus())) {
            throw new MediBookException("Only PENDING leave requests can be rejected",
                    HttpStatus.BAD_REQUEST, "INVALID_STATUS");
        }

        leave.setStatus("REJECTED");
        leave.setReviewedBy(userRepository.getReferenceById(principal.getId()));
        leave.setReviewedAt(Instant.now());

        DoctorLeave saved = leaveRepository.save(leave);
        log.info("Leave [{}] rejected by user [{}]", leaveId, principal.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<DoctorLeave> getLeaveForDoctor(Long doctorId) {
        return leaveRepository.findByDoctorId(doctorId);
    }

    @Transactional(readOnly = true)
    public List<DoctorLeave> getAllPendingLeaves() {
        return leaveRepository.findByStatus("PENDING");
    }

    @Transactional(readOnly = true)
    public boolean isDoctorOnLeave(Long doctorId, LocalDate date) {
        return !leaveRepository.findActiveLeaveOnDate(doctorId, date).isEmpty()
                || holidayRepository.existsByHolidayDate(date);
    }

    @Transactional
    public HospitalHoliday createHoliday(LocalDate date, String name, Long departmentId) {
        if (holidayRepository.existsByHolidayDate(date)) {
            throw new MediBookException("Holiday already exists for date: " + date,
                    HttpStatus.CONFLICT, "HOLIDAY_EXISTS");
        }

        HospitalHoliday holiday = HospitalHoliday.builder()
                .holidayDate(date)
                .name(name)
                .build();

        return holidayRepository.save(holiday);
    }

    @Transactional(readOnly = true)
    public List<HospitalHoliday> getHolidays(LocalDate from, LocalDate to) {
        return holidayRepository.findByHolidayDateBetween(from, to);
    }
}
