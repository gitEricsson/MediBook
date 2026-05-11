package com.medibook.domain.review.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.review.dto.ReviewRequest;
import com.medibook.domain.review.dto.ReviewResponse;
import com.medibook.domain.review.entity.DoctorReview;
import com.medibook.domain.review.repository.DoctorReviewRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReviewServiceTest {

    @Mock DoctorReviewRepository reviewRepository;
    @Mock AppointmentRepository  appointmentRepository;
    @Mock DoctorRepository       doctorRepository;
    @Mock UserRepository         userRepository;

    @InjectMocks ReviewService reviewService;

    private User patient;
    private Doctor doctor;
    private Appointment completedAppointment;
    private UserPrincipal principal;

    @BeforeEach
    void setUp() {
        patient = User.builder().id(1L).email("p@test.com").firstName("Jane").lastName("D").role(Role.ROLE_PATIENT).build();

        Department dept = Department.builder().id(1L).name("Cardiology").build();
        User doctorUser = User.builder().id(2L).firstName("Dr").lastName("Smith").email("doc@test.com").build();
        doctor = Doctor.builder().id(1L).user(doctorUser).department(dept).licenseNumber("L001").build();

        completedAppointment = Appointment.builder()
                .id(10L)
                .patient(patient)
                .doctor(doctor)
                .department(dept)
                .scheduledAt(LocalDateTime.now().minusDays(1))
                .status(AppointmentStatus.COMPLETED)
                .type(AppointmentType.IN_PERSON)
                .confirmationCode("MB-DONE")
                .build();

        principal = UserPrincipal.fromUser(patient);
    }

    @Test
    void submitReview_success() {
        ReviewRequest req = new ReviewRequest();
        req.setAppointmentId(10L);
        req.setRating(5);
        req.setComment("Excellent doctor!");

        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(completedAppointment));
        when(reviewRepository.existsByAppointmentId(10L)).thenReturn(false);

        DoctorReview saved = DoctorReview.builder()
                .id(1L).appointment(completedAppointment).patient(patient).doctor(doctor)
                .rating(5).comment("Excellent doctor!").status("PENDING_MODERATION").build();
        when(reviewRepository.save(any())).thenReturn(saved);

        ReviewResponse response = reviewService.submitReview(req, principal);

        assertThat(response.getRating()).isEqualTo(5);
        assertThat(response.getStatus()).isEqualTo("PENDING_MODERATION");
    }

    @Test
    void submitReview_notCompleted_throwsException() {
        completedAppointment.setStatus(AppointmentStatus.PENDING);
        ReviewRequest req = new ReviewRequest();
        req.setAppointmentId(10L);
        req.setRating(4);

        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(completedAppointment));

        assertThatThrownBy(() -> reviewService.submitReview(req, principal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("completed");
    }

    @Test
    void submitReview_duplicate_throwsConflict() {
        ReviewRequest req = new ReviewRequest();
        req.setAppointmentId(10L);
        req.setRating(3);

        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(completedAppointment));
        when(reviewRepository.existsByAppointmentId(10L)).thenReturn(true);

        assertThatThrownBy(() -> reviewService.submitReview(req, principal))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("already reviewed");
    }

    @Test
    void submitReview_wrongPatient_throwsForbidden() {
        User otherUser = User.builder().id(99L).email("other@test.com").firstName("Other").lastName("User")
                .role(Role.ROLE_PATIENT).build();
        UserPrincipal other = UserPrincipal.fromUser(otherUser);

        ReviewRequest req = new ReviewRequest();
        req.setAppointmentId(10L);
        req.setRating(3);

        when(appointmentRepository.findByIdWithDetails(10L)).thenReturn(Optional.of(completedAppointment));

        assertThatThrownBy(() -> reviewService.submitReview(req, other))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("only review");
    }
}
