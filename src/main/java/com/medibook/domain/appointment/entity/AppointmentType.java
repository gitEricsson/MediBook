package com.medibook.domain.appointment.entity;

/**
 * Consultation type — WHAT kind of visit, separate from HOW it's delivered (see ConsultationMedium).
 * EMERGENCY defaults the medium to the next available channel and bypasses fixed scheduling.
 */
public enum AppointmentType {
    IN_PERSON,
    TELEHEALTH,
    TELEMEDICINE,
    FIRST_VISIT,
    FOLLOW_UP,
    EMERGENCY
}
