package com.medibook.domain.patient.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.domain.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "patient_profiles")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PatientProfile extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(name = "date_of_birth_enc", columnDefinition = "TEXT")
    private String dateOfBirthEnc;

    @Column(name = "ssn_enc", columnDefinition = "TEXT")
    private String ssnEnc;

    @Column(name = "blood_group", length = 10)
    private String bloodGroup;

    @Column(name = "allergies_enc", columnDefinition = "TEXT")
    private String allergiesEnc;

    @Column(name = "medical_history_enc", columnDefinition = "TEXT")
    private String medicalHistoryEnc;

    @Column(name = "emergency_contact")
    private String emergencyContact;
}
