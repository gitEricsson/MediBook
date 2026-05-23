package com.medibook.domain.department.service;

import com.medibook.common.exception.MediBookException;
import com.medibook.common.exception.ResourceNotFoundException;
import com.medibook.domain.department.dto.DepartmentAdminResponse;
import com.medibook.domain.department.dto.DepartmentRequest;
import com.medibook.domain.department.dto.DepartmentResponse;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import io.github.resilience4j.bulkhead.annotation.Bulkhead;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class DepartmentService {

    private final DepartmentRepository departmentRepository;

    @Cacheable("departments")
    @Bulkhead(name = "departmentService")
    @Transactional(readOnly = true)
    public List<DepartmentResponse> getAllActive() {
        return departmentRepository.findAll().stream()
                .filter(Department::isActive)
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


    @CircuitBreaker(name = "departmentAdminStats", fallbackMethod = "getAdminStatsFallback")
    @Bulkhead(name = "departmentAdminStats")
    @Transactional(readOnly = true)
    public Page<DepartmentAdminResponse> getAdminStats(String q, Boolean status, Pageable pageable) {
        LocalDateTime ninetyDaysAgo = LocalDateTime.now().minusDays(90);
        return departmentRepository.getAdminStats(q, status, ninetyDaysAgo, pageable);
    }

    public Page<DepartmentAdminResponse> getAdminStatsFallback(String q, Boolean status, Pageable pageable, Throwable t) {
        log.error("Circuit breaker opened for getAdminStats. Error: {}", t.getMessage());
        return Page.empty(pageable); // Graceful degradation
    }

    @CacheEvict(value = "departments", allEntries = true)
    @Transactional
    public DepartmentResponse create(DepartmentRequest request) {
        if (departmentRepository.existsByNameIgnoreCase(request.getName())) {
            throw new MediBookException("Department name already exists", HttpStatus.CONFLICT, "NAME_EXISTS");
        }
        if (departmentRepository.existsByCodeIgnoreCase(request.getCode())) {
            throw new MediBookException("Department code already exists", HttpStatus.CONFLICT, "CODE_EXISTS");
        }
        
        Department dept = Department.builder()
                .name(request.getName())
                .code(request.getCode().toUpperCase())
                .description(request.getDescription())
                .isActive(true)
                .slotDurationMins(request.getSlotDurationMins())
                .bufferMins(request.getBufferMins())
                .baseConsultationFee(request.getBaseConsultationFee())
                .build();
        return DepartmentResponse.fromEntity(departmentRepository.save(dept));
    }

    @CacheEvict(value = "departments", allEntries = true)
    @Transactional
    public DepartmentResponse update(Long id, DepartmentRequest request) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
                
        if (!dept.getName().equalsIgnoreCase(request.getName()) && 
            departmentRepository.existsByNameIgnoreCase(request.getName())) {
            throw new MediBookException("Department name already exists", HttpStatus.CONFLICT, "NAME_EXISTS");
        }
        if (!dept.getCode().equalsIgnoreCase(request.getCode()) && 
            departmentRepository.existsByCodeIgnoreCase(request.getCode())) {
            throw new MediBookException("Department code already exists", HttpStatus.CONFLICT, "CODE_EXISTS");
        }

        dept.setName(request.getName());
        dept.setCode(request.getCode().toUpperCase());
        dept.setDescription(request.getDescription());
        dept.setSlotDurationMins(request.getSlotDurationMins());
        dept.setBufferMins(request.getBufferMins());
        dept.setBaseConsultationFee(request.getBaseConsultationFee());
        return DepartmentResponse.fromEntity(departmentRepository.save(dept));
    }

    @CacheEvict(value = "departments", allEntries = true)
    @Transactional
    public void deactivate(Long id) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
        dept.setActive(false);
        departmentRepository.save(dept);
        log.info("Department deactivated: {}", id);
    }

    @CacheEvict(value = "departments", allEntries = true)
    @Transactional
    public void reactivate(Long id) {
        Department dept = departmentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Department", "id", id));
        dept.setActive(true);
        departmentRepository.save(dept);
        log.info("Department reactivated: {}", id);
    }

    @Transactional(readOnly = true)
    public List<DepartmentAdminResponse> getAllAdminStats() {
        LocalDateTime ninetyDaysAgo = LocalDateTime.now().minusDays(90);
        return departmentRepository.getAdminStats(null, null, ninetyDaysAgo, Pageable.unpaged()).getContent();
    }
}
