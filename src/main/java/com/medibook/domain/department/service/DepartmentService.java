package com.medibook.domain.department.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.department.dto.DepartmentRequest;
import com.medibook.domain.department.dto.DepartmentResponse;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;

    @Cacheable("departments")
    @Bulkhead(name = "doctorService")
    @Transactional(readOnly = true)
    public List<DepartmentResponse> getAll() {
        return departmentRepository.findAll().stream()
                .map(DepartmentResponse::fromEntity)
                .toList();
    }

    @Cacheable(value = "departments", key = "#id")
    @Transactional(readOnly = true)
    public DepartmentResponse getById(Long id) {
        return departmentRepository.findById(id)
                .map(DepartmentResponse::fromEntity)
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
    }

    @CacheEvict(value = "departments", allEntries = true)
    @Transactional
    public DepartmentResponse create(DepartmentRequest request) {
        if (departmentRepository.existsByNameIgnoreCase(request.getName())) {
            throw new MediBookException("Department already exists: " + request.getName(),
                    HttpStatus.CONFLICT, "DEPARTMENT_EXISTS");
        }
        Department dept = Department.builder()
                .name(request.getName())
                .description(request.getDescription())
                .build();
        return DepartmentResponse.fromEntity(departmentRepository.save(dept));
    }

    @CacheEvict(value = "departments", allEntries = true)
    @Transactional
    public DepartmentResponse update(Long id, DepartmentRequest request) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
        dept.setName(request.getName());
        dept.setDescription(request.getDescription());
        return DepartmentResponse.fromEntity(departmentRepository.save(dept));
    }

    @CacheEvict(value = "departments", allEntries = true)
    @Transactional
    public void delete(Long id) {
        if (!departmentRepository.existsById(id)) {
            throw new ResourceNotFoundException("Department", "id", id);
        }
        departmentRepository.deleteById(id);
    }
}
