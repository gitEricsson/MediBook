package com.medibook.seed;

import com.medibook.domain.appointment.entity.Appointment;
import com.medibook.domain.appointment.entity.AppointmentStatus;
import com.medibook.domain.appointment.entity.AppointmentType;
import com.medibook.domain.appointment.repository.AppointmentRepository;
import com.medibook.domain.consultation.entity.ConsultationNote;
import com.medibook.domain.consultation.repository.ConsultationNoteRepository;
import com.medibook.domain.department.entity.Department;
import com.medibook.domain.department.repository.DepartmentRepository;
import com.medibook.domain.doctor.entity.Doctor;
import com.medibook.domain.doctor.entity.DoctorWorkingHours;
import com.medibook.domain.doctor.repository.DoctorRepository;
import com.medibook.domain.doctor.repository.DoctorWorkingHoursRepository;
import com.medibook.domain.notification.service.NotificationService;
import com.medibook.domain.patient.entity.PatientProfile;
import com.medibook.domain.patient.repository.PatientProfileRepository;
import com.medibook.domain.payment.entity.Invoice;
import com.medibook.domain.payment.entity.InvoiceLineItem;
import com.medibook.domain.payment.entity.Payment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.medibook.domain.payment.entity.PaymentProvider;
import com.medibook.domain.payment.entity.PaymentStatus;
import com.medibook.domain.payment.repository.InvoiceRepository;
import com.medibook.domain.payment.repository.PaymentRepository;
import com.medibook.domain.review.entity.DoctorReview;
import com.medibook.domain.review.repository.DoctorReviewRepository;
import com.medibook.domain.schedule.entity.DoctorLeave;
import com.medibook.domain.schedule.entity.NoteTemplate;
import com.medibook.domain.schedule.repository.DoctorLeaveRepository;
import com.medibook.domain.schedule.repository.NoteTemplateRepository;
import com.medibook.domain.user.entity.Role;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.domain.waitlist.entity.WaitlistEntry;
import com.medibook.domain.waitlist.repository.WaitlistRepository;
import com.medibook.messaging.event.AppointmentEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Random;
import java.util.Collections;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Component
@Order(10)
@Profile("!prod")
@RequiredArgsConstructor
public class SeedDataRunner implements ApplicationRunner {

    private static final String  DEMO_PASSWORD = "Password123!";
    private static final LocalDate TODAY        = LocalDate.now();
    private static final Random RANDOM         = new Random();

    // ── Repositories ────────────────────────────────────────────────────────
    private final SeedDataProperties          seedProps;
    private final UserRepository              userRepo;
    private final DepartmentRepository        deptRepo;
    private final DoctorRepository            doctorRepo;
    private final DoctorWorkingHoursRepository hoursRepo;
    private final AppointmentRepository       apptRepo;
    private final ConsultationNoteRepository  noteRepo;
    private final PatientProfileRepository    profileRepo;
    private final DoctorReviewRepository      reviewRepo;
    private final PaymentRepository           paymentRepo;
    private final InvoiceRepository           invoiceRepo;
    private final DoctorLeaveRepository       leaveRepo;
    private final NoteTemplateRepository      templateRepo;
    private final WaitlistRepository          waitlistRepo;
    private final NotificationService         notificationService;
    private final PasswordEncoder             passwordEncoder;

    // ══════════════════════════════════════════════════════════════════════════
    // Static seed data specs
    // ══════════════════════════════════════════════════════════════════════════

    private record DeptSpec(String name, String code, String desc, BigDecimal fee) {}

    private static final List<DeptSpec> DEPT_SPECS = List.of(
        new DeptSpec("Cardiology",       "CARD", "Heart and cardiovascular disease management",          new BigDecimal("15000.00")),
        new DeptSpec("Dermatology",      "DERM", "Skin, hair, and nail conditions",                     new BigDecimal("8000.00")),
        new DeptSpec("Pediatrics",       "PEDS", "Medical care for infants, children, and adolescents", new BigDecimal("7500.00")),
        new DeptSpec("Neurology",        "NEUR", "Brain and nervous system disorders",                  new BigDecimal("12000.00")),
        new DeptSpec("General Medicine", "GMED", "Primary and preventive healthcare",                   new BigDecimal("5000.00")),
        new DeptSpec("Orthopedics",      "ORTH", "Bone, joint, and musculoskeletal conditions",         new BigDecimal("10000.00")),
        new DeptSpec("Emergency",        "EMRG", "Acute and emergency medical care",                    new BigDecimal("20000.00"))
    );

    private record DoctorSpec(
        String email, String firstName, String lastName, String phone,
        String deptName, String specialization, String license,
        String bio, int years, BigDecimal fee, String gender, boolean telemedicine
    ) {}

    private static final List<DoctorSpec> DOCTOR_SPECS = List.of(
        new DoctorSpec(
            "dr.chukwuemeka@medibook.local", "Chukwuemeka", "Obiora", "+2348012345001",
            "Cardiology", "Interventional Cardiology", "LIC-CARD-001",
            "Senior cardiologist with 15 years of experience in interventional procedures and heart failure management. Fellow of the Nigerian Cardiac Society.",
            15, new BigDecimal("18000.00"), "Male", true
        ),
        new DoctorSpec(
            "dr.aisha@medibook.local", "Aisha", "Mohammed", "+2348012345002",
            "Dermatology", "Clinical Dermatology", "LIC-DERM-002",
            "Specialist in inflammatory skin disorders, cosmetic dermatology, and paediatric skin conditions. MSc Dermatology, University of Lagos.",
            8, new BigDecimal("10000.00"), "Female", false
        ),
        new DoctorSpec(
            "dr.adaeze@medibook.local", "Adaeze", "Nwosu", "+2348012345003",
            "Pediatrics", "General Pediatrics", "LIC-PEDS-003",
            "Compassionate paediatrician dedicated to children's health from newborns through adolescence. Special interest in developmental and nutritional disorders.",
            12, new BigDecimal("8500.00"), "Female", true
        ),
        new DoctorSpec(
            "dr.ibrahim@medibook.local", "Ibrahim", "Aliyu", "+2348012345004",
            "Neurology", "Neurology", "LIC-NEUR-004",
            "Consultant neurologist specialising in epilepsy, stroke, and neurodegenerative disorders. 20 years of academic and clinical neurology practice.",
            20, new BigDecimal("15000.00"), "Male", false
        ),
        new DoctorSpec(
            "dr.taiwo@medibook.local", "Taiwo", "Ogunleye", "+2348012345005",
            "General Medicine", "Family Medicine", "LIC-GMED-005",
            "Family physician providing holistic primary care and chronic disease management. Certified in preventive medicine and lifestyle medicine.",
            6, new BigDecimal("6000.00"), "Female", true
        )
    );

    // ══════════════════════════════════════════════════════════════════════════
    // Data Pools for random generation
    // ══════════════════════════════════════════════════════════════════════════

    private static final List<String> FIRST_NAMES = List.of(
        "James", "Fatima", "Chidi", "Amara", "Tunde", "Ngozi", "Emeka", "Sade", "Kola", "Chidinma",
        "Olumide", "Ifeanyi", "Zainab", "Abubakar", "Ebele", "Femi", "Yinka", "Blessing", "Joy", "Grace",
        "Samuel", "David", "Elizabeth", "Mary", "Joseph", "Daniel", "Ruth", "Sarah", "Isaac", "Joshua"
    );

    private static final List<String> LAST_NAMES = List.of(
        "Okafor", "Bello", "Obi", "Eze", "Adeyemi", "Nwachukwu", "Ogbonna", "Adesanya", "Fashola", "Uche",
        "Abiola", "Okonkwo", "Danjuma", "Suleiman", "Nwosu", "Ogunleye", "Balogun", "Oni", "Alabi", "Popoola"
    );

    private static final List<String> REASONS = List.of(
        "Annual check-up", "Follow-up visit", "Chest pain", "Fever and cough", "Headache",
        "Skin rash", "Routine vaccination", "Medication review", "Wellness screening", "Abdominal pain",
        "Joint pain", "Vision problems", "Back pain", "Fatigue", "Sleep issues", "Allergy evaluation"
    );

    private static final List<String> DIAGNOSES = List.of(
        "Essential hypertension", "Type 2 diabetes mellitus", "Acute upper respiratory infection",
        "Acute pharyngitis", "Gastro-oesophageal reflux disease", "Hyperlipidaemia", "Osteoarthritis",
        "Atopic dermatitis", "Iron deficiency anaemia", "Vitamin D deficiency", "Asthma",
        "Migraine", "Lower back pain", "Urinary tract infection", "Generalized anxiety disorder"
    );

    private static final List<String> TREATMENTS = List.of(
        "Prescribed medication and advised lifestyle changes.",
        "Recommended daily exercise and a balanced diet.",
        "Ordered blood tests for further evaluation.",
        "Advised rest and increased fluid intake.",
        "Scheduled a follow-up appointment in two weeks.",
        "Referred to a specialist for further investigation.",
        "Adjusted current medication dosage.",
        "Educated patient on self-management techniques."
    );

    private static final List<String> PRESCRIPTIONS = List.of(
        "Paracetamol 500mg BD x 5 days", "Metformin 500mg OD", "Amlodipine 5mg OD",
        "Amoxicillin 500mg TDS x 7 days", "Atorvastatin 10mg ON", "Lisinopril 10mg OD",
        "Omeprazole 20mg OD", "Salbutamol Inhaler PRN", "Cetirizine 10mg OD"
    );

    private static final List<String> REVIEW_COMMENTS = List.of(
        "Very professional and attentive doctor.", "The consultation was thorough and helpful.",
        "Wait time was short, doctor was excellent.", "Highly recommend this doctor and clinic.",
        "Excellent bedside manner and clear communication.", "Took the time to answer all my questions.",
        "Professional staff and knowledgeable doctor.", "Great experience, very satisfied with the care."
    );

    // ══════════════════════════════════════════════════════════════════════════
    // Entry point
    // ══════════════════════════════════════════════════════════════════════════

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedProps.isEnabled()) {
            log.info("[Seed] Disabled — set app.seed.enabled=true to activate");
            return;
        }

        log.info("[Seed] ══════════════════════════════════════════════════");
        log.info("[Seed]   MediBook demo data seeding started");
        log.info("[Seed] ══════════════════════════════════════════════════");
        long t0 = System.currentTimeMillis();

        Map<String, Department> depts   = seedDepartments();
        List<User>              admins  = seedAdmins();
        List<User>              patients = seedPatients();
        List<Doctor>            doctors  = seedDoctors(depts);
        seedWorkingHours(doctors);
        seedNoteTemplates(doctors);
        List<Appointment> appointments  = seedAppointments(patients, doctors, depts);
        seedConsultationNotes(appointments, doctors);
        seedReviews(appointments);
        seedPayments(appointments);
        seedDoctorLeaves(doctors, admins);
        seedWaitlistEntries(patients, doctors, depts);
        seedNotifications(appointments);   // Cassandra — individual try-catch inside

        log.info("[Seed] ══════════════════════════════════════════════════");
        log.info("[Seed]   Complete in {} ms", System.currentTimeMillis() - t0);
        log.info("[Seed]   Demo password for all seeded accounts: '{}'", DEMO_PASSWORD);
        log.info("[Seed]   Super admin : superadmin@medibook.local");
        log.info("[Seed]   Admin ops   : admin.ops@medibook.local");
        log.info("[Seed]   Doctor      : dr.chukwuemeka@medibook.local");
        log.info("[Seed]   Patient     : patient.james@medibook.local");
        log.info("[Seed] ══════════════════════════════════════════════════");
    }

    // ══════════════════════════════════════════════════════════════════════════
    // Seed methods
    // ══════════════════════════════════════════════════════════════════════════

    private Map<String, Department> seedDepartments() {
        Map<String, Department> result = new HashMap<>();
        int created = 0;
        for (DeptSpec s : DEPT_SPECS) {
            boolean isNew = !deptRepo.existsByNameIgnoreCase(s.name());
            Department d = deptRepo.findByNameIgnoreCase(s.name()).orElseGet(() -> {
                Department nd = Department.builder()
                    .name(s.name())
                    .code(s.code())
                    .description(s.desc())
                    .baseConsultationFee(s.fee())
                    .isActive(true)
                    .build();
                return deptRepo.save(nd);
            });
            result.put(s.name(), d);
            if (isNew) created++;
        }
        log.info("[Seed] Departments ready ({} total, {} created)", DEPT_SPECS.size(), created);
        return result;
    }

    private List<User> seedAdmins() {
        record AdminSpec(String email, String first, String last, String phone) {}
        List<AdminSpec> specs = List.of(
            new AdminSpec("admin.ops@medibook.local",     "Amaka",  "Okonkwo", "+2348021000001"),
            new AdminSpec("admin.reports@medibook.local", "Bola",   "Adeleke", "+2348021000002")
        );
        List<User> admins = new ArrayList<>();
        for (AdminSpec s : specs) {
            admins.add(findOrCreateUser(s.email(), s.first(), s.last(), s.phone(), null, Role.ROLE_ADMIN));
        }
        log.info("[Seed] Admins ready ({})", admins.size());
        return admins;
    }

    private List<User> seedPatients() {
        List<User> patients = new ArrayList<>();
        // Create 50 patients
        for (int i = 0; i < 50; i++) {
            String firstName = getRandomElement(FIRST_NAMES);
            String lastName  = getRandomElement(LAST_NAMES);
            String email     = "patient." + firstName.toLowerCase() + "." + i + "@medibook.local";
            String phone     = "+234700" + String.format("%07d", i);
            LocalDate dob    = LocalDate.now().minusYears(20 + RANDOM.nextInt(40)).minusDays(RANDOM.nextInt(365));
            String bloodGroup = getRandomElement(List.of("A+", "B+", "O+", "AB+", "A-", "B-", "O-", "AB-"));

            User u = findOrCreateUser(email, firstName, lastName, phone, dob, Role.ROLE_PATIENT);
            patients.add(u);

            if (profileRepo.findByUserId(u.getId()).isEmpty()) {
                PatientProfile p = PatientProfile.builder()
                    .user(u)
                    .bloodGroup(bloodGroup)
                    .dateOfBirthEnc(dob.toString())
                    .emergencyContact("Family Contact: " + firstName + " Next-of-Kin")
                    .medicalHistoryEnc("No significant past medical history")
                    .allergiesEnc("NKDA")
                    .build();
                profileRepo.save(p);
            }
        }
        log.info("[Seed] Patients ready ({})", patients.size());
        return patients;
    }

    private List<Doctor> seedDoctors(Map<String, Department> depts) {
        List<Doctor> doctors = new ArrayList<>();
        for (DoctorSpec s : DOCTOR_SPECS) {
            if (doctorRepo.existsByLicenseNumber(s.license())) {
                doctorRepo.findByLicenseNumber(s.license()).ifPresent(doctors::add);
                continue;
            }
            User u = findOrCreateUser(s.email(), s.firstName(), s.lastName(), s.phone(), null, Role.ROLE_DOCTOR);
            Department dept = depts.get(s.deptName());
            String searchVector = s.firstName() + " " + s.lastName() + " " + s.specialization() + " " + s.deptName();
            Doctor d = Doctor.builder()
                .user(u)
                .department(dept)
                .specialization(s.specialization())
                .licenseNumber(s.license())
                .bio(s.bio())
                .isActive(true)
                .languages("English, Hausa, Yoruba, Igbo")
                .acceptingNew(true)
                .slotDurationMins(30)
                .yearsOfExperience(s.years())
                .consultationFee(s.fee())
                .gender(s.gender())
                .telemedicineEnabled(s.telemedicine())
                .averageRating(0.0)
                .reviewCount(0)
                .searchVector(searchVector)
                .build();
            doctors.add(doctorRepo.save(d));
        }
        log.info("[Seed] Doctors ready ({})", doctors.size());
        return doctors;
    }

    private void seedWorkingHours(List<Doctor> doctors) {
        // 1=Mon, 2=Tue, 3=Wed, 4=Thu, 5=Fri
        int created = 0;
        for (int i = 0; i < doctors.size(); i++) {
            Doctor d = doctors.get(i);
            if (!hoursRepo.findByDoctorId(d.getId()).isEmpty()) continue;

            LocalTime start = switch (i) {
                case 1  -> LocalTime.of(10, 0);  // Dermatology: 10:00–18:00
                case 4  -> LocalTime.of(8,  0);  // Gen Med: 08:00–16:00
                default -> LocalTime.of(9,  0);  // All others: 09:00–17:00
            };
            LocalTime end = switch (i) {
                case 1  -> LocalTime.of(18, 0);
                case 4  -> LocalTime.of(16, 0);
                default -> LocalTime.of(17, 0);
            };

            for (int day = 1; day <= 5; day++) {
                hoursRepo.save(DoctorWorkingHours.builder()
                    .doctor(d)
                    .dayOfWeek(day)
                    .startTime(start)
                    .endTime(end)
                    .build());
                created++;
            }
        }
        log.info("[Seed] Working hours ready ({} rows)", created);
    }

    private void seedNoteTemplates(List<Doctor> doctors) {
        if (templateRepo.findByTemplateTypeAndIsActive("SOAP", true).isEmpty()) {
            templateRepo.save(NoteTemplate.builder()
                .name("Standard SOAP Note")
                .templateType("SOAP")
                .content("**Subjective:** [Chief complaint and history]\n\n**Objective:** [Examination findings, vitals]\n\n**Assessment:** [Diagnosis / differential]\n\n**Plan:** [Treatment, investigations, follow-up]")
                .isActive(true)
                .build());
        }
        if (templateRepo.findByTemplateTypeAndIsActive("FOLLOW_UP", true).isEmpty()) {
            templateRepo.save(NoteTemplate.builder()
                .name("Standard Follow-Up Note")
                .templateType("FOLLOW_UP")
                .content("**Interval History:** [Changes since last visit]\n\n**Examination:** [Relevant findings today]\n\n**Progress:** [Better / Same / Worse]\n\n**Plan:** [Continue / modify treatment]")
                .isActive(true)
                .build());
        }
        // Doctor-specific templates
        if (!doctors.isEmpty()) {
            Doctor cardiologist = doctors.get(0);
            if (templateRepo.findAvailableForDoctor(cardiologist.getId()).stream()
                    .noneMatch(t -> "Cardiac Assessment".equals(t.getName()))) {
                templateRepo.save(NoteTemplate.builder()
                    .name("Cardiac Assessment")
                    .templateType("CARDIAC")
                    .content("**Cardiac History:** [Symptoms, risk factors]\n\n**Vitals:** BP: / HR: Rhythm: \n\n**Examination:** [Heart sounds, JVP, oedema]\n\n**ECG findings:**\n\n**Echocardiogram:**\n\n**Plan:**")
                    .doctor(cardiologist)
                    .isActive(true)
                    .build());
            }
        }
        if (doctors.size() > 2) {
            Doctor paediatrician = doctors.get(2);
            if (templateRepo.findAvailableForDoctor(paediatrician.getId()).stream()
                    .noneMatch(t -> "Paediatric Wellness Check".equals(t.getName()))) {
                templateRepo.save(NoteTemplate.builder()
                    .name("Paediatric Wellness Check")
                    .templateType("WELLNESS")
                    .content("**Age / Weight / Height / HC:**\n\n**Growth percentile:**\n\n**Developmental milestones:** [Age-appropriate Y/N]\n\n**Immunisation status:**\n\n**Parental concerns:**\n\n**Plan:**")
                    .doctor(paediatrician)
                    .isActive(true)
                    .build());
            }
        }
        log.info("[Seed] Note templates ready");
    }

    private List<Appointment> seedAppointments(List<User> patients, List<Doctor> doctors,
                                                Map<String, Department> depts) {
        List<Appointment> saved = new ArrayList<>();
        int created = 0;

        // Generate appointments for the last 24 weeks and next 4 weeks
        for (Doctor doctor : doctors) {
            for (int week = -24; week <= 4; week++) {
                // 3-5 appointments per week
                int apptsThisWeek = 3 + RANDOM.nextInt(3);
                for (int i = 0; i < apptsThisWeek; i++) {
                    User patient = getRandomElement(patients);
                    int dayOffset = week * 7 + RANDOM.nextInt(5); // Mon-Fri
                    int hour = 9 + RANDOM.nextInt(7); // 9am - 4pm

                    LocalDateTime scheduledAt = TODAY.plusDays(dayOffset).atTime(hour, 0);
                    String code = "SEED-" + doctor.getId() + "-" + scheduledAt.hashCode();

                    if (apptRepo.existsByConfirmationCode(code)) continue;

                    AppointmentStatus status;
                    if (scheduledAt.isBefore(LocalDateTime.now())) {
                        // Historical status distribution: 80% Completed, 10% Cancelled, 10% No-show
                        int r = RANDOM.nextInt(100);
                        if (r < 80) status = AppointmentStatus.COMPLETED;
                        else if (r < 90) status = AppointmentStatus.CANCELLED;
                        else status = AppointmentStatus.NO_SHOW;
                    } else {
                        // Future status distribution: 80% Confirmed, 20% Pending
                        status = RANDOM.nextInt(100) < 80 ? AppointmentStatus.CONFIRMED : AppointmentStatus.PENDING;
                    }

                    Appointment appt = Appointment.builder()
                        .patient(patient)
                        .doctor(doctor)
                        .department(doctor.getDepartment())
                        .scheduledAt(scheduledAt)
                        .endTime(scheduledAt.plusMinutes(30))
                        .durationMins(30)
                        .status(status)
                        .type(RANDOM.nextBoolean() ? AppointmentType.IN_PERSON : AppointmentType.TELEHEALTH)
                        .reason(getRandomElement(REASONS))
                        .confirmationCode(code)
                        .build();

                    if (status == AppointmentStatus.CANCELLED) {
                        appt.setCancelledAt(scheduledAt.minusDays(1));
                        appt.setCancelledBy(patient);
                        appt.setCancellationReason("Scheduling conflict");
                    }

                    saved.add(apptRepo.save(appt));
                    created++;
                }
            }
        }

        log.info("[Seed] Appointments ready ({} created)", created);
        return saved;
    }

    private void seedConsultationNotes(List<Appointment> appointments, List<Doctor> doctors) {
        int created = 0;
        for (Appointment appt : appointments) {
            if (appt.getStatus() != AppointmentStatus.COMPLETED) continue;
            if (noteRepo.findByAppointmentId(appt.getId()).isPresent()) continue;

            ConsultationNote note = ConsultationNote.builder()
                .appointment(appt)
                .doctor(appt.getDoctor())
                .diagnosis(getRandomElement(DIAGNOSES))
                .treatmentPlan(getRandomElement(TREATMENTS))
                .prescriptions(getRandomElement(PRESCRIPTIONS))
                .followUpDate(appt.getScheduledAt().plusDays(RANDOM.nextInt(90)).toLocalDate())
                .phiVersion("v1")
                .build();
            noteRepo.save(note);
            created++;
        }
        log.info("[Seed] Consultation notes ready ({} created)", created);
    }

    private void seedReviews(List<Appointment> appointments) {
        int created = 0;
        for (Appointment appt : appointments) {
            if (appt.getStatus() != AppointmentStatus.COMPLETED) continue;
            if (RANDOM.nextInt(100) > 40) continue; // 40% review rate
            if (reviewRepo.existsByAppointmentId(appt.getId())) continue;

            DoctorReview review = DoctorReview.builder()
                .appointment(appt)
                .patient(appt.getPatient())
                .doctor(appt.getDoctor())
                .rating((byte) (3 + RANDOM.nextInt(3))) // 3-5 rating
                .comment(getRandomElement(REVIEW_COMMENTS))
                .status("APPROVED")
                .moderatedAt(appt.getScheduledAt().plusDays(1))
                .build();
            reviewRepo.save(review);
            created++;

            // Sync doctor's average rating
            Doctor doc = appt.getDoctor();
            long approvedCount = reviewRepo.countApprovedByDoctorId(doc.getId());
            double avg = reviewRepo.findAverageRatingByDoctorId(doc.getId()).orElse(0.0);
            doc.setAverageRating(avg);
            doc.setReviewCount((int) approvedCount);
            doctorRepo.save(doc);
        }
        log.info("[Seed] Reviews ready ({} created)", created);
    }

    private void seedPayments(List<Appointment> appointments) {
        int created = 0;
        // One SUCCESSFUL payment per COMPLETED appointment
        List<Appointment> completed = appointments.stream()
            .filter(a -> a.getStatus() == AppointmentStatus.COMPLETED)
            .toList();

        for (Appointment appt : completed) {
            String idemKey = "SEED-PAY-" + appt.getConfirmationCode();
            if (paymentRepo.findByIdempotencyKey(idemKey).isPresent()) continue;

            BigDecimal amount = appt.getDoctor().getEffectiveConsultationFee();

            Payment payment = Payment.builder()
                .appointment(appt)
                .patient(appt.getPatient())
                .idempotencyKey(idemKey)
                .provider(PaymentProvider.PAYSTACK)
                .providerRef("PS-SEED-" + appt.getConfirmationCode())
                .amount(amount)
                .currency("NGN")
                .status(PaymentStatus.SUCCESSFUL)
                .build();
            Payment saved = paymentRepo.save(payment);
            created++;

            // Invoice
            String invoiceNumber = "INV-SEED-" + appt.getConfirmationCode();
            if (invoiceRepo.findByInvoiceNumber(invoiceNumber).isEmpty()) {
                InvoiceLineItem lineItem = InvoiceLineItem.builder()
                    .description("Consultation fee — " + appt.getDoctor().getSpecialization())
                    .quantity(1)
                    .unitPrice(amount)
                    .subtotal(amount)
                    .build();

                Invoice invoice = Invoice.builder()
                    .payment(saved)
                    .invoiceNumber(invoiceNumber)
                    .patient(appt.getPatient())
                    .doctor(appt.getDoctor())
                    .subtotal(amount)
                    .discount(BigDecimal.ZERO)
                    .total(amount)
                    .currency("NGN")
                    .status("PAID")
                    .issuedAt(appt.getScheduledAt())
                    .paidAt(appt.getScheduledAt().plusMinutes(35))
                    .dueDate(appt.getScheduledAt().toLocalDate())
                    .build();
                invoice.getLineItems().add(lineItem);
                lineItem.setInvoice(invoice);
                invoiceRepo.save(invoice);
            }
        }
        log.info("[Seed] Payments and invoices ready ({} created)", created);
    }

    private void seedDoctorLeaves(List<Doctor> doctors, List<User> admins) {
        record LeaveSpec(int dIdx, int startOffset, int endOffset, String reason, String type) {}
        List<LeaveSpec> specs = List.of(
            new LeaveSpec(0, -21, -15, "Annual leave",                 "ANNUAL"),
            new LeaveSpec(1,  30,  37, "Professional development course", "CONFERENCE"),
            new LeaveSpec(2, -90, -83, "Personal leave",               "PERSONAL"),
            new LeaveSpec(3,  45,  52, "Annual leave",                 "ANNUAL"),
            new LeaveSpec(4,   7,   9, "Sick leave",                   "SICK")
        );
        int created = 0;
        for (LeaveSpec s : specs) {
            Doctor doctor = doctors.get(s.dIdx());
            if (!leaveRepo.findByDoctorId(doctor.getId()).isEmpty()) continue;

            DoctorLeave leave = DoctorLeave.builder()
                .doctor(doctor)
                .startDate(TODAY.plusDays(s.startOffset()))
                .endDate(TODAY.plusDays(s.endOffset()))
                .reason(s.reason())
                .leaveType(s.type())
                .status("APPROVED")
                .createdBy(admins.isEmpty() ? null : admins.get(0))
                .build();
            leaveRepo.save(leave);
            created++;
        }
        log.info("[Seed] Doctor leaves ready ({} created)", created);
    }

    private void seedWaitlistEntries(List<User> patients, List<Doctor> doctors,
                                     Map<String, Department> depts) {
        int created = 0;
        if (patients.size() > 5 && !doctors.isEmpty()) {
            User p = patients.get(5);
            Doctor d = doctors.get(0);
            if (!waitlistRepo.existsByPatientIdAndDoctorIdAndStatus(p.getId(), d.getId(), "WAITING")) {
                waitlistRepo.save(WaitlistEntry.builder()
                    .patient(p)
                    .doctor(d)
                    .department(d.getDepartment())
                    .specialization(d.getSpecialization())
                    .preferredDate(TODAY.plusDays(5))
                    .status("WAITING")
                    .expiresAt(LocalDateTime.now().plusDays(14))
                    .build());
                created++;
            }
        }
        if (patients.size() > 7 && doctors.size() > 2) {
            User p = patients.get(7);
            Doctor d = doctors.get(2);
            if (!waitlistRepo.existsByPatientIdAndDoctorIdAndStatus(p.getId(), d.getId(), "WAITING")) {
                waitlistRepo.save(WaitlistEntry.builder()
                    .patient(p)
                    .doctor(d)
                    .department(d.getDepartment())
                    .specialization(d.getSpecialization())
                    .preferredDate(TODAY.plusDays(3))
                    .status("WAITING")
                    .expiresAt(LocalDateTime.now().plusDays(7))
                    .build());
                created++;
            }
        }
        log.info("[Seed] Waitlist entries ready ({} created)", created);
    }

    private void seedNotifications(List<Appointment> appointments) {
        int sent = 0;
        for (Appointment a : appointments) {
            AppointmentEvent event = buildEvent(a);
            try {
                switch (a.getStatus()) {
                    case CONFIRMED  -> { notificationService.sendAppointmentBooked(event);    notificationService.sendAppointmentConfirmed(event); }
                    case COMPLETED  -> notificationService.sendAppointmentBooked(event);
                    case CANCELLED  -> notificationService.sendAppointmentCancelled(event);
                    default         -> {}
                }
                sent++;
            } catch (Exception e) {
                log.warn("[Seed] Notification skipped for appointment {} (Cassandra unavailable?): {}",
                    a.getConfirmationCode(), e.getMessage());
            }
        }
        log.info("[Seed] Notifications dispatched ({} appointments processed)", sent);
    }

    private <T> T getRandomElement(List<T> list) {
        if (list == null || list.isEmpty()) return null;
        return list.get(RANDOM.nextInt(list.size()));
    }

    private User findOrCreateUser(String email, String firstName, String lastName,
                                   String phone, LocalDate dob, Role role) {
        return userRepo.findByEmail(email).orElseGet(() -> {
            User u = User.builder()
                .email(email)
                .password(passwordEncoder.encode(DEMO_PASSWORD))
                .firstName(firstName)
                .lastName(lastName)
                .phone(phone)
                .dateOfBirth(dob)
                .role(role)
                .isActive(true)
                .enabled(true)
                .emailNotifications(true)
                .smsNotifications(true)
                .locale("en-US")
                .build();
            return userRepo.save(u);
        });
    }

    private AppointmentEvent buildEvent(Appointment a) {
        Doctor doc  = a.getDoctor();
        User   pat  = a.getPatient();
        return AppointmentEvent.builder()
            .eventId(UUID.randomUUID().toString())
            .eventType("SEED")
            .appointmentId(a.getId())
            .patientId(pat.getId())
            .patientEmail(pat.getEmail())
            .patientName(pat.getFullName())
            .doctorId(doc.getUser().getId())
            .doctorEmail(doc.getUser().getEmail())
            .doctorName("Dr. " + doc.getUser().getFullName())
            .departmentName(doc.getDepartment().getName())
            .scheduledAt(a.getScheduledAt())
            .status(a.getStatus())
            .occurredAt(LocalDateTime.now())
            .build();
    }
}
