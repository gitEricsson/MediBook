package com.medibook.domain.consultation.entity;

import com.medibook.common.audit.SoftDeleteEntity;
import com.medibook.common.encryption.PhiAttributeConverter;
import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.doctor.entity.Doctor;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.Where;

import java.time.LocalDate;

@Entity
@Table(name = "consultation_notes")
@SQLDelete(sql = "UPDATE consultation_notes SET deleted_at = CURRENT_TIMESTAMP, deleted_by = ?1 WHERE id = ?2")
@Where(clause = "deleted_at IS NULL")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ConsultationNote extends SoftDeleteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "appointment_id", nullable = false, unique = true)
    private Appointment appointment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "doctor_id")
    private Doctor doctor;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "diagnosis", columnDefinition = "MEDIUMTEXT")
    private String diagnosis;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "treatment_plan", columnDefinition = "MEDIUMTEXT")
    private String treatmentPlan;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(columnDefinition = "TEXT")
    private String prescriptions;

    @Column(name = "follow_up_date")
    private LocalDate followUpDate;

    @Column(name = "phi_version", nullable = false, length = 10)
    @Builder.Default
    private String phiVersion = "v1";
}
