package com.medibook.domain.appointment.entity;

/**
 * HOW the consultation is delivered — physical presence, audio-only, or video.
 * Only AUDIO and VIDEO consultations can access telemedicine sessions.
 */
public enum ConsultationMedium {
    PHYSICAL,
    AUDIO,
    VIDEO
}
