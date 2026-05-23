package com.medibook.domain.patient.entity;

import com.medibook.common.audit.AuditableEntity;
import com.medibook.common.encryption.PhiAttributeConverter;
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

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "date_of_birth_enc", columnDefinition = "TEXT")
    private String dateOfBirthEnc;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "ssn_enc", columnDefinition = "TEXT")
    private String ssnEnc;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "blood_group", columnDefinition = "TEXT")
    private String bloodGroup;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "allergies_enc", columnDefinition = "TEXT")
    private String allergiesEnc;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "medical_history_enc", columnDefinition = "TEXT")
    private String medicalHistoryEnc;

    @Convert(converter = PhiAttributeConverter.class)
    @Column(name = "emergency_contact", columnDefinition = "TEXT")
    private String emergencyContact;
}
