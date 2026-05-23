package com.medibook.domain.doctor.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

import java.util.List;

/**
 * Request body for admin-provisioned doctor creation.
 * Creates both a User account (sent invitation email) and Doctor profile in one call.
 */
@Data
public class AdminCreateDoctorRequest {

    @NotBlank(message = "First name is required")
    @Size(max = 100)
    private String firstName;

    @NotBlank(message = "Last name is required")
    @Size(max = 100)
    private String lastName;

    @NotBlank(message = "Email is required")
    @Email(message = "Must be a valid email address")
    @Size(max = 255)
    private String email;

    @Size(max = 20)
    private String phone;

    @NotNull(message = "Department ID is required")
    private Long departmentId;

    /** Additional departments this doctor is cross-listed in. */
    private List<Long> additionalDepartmentIds;

    @Size(max = 150)
    private String specialization;

    /** All specializations; any beyond the primary go here. */
    private List<@Size(max = 150) String> specializations;

    @Size(max = 100)
    private String licenseNumber;

    @Size(max = 2000)
    private String bio;

    /** e.g. "9:00 AM" — stored as metadata, used to seed default working hours */
    private String defaultStartTime;

    /** e.g. "5:00 PM" — stored as metadata */
    private String defaultEndTime;
}
