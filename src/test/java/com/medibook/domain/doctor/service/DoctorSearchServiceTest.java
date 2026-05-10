package com.medibook.domain.doctor.service;

import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.appointment.service.AppointmentHoldService;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.dto.AvailabilityGridResponse;
import com.medibook.domain.doctor.dto.DoctorResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DoctorSearchService")
class DoctorSearchServiceTest {

    @Mock DoctorRepository doctorRepository;
    @Mock DoctorWorkingHoursRepository workingHoursRepository;
    @Mock AppointmentRepository appointmentRepository;
    @Mock AppointmentHoldService holdService;

    DoctorSearchService service;

    @BeforeEach
    void setUp() {
        service = new DoctorSearchService(doctorRepository, workingHoursRepository, appointmentRepository, holdService);
    }

    @Test
    void searchDoctorsReturnsEmptyWhenFullTextMatchesNothing() {
        when(doctorRepository.findIdsByFullText("cardio*")).thenReturn(List.of());

        Page<DoctorResponse> page = service.searchDoctors(" cardio ", null, null,
                null, null, null, PageRequest.of(0, 10));

        assertThat(page).isEmpty();
        verify(doctorRepository, never()).findAll(any(Specification.class), any(PageRequest.class));
    }

    @Test
    void searchDoctorsBuildsSpecificationAndMapsDoctorResponses() {
        Doctor doctor = doctor();
        when(doctorRepository.findIdsByFullText("cardio*")).thenReturn(List.of(doctor.getId()));
        when(doctorRepository.findAll(any(Specification.class), any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(doctor)));

        Page<DoctorResponse> page = service.searchDoctors("cardio", List.of(1L), List.of("Cardiology"),
                null, null, true, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().getFirst().getFullName()).isEqualTo("Ada Lovelace");
        assertThat(page.getContent().getFirst().getDepartmentName()).isEqualTo("Cardiology");
    }

    @Test
    void getDoctorByIdUsesDetailFetch() {
        when(doctorRepository.findByIdWithDetails(9L)).thenReturn(Optional.of(doctor()));

        DoctorResponse response = service.getDoctorById(9L);

        assertThat(response.getId()).isEqualTo(9L);
        verify(doctorRepository).findByIdWithDetails(9L);
    }

    @Test
    void getDoctorByIdThrowsWhenMissing() {
        when(doctorRepository.findByIdWithDetails(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getDoctorById(404L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAvailabilityMarksTakenHeldAndOpenSlotsUsingDoctorSlotDuration() {
        Doctor doctor = doctor();
        doctor.setSlotDurationMins(20);
        LocalDate date = LocalDate.of(2026, 6, 1);
        LocalDateTime ten = date.atTime(10, 0);
        LocalDateTime tenTwenty = date.atTime(10, 20);
        LocalDateTime tenForty = date.atTime(10, 40);
        when(doctorRepository.findById(9L)).thenReturn(Optional.of(doctor));
        when(workingHoursRepository.findByDoctorId(9L)).thenReturn(List.of(hours(doctor, 1, "10:00", "11:00")));
        when(appointmentRepository.findByDoctorIdAndScheduledAtBetweenOrderByScheduledAtAsc(
                eq(9L), eq(date.atStartOfDay()), eq(date.plusDays(1).atStartOfDay())))
                .thenReturn(List.of(
                        Appointment.builder()
                                .doctor(doctor)
                                .scheduledAt(ten)
                                .endTime(tenTwenty)
                                .status(AppointmentStatus.CONFIRMED)
                                .build(),
                        Appointment.builder()
                                .doctor(doctor)
                                .scheduledAt(tenForty)
                                .durationMins(20)
                                .status(AppointmentStatus.CANCELLED)
                                .build()));
        when(holdService.getHeldSlots(9L, List.of(ten, tenTwenty, tenForty))).thenReturn(Set.of(tenTwenty));

        AvailabilityGridResponse response = service.getAvailability(9L, date, date);

        assertThat(response.getDays()).hasSize(1);
        assertThat(response.getDays().getFirst().getSlots())
                .extracting(AvailabilityGridResponse.SlotInfo::getStatus)
                .containsExactly("TAKEN", "HELD", "OPEN");
        assertThat(response.getDays().getFirst().getSlots().getFirst().getEnd()).isEqualTo(tenTwenty);
    }

    @Test
    void getAvailabilityThrowsWhenDoctorDoesNotExist() {
        when(doctorRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAvailability(404L,
                LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 1)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static DoctorWorkingHours hours(Doctor doctor, int dayOfWeek, String start, String end) {
        return DoctorWorkingHours.builder()
                .doctor(doctor)
                .dayOfWeek(dayOfWeek)
                .startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end))
                .build();
    }

    private static Doctor doctor() {
        User user = User.builder()
                .id(7L)
                .firstName("Ada")
                .lastName("Lovelace")
                .email("ada@test.local")
                .password("secret")
                .role(Role.ROLE_DOCTOR)
                .isActive(true)
                .enabled(true)
                .build();
        Department department = Department.builder().id(1L).name("Cardiology").code("CARD").build();
        return Doctor.builder()
                .id(9L)
                .user(user)
                .department(department)
                .specialization("Cardiology")
                .licenseNumber("LIC-9")
                .languages("English")
                .acceptingNew(true)
                .slotDurationMins(30)
                .build();
    }
}
