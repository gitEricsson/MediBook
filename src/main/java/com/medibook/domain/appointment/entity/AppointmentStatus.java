package com.medibook.domain.appointment.entity;

public enum AppointmentStatus {
    PENDING_PAYMENT,
    PENDING,
    CONFIRMED,
    CHECKED_IN,
    IN_WAITING_ROOM,
    IN_CONSULTATION,
    COMPLETED,
    CANCELLED,
    NO_SHOW,
    REFUNDED,
    EMERGENCY_PENDING_SETTLEMENT
}
