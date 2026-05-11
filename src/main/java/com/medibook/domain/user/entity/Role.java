package com.medibook.domain.user.entity;

/**
 * System roles — stored as the Spring Security authority name.
 */
public enum Role {
    ROLE_PATIENT,
    ROLE_DOCTOR,
    ROLE_ADMIN,
    ROLE_SUPER_ADMIN
}
