package com.medibook.domain.department.entity;

import com.medibook.common.audit.AuditableEntity;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "departments")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Department extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 150)
    private String name;

    @Column(nullable = false, unique = true, length = 50)
    private String code;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_active")
    @Builder.Default
    private boolean isActive = true;

    @Column(name = "base_consultation_fee", nullable = false, precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal baseConsultationFee = BigDecimal.valueOf(5000.00);

    /** Default slot duration for all doctors in this department (minutes). Doctor-level override takes precedence. */
    @Column(name = "slot_duration_mins", nullable = false)
    @Builder.Default
    private int slotDurationMins = 30;

    /** Buffer between consecutive slots for cleaning/preparation (minutes). */
    @Column(name = "buffer_mins", nullable = false)
    @Builder.Default
    private int bufferMins = 0;

    @Version
    @Builder.Default
    private Long version = 0L;
}
