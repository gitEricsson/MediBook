package com.medibook.domain.department.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.department.dto.DepartmentRequest;
import com.medibook.domain.department.dto.DepartmentResponse;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("DepartmentService — Unit Tests")
class DepartmentServiceTest {

    @Mock DepartmentRepository departmentRepository;

    @InjectMocks DepartmentService departmentService;

    private Department activeDept;
    private Department inactiveDept;

    @BeforeEach
    void setUp() {
        activeDept   = Department.builder().id(1L).name("Cardiology").code("CARD").isActive(true).build();
        inactiveDept = Department.builder().id(2L).name("Old Unit").code("OLD").isActive(false).build();
    }


    @Test
    @DisplayName("getAllActive — returns only isActive=true departments")
    void getAllActive_filtersInactiveDepartments() {
        when(departmentRepository.findAll()).thenReturn(List.of(activeDept, inactiveDept));

        List<DepartmentResponse> result = departmentService.getAllActive();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Cardiology");
    }

    @Test
    @DisplayName("getAllActive — returns empty list when all departments are inactive")
    void getAllActive_allInactive_returnsEmptyList() {
        when(departmentRepository.findAll()).thenReturn(List.of(inactiveDept));

        assertThat(departmentService.getAllActive()).isEmpty();
    }


    @Test
    @DisplayName("getById — existing department returns mapped response")
    void getById_existing_returnsResponse() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDept));

        DepartmentResponse response = departmentService.getById(1L);

        assertThat(response.getId()).isEqualTo(1L);
        assertThat(response.getName()).isEqualTo("Cardiology");
        assertThat(response.getCode()).isEqualTo("CARD");
    }

    @Test
    @DisplayName("getById — unknown ID throws NOT_FOUND")
    void getById_notFound_throwsNotFound() {
        when(departmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> departmentService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }


    @Test
    @DisplayName("create — success saves department with uppercased code")
    void create_success_codeIsUppercased() {
        DepartmentRequest req = buildRequest("Neurology", "neur", "Brain specialists");
        when(departmentRepository.existsByNameIgnoreCase("Neurology")).thenReturn(false);
        when(departmentRepository.existsByCodeIgnoreCase("neur")).thenReturn(false);
        when(departmentRepository.save(any())).thenReturn(activeDept);

        departmentService.create(req);

        ArgumentCaptor<Department> captor = ArgumentCaptor.forClass(Department.class);
        verify(departmentRepository).save(captor.capture());
        assertThat(captor.getValue().getCode()).isEqualTo("NEUR");
    }

    @Test
    @DisplayName("create — duplicate name throws 409 NAME_EXISTS")
    void create_duplicateName_throwsNameExists() {
        when(departmentRepository.existsByNameIgnoreCase("Cardiology")).thenReturn(true);

        assertThatThrownBy(() -> departmentService.create(buildRequest("Cardiology", "NEW", null)))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("NAME_EXISTS"));
        verify(departmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("create — duplicate code throws 409 CODE_EXISTS")
    void create_duplicateCode_throwsCodeExists() {
        when(departmentRepository.existsByNameIgnoreCase("New Dept")).thenReturn(false);
        when(departmentRepository.existsByCodeIgnoreCase("CARD")).thenReturn(true);

        assertThatThrownBy(() -> departmentService.create(buildRequest("New Dept", "CARD", null)))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("CODE_EXISTS"));
        verify(departmentRepository, never()).save(any());
    }


    @Test
    @DisplayName("update — success overwrites name, code (uppercased), and description")
    void update_success_updatesFields() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDept));
        when(departmentRepository.existsByNameIgnoreCase("Cardiology Updated")).thenReturn(false);
        when(departmentRepository.existsByCodeIgnoreCase("card2")).thenReturn(false); // service receives original case
        when(departmentRepository.save(any())).thenReturn(activeDept);

        departmentService.update(1L, buildRequest("Cardiology Updated", "card2", "New desc"));

        ArgumentCaptor<Department> captor = ArgumentCaptor.forClass(Department.class);
        verify(departmentRepository).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("Cardiology Updated");
        assertThat(captor.getValue().getCode()).isEqualTo("CARD2");
        assertThat(captor.getValue().getDescription()).isEqualTo("New desc");
    }

    @Test
    @DisplayName("update — keeping the same name does not trigger NAME_EXISTS check")
    void update_sameNameAsItself_doesNotConflict() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDept));
        when(departmentRepository.save(any())).thenReturn(activeDept);

        departmentService.update(1L, buildRequest("Cardiology", "CARD", null));

        verify(departmentRepository, never()).existsByNameIgnoreCase(any());
        verify(departmentRepository, never()).existsByCodeIgnoreCase(any());
    }

    @Test
    @DisplayName("update — keeping the same code does not trigger CODE_EXISTS check")
    void update_sameCodeAsItself_doesNotConflict() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDept));
        when(departmentRepository.existsByNameIgnoreCase("New Name")).thenReturn(false);
        when(departmentRepository.save(any())).thenReturn(activeDept);

        departmentService.update(1L, buildRequest("New Name", "CARD", null));

        verify(departmentRepository, never()).existsByCodeIgnoreCase(any());
    }

    @Test
    @DisplayName("update — changing name to existing one throws 409 NAME_EXISTS")
    void update_nameChangedToDuplicate_throwsConflict() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDept));
        when(departmentRepository.existsByNameIgnoreCase("Old Unit")).thenReturn(true);

        assertThatThrownBy(() -> departmentService.update(1L, buildRequest("Old Unit", "CARD", null)))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("NAME_EXISTS"));
        verify(departmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("update — changing code to existing one throws 409 CODE_EXISTS")
    void update_codeChangedToDuplicate_throwsConflict() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDept));
        when(departmentRepository.existsByCodeIgnoreCase("OLD")).thenReturn(true);

        assertThatThrownBy(() -> departmentService.update(1L, buildRequest("Cardiology", "OLD", null)))
                .isInstanceOf(MediBookException.class)
                .satisfies(ex -> assertThat(((MediBookException) ex).getErrorCode()).isEqualTo("CODE_EXISTS"));
        verify(departmentRepository, never()).save(any());
    }

    @Test
    @DisplayName("update — department not found throws NOT_FOUND")
    void update_notFound_throwsNotFound() {
        when(departmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> departmentService.update(99L, buildRequest("X", "Y", null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(departmentRepository, never()).save(any());
    }


    @Test
    @DisplayName("deactivate — sets isActive=false and saves")
    void deactivate_setsActiveFalse() {
        when(departmentRepository.findById(1L)).thenReturn(Optional.of(activeDept));

        departmentService.deactivate(1L);

        ArgumentCaptor<Department> captor = ArgumentCaptor.forClass(Department.class);
        verify(departmentRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isFalse();
    }

    @Test
    @DisplayName("deactivate — department not found throws NOT_FOUND")
    void deactivate_notFound_throwsNotFound() {
        when(departmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> departmentService.deactivate(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(departmentRepository, never()).save(any());
    }


    @Test
    @DisplayName("reactivate — sets isActive=true and saves")
    void reactivate_setsActiveTrue() {
        when(departmentRepository.findById(2L)).thenReturn(Optional.of(inactiveDept));

        departmentService.reactivate(2L);

        ArgumentCaptor<Department> captor = ArgumentCaptor.forClass(Department.class);
        verify(departmentRepository).save(captor.capture());
        assertThat(captor.getValue().isActive()).isTrue();
    }

    @Test
    @DisplayName("reactivate — department not found throws NOT_FOUND")
    void reactivate_notFound_throwsNotFound() {
        when(departmentRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> departmentService.reactivate(99L))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(departmentRepository, never()).save(any());
    }


    private DepartmentRequest buildRequest(String name, String code, String description) {
        DepartmentRequest req = new DepartmentRequest();
        req.setName(name);
        req.setCode(code);
        req.setDescription(description);
        return req;
    }
}
