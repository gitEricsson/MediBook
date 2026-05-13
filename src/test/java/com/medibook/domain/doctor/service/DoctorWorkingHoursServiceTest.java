package com.medibook.domain.doctor.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.doctor.dto.WorkingHoursRequest;
import com.medibook.domain.doctor.dto.WorkingHoursResponse;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.security.UserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DoctorWorkingHoursService")
class DoctorWorkingHoursServiceTest {

    @Mock DoctorRepository doctorRepository;
    @Mock DoctorWorkingHoursRepository workingHoursRepository;

    DoctorWorkingHoursService service;

    @BeforeEach
    void setUp() {
        service = new DoctorWorkingHoursService(doctorRepository, workingHoursRepository);
    }

    @Test
    void getByDoctorIdReturnsSortedSchedule() {
        Doctor doctor = doctor(10L);
        when(doctorRepository.existsById(10L)).thenReturn(true);
        when(workingHoursRepository.findByDoctorId(10L)).thenReturn(List.of(
                hours(doctor, 5, "09:00", "12:00"),
                hours(doctor, 1, "08:00", "11:00")));

        List<WorkingHoursResponse> response = service.getByDoctorId(10L);

        assertThat(response).extracting(WorkingHoursResponse::getDayOfWeek).containsExactly(1, 5);
    }

    @Test
    void getByDoctorIdThrowsWhenDoctorMissing() {
        when(doctorRepository.existsById(10L)).thenReturn(false);

        assertThatThrownBy(() -> service.getByDoctorId(10L))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void replaceAllDeletesOldRowsAndSavesNewRowsAtomically() {
        Doctor doctor = doctor(10L);
        DoctorWorkingHours oldHours = hours(doctor, 1, "09:00", "10:00");
        when(doctorRepository.findById(10L)).thenReturn(Optional.of(doctor));
        when(workingHoursRepository.findByDoctorId(10L)).thenReturn(List.of(oldHours));
        when(workingHoursRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<WorkingHoursResponse> response = service.replaceAll(10L, request(
                day(3, "10:00", "12:00"),
                day(1, "08:00", "11:00")));

        verify(workingHoursRepository).deleteAll(List.of(oldHours));
        ArgumentCaptor<List<DoctorWorkingHours>> captor = ArgumentCaptor.forClass(List.class);
        verify(workingHoursRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).extracting(DoctorWorkingHours::getDayOfWeek).containsExactly(3, 1);
        assertThat(response).extracting(WorkingHoursResponse::getDayOfWeek).containsExactly(1, 3);
    }

    @Test
    void replaceAllWithAdminPrincipalCanManageAnyDoctor() {
        Doctor doctor = doctor(10L);
        when(doctorRepository.findById(10L)).thenReturn(Optional.of(doctor));
        when(workingHoursRepository.findByDoctorId(10L)).thenReturn(List.of());
        when(workingHoursRepository.saveAll(anyList())).thenAnswer(invocation -> invocation.getArgument(0));

        List<WorkingHoursResponse> response = service.replaceAll(10L,
                request(day(2, "08:00", "09:00")), principal(99L, Role.ROLE_ADMIN));

        assertThat(response).singleElement()
                .extracting(WorkingHoursResponse::getDayOfWeek)
                .isEqualTo(2);
    }

    @Test
    void replaceAllWithDoctorPrincipalMustOwnDoctorProfile() {
        Doctor doctor = doctor(10L);
        when(doctorRepository.findById(10L)).thenReturn(Optional.of(doctor));

        assertThatThrownBy(() -> service.replaceAll(10L,
                request(day(2, "08:00", "09:00")), principal(999L, Role.ROLE_DOCTOR)))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Not authorized");

        verify(workingHoursRepository, never()).deleteAll(anyList());
        verify(workingHoursRepository, never()).saveAll(anyList());
    }

    @Test
    void duplicateDayIsRejected() {
        when(doctorRepository.findById(10L)).thenReturn(Optional.of(doctor(10L)));

        assertThatThrownBy(() -> service.replaceAll(10L,
                request(day(1, "08:00", "09:00"), day(1, "10:00", "11:00"))))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Duplicate day");
    }

    @Test
    void invalidTimeRangeIsRejected() {
        when(doctorRepository.findById(10L)).thenReturn(Optional.of(doctor(10L)));

        assertThatThrownBy(() -> service.replaceAll(10L, request(day(1, "11:00", "09:00"))))
                .isInstanceOf(MediBookException.class)
                .hasMessageContaining("Start time");
    }

    private static WorkingHoursRequest request(WorkingHoursRequest.DaySchedule... days) {
        WorkingHoursRequest request = new WorkingHoursRequest();
        request.setSchedule(List.of(days));
        return request;
    }

    private static WorkingHoursRequest.DaySchedule day(int dayOfWeek, String start, String end) {
        WorkingHoursRequest.DaySchedule day = new WorkingHoursRequest.DaySchedule();
        day.setDayOfWeek(dayOfWeek);
        day.setStartTime(LocalTime.parse(start));
        day.setEndTime(LocalTime.parse(end));
        return day;
    }

    private static DoctorWorkingHours hours(Doctor doctor, int dayOfWeek, String start, String end) {
        return DoctorWorkingHours.builder()
                .doctor(doctor)
                .dayOfWeek(dayOfWeek)
                .startTime(LocalTime.parse(start))
                .endTime(LocalTime.parse(end))
                .build();
    }

    private static Doctor doctor(Long doctorId) {
        User user = User.builder()
                .id(7L)
                .email("doctor@test.local")
                .password("secret")
                .firstName("Grace")
                .lastName("Hopper")
                .role(Role.ROLE_DOCTOR)
                .isActive(true)
                .enabled(true)
                .build();
        return Doctor.builder()
                .id(doctorId)
                .user(user)
                .department(Department.builder().id(1L).name("General").build())
                .licenseNumber("LIC-" + doctorId)
                .build();
    }

    private static UserPrincipal principal(Long userId, Role role) {
        return UserPrincipal.fromUser(User.builder()
                .id(userId)
                .email("principal@test.local")
                .password("secret")
                .firstName("Principal")
                .lastName("User")
                .role(role)
                .isActive(true)
                .enabled(true)
                .build());
    }
}
