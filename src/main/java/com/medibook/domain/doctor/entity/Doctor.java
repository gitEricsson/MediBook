package com.medibook.domain.doctor.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "doctors")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Doctor extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "department_id", nullable = false)
    private Department department;

    @Column(length = 150)
    private String specialization;

    @Column(name = "license_number", nullable = false, unique = true, length = 100)
    private String licenseNumber;

    @Column(columnDefinition = "TEXT")
    private String bio;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(length = 255)
    @Builder.Default
    private String languages = "English";

    @Column(name = "accepting_new", nullable = false)
    @Builder.Default
    private boolean acceptingNew = true;

    @Column(name = "slot_duration_mins", nullable = false)
    @Builder.Default
    private int slotDurationMins = 30;

    @Column(name = "search_vector", columnDefinition = "TEXT")
    private String searchVector;
}
