package com.medibook.domain.review.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.domain.review.dto.ReviewRequest;
import com.medibook.domain.review.dto.ReviewResponse;
import com.medibook.domain.review.entity.DoctorReview;
import com.medibook.domain.review.repository.DoctorReviewRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final DoctorReviewRepository reviewRepository;
    private final AppointmentRepository  appointmentRepository;
    private final DoctorRepository       doctorRepository;
    private final UserRepository         userRepository;
    private final NotificationService    notificationService;

    @Transactional
    public ReviewResponse submitReview(ReviewRequest req, UserPrincipal principal) {
        Appointment appointment = appointmentRepository.findByIdWithDetails(req.getAppointmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Appointment", "id", req.getAppointmentId()));

        if (!appointment.getPatient().getId().equals(principal.getId())) {
            throw new MediBookException("You can only review appointments that belong to you",
                    HttpStatus.FORBIDDEN, "ACCESS_DENIED");
        }

        if (appointment.getStatus() != AppointmentStatus.COMPLETED) {
            throw new MediBookException("You can only review completed appointments",
                    HttpStatus.BAD_REQUEST, "APPOINTMENT_NOT_COMPLETED");
        }

        if (reviewRepository.existsByAppointmentId(req.getAppointmentId())) {
            throw new MediBookException("You have already reviewed this appointment",
                    HttpStatus.CONFLICT, "REVIEW_EXISTS");
        }

        User patient = appointment.getPatient();
        Doctor doctor = appointment.getDoctor();

        DoctorReview review = DoctorReview.builder()
                .appointment(appointment)
                .patient(patient)
                .doctor(doctor)
                .rating(req.getRating())
                .comment(req.getComment())
                .status("PENDING_MODERATION")
                .build();

        DoctorReview saved = reviewRepository.save(review);
        log.info("Review [{}] submitted for doctor [{}] by patient [{}]",
                saved.getId(), doctor.getId(), patient.getId());

        return ReviewResponse.fromEntity(saved);
    }

    @Transactional
    public ReviewResponse moderateReview(Long reviewId, String action, UserPrincipal moderator) {
        DoctorReview review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new ResourceNotFoundException("Review", "id", reviewId));

        String newStatus = switch (action.toUpperCase()) {
            case "APPROVE" -> "APPROVED";
            case "REJECT"  -> "REJECTED";
            default        -> throw new MediBookException("Invalid moderation action: " + action,
                    HttpStatus.BAD_REQUEST, "INVALID_ACTION");
        };

        User admin = userRepository.findById(moderator.getId())
                .orElseThrow(() -> new ResourceNotFoundException("User", "id", moderator.getId()));

        review.setStatus(newStatus);
        review.setModeratedBy(admin);
        review.setModeratedAt(LocalDateTime.now());
        DoctorReview saved = reviewRepository.save(review);

        if ("APPROVED".equals(newStatus)) {
            recalculateDoctorRating(review.getDoctor().getId());
            notificationService.sendReviewApproved(
                    review.getPatient().getId(),
                    review.getDoctor().getUser().getFullName());
        }

        return ReviewResponse.fromEntity(saved);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getApprovedReviewsForDoctor(Long doctorId, Pageable pageable) {
        return reviewRepository.findByDoctorIdAndStatus(doctorId, "APPROVED", pageable)
                .map(ReviewResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getMyReviews(UserPrincipal principal, Pageable pageable) {
        return reviewRepository.findByPatientId(principal.getId(), pageable)
                .map(ReviewResponse::fromEntity);
    }

    @Transactional(readOnly = true)
    public Page<ReviewResponse> getPendingReviews(Pageable pageable) {
        return reviewRepository.findByStatus("PENDING_MODERATION", pageable)
                .map(ReviewResponse::fromEntity);
    }

    private void recalculateDoctorRating(Long doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId).orElse(null);
        if (doctor == null) return;
        double avg = reviewRepository.findAverageRatingByDoctorId(doctorId).orElse(0.0);
        long count = reviewRepository.countApprovedByDoctorId(doctorId);
        doctor.setAverageRating(avg);
        doctor.setReviewCount((int) count);
        doctorRepository.save(doctor);
        log.info("Doctor [{}] rating recalculated: avg={} count={}", doctorId, avg, count);
    }
}
