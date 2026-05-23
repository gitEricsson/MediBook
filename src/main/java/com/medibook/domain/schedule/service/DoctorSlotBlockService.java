package com.medibook.domain.schedule.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.service.DoctorScheduleService;
import com.medibook.domain.schedule.dto.SlotBlockRequest;
import com.medibook.domain.schedule.dto.SlotBlockResponse;
import com.medibook.domain.schedule.entity.DoctorSlotBlock;
import com.medibook.domain.schedule.repository.DoctorSlotBlockRepository;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * Ad-hoc, per-day slot unavailability owned by the doctor. The availability
 * grid consumes these directly to mark slots as BLOCKED.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DoctorSlotBlockService {

    private final DoctorSlotBlockRepository slotBlockRepository;
    private final DoctorRepository          doctorRepository;
    private final UserRepository            userRepository;
    private final DoctorScheduleService     doctorScheduleService;

    @Transactional
    public SlotBlockResponse create(Long doctorId, SlotBlockRequest req, UserPrincipal principal) {
        Doctor doctor = doctorRepository.findById(doctorId)
                .orElseThrow(() -> new ResourceNotFoundException("Doctor", "id", doctorId));

        boolean isOwn   = doctor.getUser().getId().equals(principal.getId());
        boolean isAdmin = principal.hasRole("ROLE_ADMIN") || principal.hasRole("ROLE_SUPER_ADMIN");
        if (!isOwn && !isAdmin) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        if (!req.getStartTime().isBefore(req.getEndTime())) {
            throw new MediBookException("End time must be after start time",
                    HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE");
        }
        if (req.getBlockDate().isBefore(LocalDate.now())) {
            throw new MediBookException("Cannot block a date in the past",
                    HttpStatus.BAD_REQUEST, "INVALID_DATE_RANGE");
        }

        DoctorSlotBlock block = DoctorSlotBlock.builder()
                .doctor(doctor)
                .blockDate(req.getBlockDate())
                .startTime(req.getStartTime())
                .endTime(req.getEndTime())
                .reason(req.getReason())
                .createdBy(userRepository.getReferenceById(principal.getId()))
                .build();

        DoctorSlotBlock saved = slotBlockRepository.save(block);

        // Bust the per-doctor-per-day slot cache so patient availability picks
        // up the new block on the next request.
        doctorScheduleService.evictSlotCache(doctorId, req.getBlockDate());

        log.info("Slot block [{}] created by user [{}] for doctor [{}] on {} ({} - {}): {}",
                saved.getId(), principal.getId(), doctorId, req.getBlockDate(),
                req.getStartTime(), req.getEndTime(), req.getReason());
        return SlotBlockResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public List<SlotBlockResponse> listForDoctor(Long doctorId, LocalDate from, LocalDate to) {
        return slotBlockRepository
                .findByDoctorIdAndBlockDateBetweenOrderByBlockDateAscStartTimeAsc(doctorId, from, to)
                .stream()
                .map(SlotBlockResponse::from)
                .toList();
    }

    /** Raw entities — used by the availability grid which only needs the time window. */
    @Transactional(readOnly = true)
    public List<DoctorSlotBlock> findRawForDoctor(Long doctorId, LocalDate from, LocalDate to) {
        return slotBlockRepository
                .findByDoctorIdAndBlockDateBetweenOrderByBlockDateAscStartTimeAsc(doctorId, from, to);
    }

    @Transactional(readOnly = true)
    public List<SlotBlockResponse> listAdminAudit(LocalDate from, LocalDate to) {
        return slotBlockRepository.findAllInRange(from, to).stream()
                .map(SlotBlockResponse::from)
                .toList();
    }

    @Transactional
    public void delete(Long blockId, UserPrincipal principal) {
        DoctorSlotBlock block = slotBlockRepository.findById(blockId)
                .orElseThrow(() -> new ResourceNotFoundException("DoctorSlotBlock", "id", blockId));

        boolean isOwn   = block.getDoctor().getUser().getId().equals(principal.getId());
        boolean isAdmin = principal.hasRole("ROLE_ADMIN") || principal.hasRole("ROLE_SUPER_ADMIN");
        if (!isOwn && !isAdmin) {
            throw new MediBookException("Not authorized", HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        Long doctorId = block.getDoctor().getId();
        LocalDate blockDate = block.getBlockDate();
        slotBlockRepository.delete(block);
        doctorScheduleService.evictSlotCache(doctorId, blockDate);
        log.info("Slot block [{}] removed by user [{}]", blockId, principal.getId());
    }
}
