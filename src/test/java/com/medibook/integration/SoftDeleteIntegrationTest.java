package com.medibook.integration;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.payment.entity.Invoice;
import com.medibook.domain.payment.entity.Payment;
import com.medibook.domain.payment.entity.PaymentProvider;
import com.medibook.domain.payment.entity.PaymentStatus;
import com.medibook.domain.payment.repository.InvoiceRepository;
import com.medibook.domain.payment.repository.PaymentRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
public class SoftDeleteIntegrationTest {

    @Autowired
    private AppointmentRepository appointmentRepository;

    @Autowired
    private ConsultationNoteRepository consultationNoteRepository;

    @Autowired
    private PaymentRepository paymentRepository;

    @Autowired
    private InvoiceRepository invoiceRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DoctorRepository doctorRepository;

    private User patient;
    private User doctor;
    private Doctor doctorEntity;
    private Appointment appointment;
    private Payment payment;

    @BeforeEach
    void setUp() {
        // Create test patient
        patient = User.builder()
                .email("patient@test.com")
                .firstName("Patient")
                .lastName("Test")
                .phone("+1234567890")
                .role(Role.ROLE_PATIENT)
                .isActive(true)
                .build();
        patient = userRepository.save(patient);

        // Create test doctor user
        doctor = User.builder()
                .email("doctor@test.com")
                .firstName("Doctor")
                .lastName("Test")
                .phone("+9876543210")
                .role(Role.ROLE_DOCTOR)
                .isActive(true)
                .build();
        doctor = userRepository.save(doctor);

        // Create doctor entity
        doctorEntity = Doctor.builder()
                .user(doctor)
                .specialization("Cardiology")
                .licenseNumber("LIC123")
                .yearsOfExperience(10)
                .isActive(true)
                .build();
        doctorEntity = doctorRepository.save(doctorEntity);

        // Create appointment
        appointment = Appointment.builder()
                .patient(patient)
                .doctor(doctorEntity)
                .scheduledAt(LocalDateTime.now().plusDays(1))
                .endTime(LocalDateTime.now().plusDays(1).plusMinutes(30))
                .durationMins(30)
                .status(AppointmentStatus.CONFIRMED)
                .type(AppointmentType.IN_PERSON)
                .reason("Checkup")
                .build();
        appointment = appointmentRepository.save(appointment);

        // Create payment
        payment = Payment.builder()
                .appointment(appointment)
                .patient(patient)
                .idempotencyKey("test-key-" + System.nanoTime())
                .provider(PaymentProvider.MONNIFY)
                .amount(BigDecimal.valueOf(5000))
                .currency("NGN")
                .status(PaymentStatus.SUCCESSFUL)
                .build();
        payment = paymentRepository.save(payment);
    }

    @Test
    void testAppointmentSoftDelete() {
        // Verify appointment exists
        Optional<Appointment> found = appointmentRepository.findById(appointment.getId());
        assertTrue(found.isPresent());

        // Soft delete
        appointment.softDelete(1L);
        appointmentRepository.save(appointment);

        // Verify soft deleted - normal query should not find it
        Optional<Appointment> afterDelete = appointmentRepository.findById(appointment.getId());
        assertFalse(afterDelete.isPresent());

        // Verify it can be found with include-deleted query
        Optional<Appointment> includeDeleted = appointmentRepository.findByIdIncludeDeleted(appointment.getId());
        assertTrue(includeDeleted.isPresent());
        assertTrue(includeDeleted.get().isDeleted());
        assertEquals(1L, includeDeleted.get().getDeletedBy());
        assertNotNull(includeDeleted.get().getDeletedAt());
    }

    @Test
    void testAppointmentRestore() {
        // Soft delete
        appointment.softDelete(1L);
        appointmentRepository.save(appointment);

        // Verify deleted
        Optional<Appointment> afterDelete = appointmentRepository.findById(appointment.getId());
        assertFalse(afterDelete.isPresent());

        // Restore
        appointment.restore();
        appointmentRepository.save(appointment);

        // Verify restored
        Optional<Appointment> restored = appointmentRepository.findById(appointment.getId());
        assertTrue(restored.isPresent());
        assertFalse(restored.get().isDeleted());
        assertNull(restored.get().getDeletedAt());
        assertNull(restored.get().getDeletedBy());
    }

    @Test
    void testConsultationNoteSoftDelete() {
        // Create consultation note
        ConsultationNote note = ConsultationNote.builder()
                .appointment(appointment)
                .doctor(doctorEntity)
                .diagnosis("High blood pressure")
                .treatmentPlan("Daily medication")
                .followUpDate(LocalDate.now().plusDays(30))
                .build();
        note = consultationNoteRepository.save(note);

        // Verify exists
        Optional<ConsultationNote> found = consultationNoteRepository.findById(note.getId());
        assertTrue(found.isPresent());

        // Soft delete
        note.softDelete(1L);
        consultationNoteRepository.save(note);

        // Verify soft deleted
        Optional<ConsultationNote> afterDelete = consultationNoteRepository.findById(note.getId());
        assertFalse(afterDelete.isPresent());

        // Verify include-deleted finds it
        Optional<ConsultationNote> includeDeleted = consultationNoteRepository.findByIdIncludeDeleted(note.getId());
        assertTrue(includeDeleted.isPresent());
        assertTrue(includeDeleted.get().isDeleted());
    }

    @Test
    void testPaymentSoftDelete() {
        // Verify payment exists
        Optional<Payment> found = paymentRepository.findById(payment.getId());
        assertTrue(found.isPresent());

        // Soft delete
        payment.softDelete(1L);
        paymentRepository.save(payment);

        // Verify soft deleted
        Optional<Payment> afterDelete = paymentRepository.findById(payment.getId());
        assertFalse(afterDelete.isPresent());

        // Verify include-deleted finds it
        Optional<Payment> includeDeleted = paymentRepository.findByIdIncludeDeleted(payment.getId());
        assertTrue(includeDeleted.isPresent());
        assertTrue(includeDeleted.get().isDeleted());
    }

    @Test
    void testInvoiceSoftDelete() {
        // Create invoice
        Invoice invoice = Invoice.builder()
                .payment(payment)
                .invoiceNumber("INV-001")
                .patient(patient)
                .doctor(doctorEntity)
                .subtotal(BigDecimal.valueOf(5000))
                .discount(BigDecimal.ZERO)
                .total(BigDecimal.valueOf(5000))
                .currency("NGN")
                .status("PAID")
                .dueDate(LocalDate.now().plusDays(7))
                .build();
        invoice = invoiceRepository.save(invoice);

        // Verify exists
        Optional<Invoice> found = invoiceRepository.findById(invoice.getId());
        assertTrue(found.isPresent());

        // Soft delete
        invoice.softDelete(1L);
        invoiceRepository.save(invoice);

        // Verify soft deleted
        Optional<Invoice> afterDelete = invoiceRepository.findById(invoice.getId());
        assertFalse(afterDelete.isPresent());

        // Verify include-deleted finds it
        Optional<Invoice> includeDeleted = invoiceRepository.findByIdIncludeDeleted(invoice.getId());
        assertTrue(includeDeleted.isPresent());
        assertTrue(includeDeleted.get().isDeleted());
    }

    @Test
    void testFindDeletedBetweenDateRange() {
        // Create and delete appointment
        appointment.softDelete(1L);
        appointmentRepository.save(appointment);

        LocalDateTime startRange = LocalDateTime.now().minusHours(1);
        LocalDateTime endRange = LocalDateTime.now().plusHours(1);

        // Find deleted in range
        List<Appointment> deleted = appointmentRepository.findDeletedBetween(startRange, endRange);
        assertFalse(deleted.isEmpty());
        assertTrue(deleted.stream().allMatch(Appointment::isDeleted));
    }

    @Test
    void testCountDeleted() {
        // Verify initial count is 0
        long initialCount = appointmentRepository.countDeleted();
        assertEquals(0, initialCount);

        // Delete appointment
        appointment.softDelete(1L);
        appointmentRepository.save(appointment);

        // Verify count increased
        long afterDelete = appointmentRepository.countDeleted();
        assertEquals(initialCount + 1, afterDelete);
    }

    @Test
    void testMultipleDeletedRecords() {
        // Create and delete multiple appointments
        Appointment apt2 = Appointment.builder()
                .patient(patient)
                .doctor(doctorEntity)
                .scheduledAt(LocalDateTime.now().plusDays(2))
                .endTime(LocalDateTime.now().plusDays(2).plusMinutes(30))
                .durationMins(30)
                .status(AppointmentStatus.CONFIRMED)
                .type(AppointmentType.IN_PERSON)
                .build();
        apt2 = appointmentRepository.save(apt2);

        // Delete both
        appointment.softDelete(1L);
        apt2.softDelete(2L);
        appointmentRepository.save(appointment);
        appointmentRepository.save(apt2);

        // Verify deletion stats
        assertEquals(2, appointmentRepository.countDeleted());

        // Verify normal queries don't return deleted
        assertEquals(0, appointmentRepository.findAll().size());
    }

    @Test
    void testDeletedAtAndDeletedByAreTracked() {
        Long adminId = 42L;
        appointment.softDelete(adminId);
        appointmentRepository.save(appointment);

        Optional<Appointment> found = appointmentRepository.findByIdIncludeDeleted(appointment.getId());
        assertTrue(found.isPresent());

        Appointment deleted = found.get();
        assertEquals(adminId, deleted.getDeletedBy());
        assertNotNull(deleted.getDeletedAt());
        assertTrue(deleted.getDeletedAt().isBefore(LocalDateTime.now().plusSeconds(1)));
        assertTrue(deleted.getDeletedAt().isAfter(LocalDateTime.now().minusSeconds(1)));
    }

    @Test
    void testAuditColumnsUnaffectedBySoftDelete() {
        LocalDateTime createdAtBefore = appointment.getCreatedAt();
        LocalDateTime updatedAtBefore = appointment.getUpdatedAt();

        appointment.softDelete(1L);
        appointmentRepository.save(appointment);

        Optional<Appointment> found = appointmentRepository.findByIdIncludeDeleted(appointment.getId());
        assertTrue(found.isPresent());

        Appointment deleted = found.get();
        // createdAt should not change
        assertEquals(createdAtBefore, deleted.getCreatedAt());
        // updatedAt may be updated by the database on save, but createdAt must not
        assertNotNull(deleted.getUpdatedAt());
    }
}
