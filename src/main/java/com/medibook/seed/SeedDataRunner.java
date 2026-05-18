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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@Order(10)
@Profile("!prod")
@RequiredArgsConstructor
public class SeedDataRunner implements ApplicationRunner {

    private static final String  DEMO_PASSWORD = "Password123!";
    private static final LocalDate TODAY        = LocalDate.now();

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

    private record PatientSpec(
        String email, String firstName, String lastName, String phone,
        LocalDate dob, String bloodGroup
    ) {}

    private static final List<PatientSpec> PATIENT_SPECS = List.of(
        new PatientSpec("patient.james@medibook.local",    "James",    "Okafor",    "+2347001111001", LocalDate.of(1985,  3, 15), "A+"),
        new PatientSpec("patient.fatima@medibook.local",   "Fatima",   "Bello",     "+2347001111002", LocalDate.of(1992,  7, 22), "O+"),
        new PatientSpec("patient.chidi@medibook.local",    "Chidi",    "Obi",       "+2347001111003", LocalDate.of(1978, 11,  8), "B-"),
        new PatientSpec("patient.amara@medibook.local",    "Amara",    "Eze",       "+2347001111004", LocalDate.of(1995,  1, 30), "AB+"),
        new PatientSpec("patient.tunde@medibook.local",    "Tunde",    "Adeyemi",   "+2347001111005", LocalDate.of(1988,  5, 17), "O-"),
        new PatientSpec("patient.ngozi@medibook.local",    "Ngozi",    "Nwachukwu", "+2347001111006", LocalDate.of(2000,  9, 12), "A-"),
        new PatientSpec("patient.emeka@medibook.local",    "Emeka",    "Ogbonna",   "+2347001111007", LocalDate.of(1975, 12,  3), "B+"),
        new PatientSpec("patient.sade@medibook.local",     "Sade",     "Adesanya",  "+2347001111008", LocalDate.of(1990,  6, 25), "O+"),
        new PatientSpec("patient.kola@medibook.local",     "Kola",     "Fashola",   "+2347001111009", LocalDate.of(1983,  4, 11), "A+"),
        new PatientSpec("patient.chidinma@medibook.local", "Chidinma", "Uche",      "+2347001111010", LocalDate.of(1998,  2, 18), "B+")
    );

    // dIdx=doctor index, pIdx=patient index, dayOffset, hour, status, type, reason
    private record ApptSpec(
        int dIdx, int pIdx, int dayOffset, int hour,
        AppointmentStatus status, AppointmentType type, String reason
    ) {}

    private static final List<ApptSpec> APPT_SPECS = List.of(
        new ApptSpec(0, 0, -14,  9, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Chest pain evaluation and stress test"),
        new ApptSpec(0, 1, -10,  9, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Hypertension management and medication review"),
        new ApptSpec(0, 2,  -7,  9, AppointmentStatus.COMPLETED,  AppointmentType.TELEHEALTH, "Post-PTCA cardiac follow-up"),
        new ApptSpec(0, 3,  -6, 14, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Cardiac stress test"),
        new ApptSpec(0, 4,  -4,  9, AppointmentStatus.NO_SHOW,    AppointmentType.IN_PERSON,  "Echocardiogram review"),
        new ApptSpec(0, 5,  -2, 14, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Arrhythmia consultation"),
        new ApptSpec(0, 6,   0,  9, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "New patient: palpitations and shortness of breath"),
        new ApptSpec(0, 7,   2,  9, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "Post-surgery follow-up"),
        new ApptSpec(0, 8,   4, 14, AppointmentStatus.CONFIRMED,  AppointmentType.TELEHEALTH, "Medication review"),
        new ApptSpec(0, 9,   8,  9, AppointmentStatus.PENDING,    AppointmentType.IN_PERSON,  "Pre-surgery cardiac clearance"),
        new ApptSpec(0, 0,  12, 14, AppointmentStatus.PENDING,    AppointmentType.IN_PERSON,  "Annual cardiac screening"),
        new ApptSpec(1, 1, -13, 10, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Eczema flare-up evaluation"),
        new ApptSpec(1, 2,  -9, 10, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Acne vulgaris treatment review"),
        new ApptSpec(1, 3,  -6, 10, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Psoriasis plaque management"),
        new ApptSpec(1, 4,  -5, 15, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Skin biopsy consultation"),
        new ApptSpec(1, 5,  -3, 10, AppointmentStatus.NO_SHOW,    AppointmentType.IN_PERSON,  "Generalised rash evaluation"),
        new ApptSpec(1, 6,  -1, 15, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Seborrheic dermatitis follow-up"),
        new ApptSpec(1, 7,   0, 10, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "New patient: chronic pruritus"),
        new ApptSpec(1, 8,   3, 10, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "Post-laser treatment check"),
        new ApptSpec(1, 9,   5, 15, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "Mole mapping and dermoscopy"),
        new ApptSpec(1, 0,   9, 10, AppointmentStatus.PENDING,    AppointmentType.IN_PERSON,  "Keloid scar treatment consultation"),
        new ApptSpec(1, 1,  13, 15, AppointmentStatus.PENDING,    AppointmentType.IN_PERSON,  "Vitiligo management plan"),
        new ApptSpec(2, 2, -12,  9, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Child wellness check-up, age 5"),
        new ApptSpec(2, 3,  -8,  9, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Fever, cough, and sore throat"),
        new ApptSpec(2, 4,  -5,  9, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Asthma management and inhaler technique"),
        new ApptSpec(2, 5,  -4, 14, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Routine vaccination — 18-month schedule"),
        new ApptSpec(2, 6,  -2,  9, AppointmentStatus.NO_SHOW,    AppointmentType.IN_PERSON,  "Growth and developmental assessment"),
        new ApptSpec(2, 7,  -1, 14, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Ear infection follow-up"),
        new ApptSpec(2, 8,   0,  9, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "New patient: developmental delay evaluation"),
        new ApptSpec(2, 9,   2,  9, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "Post-infection recovery check"),
        new ApptSpec(2, 0,   5, 14, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "Food allergy testing referral"),
        new ApptSpec(2, 1,   9,  9, AppointmentStatus.PENDING,    AppointmentType.IN_PERSON,  "Child nutrition and growth consultation"),
        new ApptSpec(2, 2,  14, 14, AppointmentStatus.PENDING,    AppointmentType.IN_PERSON,  "School readiness and vision screening"),
        new ApptSpec(3, 3, -11,  9, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Chronic migraine management"),
        new ApptSpec(3, 4,  -8,  9, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Epilepsy medication and seizure diary review"),
        new ApptSpec(3, 5,  -4,  9, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Ischaemic stroke rehabilitation follow-up"),
        new ApptSpec(3, 6,  -3, 14, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Brain MRI results review"),
        new ApptSpec(3, 7,  -2,  9, AppointmentStatus.NO_SHOW,    AppointmentType.IN_PERSON,  "Memory impairment assessment"),
        new ApptSpec(3, 8,  -1, 14, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Parkinson's disease progression review"),
        new ApptSpec(3, 9,   0,  9, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "New patient: persistent headaches and visual disturbance"),
        new ApptSpec(3, 0,   3,  9, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "EEG results interpretation"),
        new ApptSpec(3, 1,   5, 14, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "Multiple sclerosis disease monitoring"),
        new ApptSpec(3, 2,  10,  9, AppointmentStatus.PENDING,    AppointmentType.IN_PERSON,  "Peripheral neuropathy evaluation"),
        new ApptSpec(3, 3,  14, 14, AppointmentStatus.PENDING,    AppointmentType.IN_PERSON,  "Sleep disorder and narcolepsy consultation"),
        new ApptSpec(4, 4, -13,  8, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Annual physical examination and wellness screen"),
        new ApptSpec(4, 5,  -9,  8, AppointmentStatus.COMPLETED,  AppointmentType.IN_PERSON,  "Type 2 diabetes — HbA1c and medication review"),
        new ApptSpec(4, 6,  -5,  8, AppointmentStatus.COMPLETED,  AppointmentType.TELEHEALTH, "Blood pressure monitoring review"),
        new ApptSpec(4, 7,  -4, 14, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Lab results and lipid panel discussion"),
        new ApptSpec(4, 8,  -2,  8, AppointmentStatus.NO_SHOW,    AppointmentType.IN_PERSON,  "Tetanus booster and flu vaccine"),
        new ApptSpec(4, 9,  -1, 14, AppointmentStatus.CANCELLED,  AppointmentType.IN_PERSON,  "Chest X-ray interpretation"),
        new ApptSpec(4, 0,   0,  8, AppointmentStatus.CONFIRMED,  AppointmentType.TELEHEALTH, "New patient: chronic fatigue and sleep issues"),
        new ApptSpec(4, 1,   2,  8, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "Hypertension monitoring and medication titration"),
        new ApptSpec(4, 2,   5, 14, AppointmentStatus.CONFIRMED,  AppointmentType.IN_PERSON,  "Thyroid function test results and management"),
        new ApptSpec(4, 3,   9,  8, AppointmentStatus.PENDING,    AppointmentType.IN_PERSON,  "Pre-employment medical examination"),
        new ApptSpec(4, 4,  13, 14, AppointmentStatus.PENDING,    AppointmentType.TELEHEALTH, "General wellness and lifestyle consultation")
    );
    private record NoteSpec(String diagnosis, String treatmentPlan, String prescriptions, int followUpDays) {}

    private static final List<NoteSpec> NOTE_SPECS = List.of(
        // Doctor 0 COMPLETED appointments (indices 0-2 in doctor block)
        new NoteSpec(
            "Unstable angina pectoris with hypertensive urgency",
            "Initiated dual antiplatelet therapy. Cardiac catheterisation recommended within 72h. Strict BP monitoring twice daily. Low-sodium DASH diet; restrict strenuous activity.",
            "Aspirin 81mg OD; Clopidogrel 75mg OD; Amlodipine 10mg OD; Nitroglycerin 0.4mg SL PRN",
            30
        ),
        new NoteSpec(
            "Stage 2 essential hypertension — adequately controlled on dual therapy",
            "Continue current antihypertensive regimen. Ambulatory 24h BP monitoring ordered. DASH diet reinforcement. Aerobic exercise 150 min/week.",
            "Perindopril 8mg OD; Amlodipine 5mg OD; Aspirin 81mg OD",
            60
        ),
        new NoteSpec(
            "Stable coronary artery disease, 12 months post-PTCA — good functional recovery",
            "Dual antiplatelet therapy to continue for full 12-month course. Enrol in cardiac rehabilitation programme. Annual echocardiogram scheduled. Lipids within target.",
            "Clopidogrel 75mg OD; Aspirin 100mg OD; Bisoprolol 5mg OD; Rosuvastatin 20mg ON",
            90
        ),
        // Doctor 1 COMPLETED appointments (indices 0-2 in doctor block)
        new NoteSpec(
            "Moderate-to-severe atopic eczema with secondary Staphylococcal superinfection",
            "Topical steroid with wet wrapping technique. Emollient regimen (minimum 500g/week). Short oral antibiotic course for superinfection. Trigger avoidance: synthetic fabrics, harsh soaps.",
            "Betamethasone valerate 0.1% cream BD × 2 weeks; Cetirizine 10mg ON; Flucloxacillin 500mg QDS × 7 days",
            14
        ),
        new NoteSpec(
            "Moderate acne vulgaris (grade III) — responding to oral isotretinoin",
            "Continue isotretinoin at current dose. Monthly LFT and fasting lipid panel mandatory. Strict contraception. SPF 50+ sunscreen daily. Avoid waxing while on retinoid.",
            "Isotretinoin 40mg OD; Clindamycin 1% gel OD (topical wash)",
            30
        ),
        new NoteSpec(
            "Chronic plaque psoriasis — moderate severity (PASI 12), suboptimal response to topical agents",
            "Escalating to biologic therapy (Adalimumab). Pre-screening complete: TB (Mantoux negative), HBsAg negative, FBC normal. Review PASI response at 12 weeks.",
            "Adalimumab 80mg SC week 0, then 40mg SC every 2 weeks; Betamethasone valerate 0.1% cream PRN for breakthrough lesions",
            84
        ),
        // Doctor 2 COMPLETED appointments (indices 0-2 in doctor block)
        new NoteSpec(
            "Healthy child — 5-year routine wellness check; all developmental milestones achieved",
            "Nutrition and activity counselling provided to parents. Booster vaccinations confirmed up to date per national schedule. School readiness assessment: pass.",
            "Vitamin D3 400 IU daily; No other medications required",
            365
        ),
        new NoteSpec(
            "Group A streptococcal pharyngitis (rapid antigen test positive)",
            "Ten-day amoxicillin course. Adequate oral hydration. Saline gargle 3× daily. School exclusion for 24h after antibiotic commencement. Throat swab MC&S sent.",
            "Amoxicillin 500mg TDS × 10 days; Paracetamol suspension 250mg/5ml PRN for fever and pain",
            14
        ),
        new NoteSpec(
            "Mild-moderate persistent asthma — well-controlled (ACQ score 0.8)",
            "Excellent inhaler technique demonstrated today. Continue ICS/LABA controller therapy. SABA for breakthrough symptoms — usage < 2× per week. Peak flow diary maintained. Environmental triggers identified and addressed.",
            "Budesonide/Formoterol 100/6 mcg inhaler 1 puff BD; Salbutamol 100 mcg MDI 2 puffs PRN",
            90
        ),
        // Doctor 3 COMPLETED appointments (indices 0-2 in doctor block)
        new NoteSpec(
            "Chronic migraine with aura — inadequately controlled on beta-blocker alone",
            "Adding topiramate as additional preventive therapy. Migraine diary to document frequency, triggers, and severity. Sleep hygiene, regular mealtimes, and adequate hydration strongly advised.",
            "Topiramate 25mg ON × 4 weeks then 50mg ON; Sumatriptan 50mg oral PRN (max 2 per attack, max 4 days/month); Metoclopramide 10mg oral PRN for nausea",
            42
        ),
        new NoteSpec(
            "Juvenile myoclonic epilepsy — seizure-free on sodium valproate for 18 consecutive months",
            "Continue valproate at current dose — good therapeutic level. Annual LFT and FBC. Lifestyle counselling: avoid sleep deprivation, alcohol, and photic triggers. No driving for additional 6 months per DVLA guidelines.",
            "Sodium valproate 500mg BD (modified release); Folic acid 5mg OD (precautionary)",
            180
        ),
        new NoteSpec(
            "Right MCA territory ischaemic stroke (6 months post-event) — improving neurological deficits",
            "Neurological examination shows improving arm power (4+/5) and mild dysarthria. Continue antiplatelet therapy and statin. Ongoing physiotherapy (3× per week) and speech therapy referral maintained. BP target < 130/80.",
            "Aspirin 75mg OD; Dipyridamole MR 200mg BD; Atorvastatin 40mg ON; Ramipril 5mg OD",
            90
        ),
        // Doctor 4 COMPLETED appointments (indices 0-2 in doctor block)
        new NoteSpec(
            "Healthy adult — no significant pathology identified on annual wellness screen",
            "BMI 24.1 — within normal range. Total cholesterol 5.4 mmol/L — borderline high; dietary intervention preferred over medication. Colonoscopy screening recommended from age 50. All vaccinations current.",
            "Multivitamin supplement OD; Vitamin D3 1000 IU OD (seasonal supplementation)",
            365
        ),
        new NoteSpec(
            "Type 2 diabetes mellitus — HbA1c 7.2% (suboptimally controlled on metformin monotherapy)",
            "Adding empagliflozin for cardiorenal protection and additional glycaemic control. Diabetes education programme referral. Self-monitoring blood glucose daily (fasting + 2h postprandial). Low-carbohydrate diet advice.",
            "Metformin 1000mg BD; Empagliflozin 10mg OD; Linagliptin 5mg OD; Aspirin 75mg OD",
            90
        ),
        new NoteSpec(
            "Essential hypertension — well controlled; BP 128/82 on current dual therapy",
            "Continue current regimen. Home BP monitoring log reviewed — consistent readings. Salt restriction reinforced (< 5g NaCl/day). DASH diet and 150 min/week moderate aerobic exercise.",
            "Amlodipine 5mg OD; Losartan 50mg OD",
            60
        )
    );

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
        for (PatientSpec s : PATIENT_SPECS) {
            User u = findOrCreateUser(s.email(), s.firstName(), s.lastName(), s.phone(), s.dob(), Role.ROLE_PATIENT);
            patients.add(u);
            if (profileRepo.findByUserId(u.getId()).isEmpty()) {
                PatientProfile p = PatientProfile.builder()
                    .user(u)
                    .bloodGroup(s.bloodGroup())
                    .dateOfBirthEnc(s.dob().toString())  // PHI converter encrypts at persist
                    .emergencyContact("Family Contact: " + s.firstName() + " Next-of-Kin")
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
        int[] slotDurations = { 60, 60, 60, 60, 60, 60, 60, 60 };
        List<Doctor> doctors = new ArrayList<>();
        for (int idx = 0; idx < DOCTOR_SPECS.size(); idx++) {
            DoctorSpec s = DOCTOR_SPECS.get(idx);
            int slotDuration = slotDurations[idx % slotDurations.length];
            if (doctorRepo.existsByLicenseNumber(s.license())) {
                doctorRepo.findByLicenseNumber(s.license()).ifPresent(existing -> {
                    if (existing.getSlotDurationMins() != slotDuration) {
                        existing.setSlotDurationMins(slotDuration);
                        doctorRepo.save(existing);
                    }
                    doctors.add(existing);
                });
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
                .slotDurationMins(slotDuration)
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
        LocalTime shiftStart = LocalTime.of(8, 0);
        LocalTime shiftEnd   = LocalTime.of(22, 0);
        int[] daysOfWeek     = { 1, 2, 3, 4, 5, 6 };

        int created = 0;
        for (Doctor d : doctors) {
            List<DoctorWorkingHours> existing = hoursRepo.findByDoctorId(d.getId());
            if (!existing.isEmpty()) hoursRepo.deleteAll(existing);
            for (int dow : daysOfWeek) {
                hoursRepo.save(DoctorWorkingHours.builder()
                        .doctor(d)
                        .dayOfWeek(dow)
                        .startTime(shiftStart)
                        .endTime(shiftEnd)
                        .build());
                created++;
            }
        }
        log.info("[Seed] Working hours ready ({} rows across {} doctors, 08:00–22:00 Mon–Sat)",
                created, doctors.size());
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
        Map<Integer, Integer> seq = new HashMap<>();

        for (ApptSpec s : APPT_SPECS) {
            int localSeq = seq.merge(s.dIdx(), 1, Integer::sum);
            String code  = "SEED-D%d-%03d".formatted(s.dIdx(), localSeq);

            if (apptRepo.existsByConfirmationCode(code)) {
                apptRepo.findByConfirmationCode(code).ifPresent(saved::add);
                continue;
            }

            User    patient = patients.get(s.pIdx());
            Doctor  doctor  = doctors.get(s.dIdx());
            Department dept = doctor.getDepartment();

            LocalDateTime scheduledAt = TODAY.plusDays(s.dayOffset()).atTime(s.hour(), 0);
            LocalDateTime endTime     = scheduledAt.plusMinutes(30);

            Appointment.AppointmentBuilder builder = Appointment.builder()
                .patient(patient)
                .doctor(doctor)
                .department(dept)
                .scheduledAt(scheduledAt)
                .endTime(endTime)
                .durationMins(30)
                .status(s.status())
                .type(s.type())
                .reason(s.reason())
                .confirmationCode(code);

            if (s.status() == AppointmentStatus.CANCELLED) {
                builder
                    .cancelledAt(scheduledAt.minusDays(1))
                    .cancelledBy(patient)
                    .cancellationReason("Patient cancelled due to scheduling conflict");
            }

            saved.add(apptRepo.save(builder.build()));
        }

        log.info("[Seed] Appointments ready ({} created)", saved.size());
        return saved;
    }

    private void seedConsultationNotes(List<Appointment> appointments, List<Doctor> doctors) {
        int doctorBlockSize = 11;
        int created = 0;

        for (int dIdx = 0; dIdx < doctors.size(); dIdx++) {
            for (int localPos = 0; localPos < 3; localPos++) {
                int globalApptIdx = dIdx * doctorBlockSize + localPos;
                if (globalApptIdx >= APPT_SPECS.size()) break;
                if (APPT_SPECS.get(globalApptIdx).status() != AppointmentStatus.COMPLETED) continue;

                String code = "SEED-D%d-%03d".formatted(dIdx, localPos + 1);
                Appointment appt = findApptByCode(appointments, code);
                if (appt == null) continue;
                if (noteRepo.findByAppointmentId(appt.getId()).isPresent()) continue;

                NoteSpec n = NOTE_SPECS.get(dIdx * 3 + localPos);
                ConsultationNote note = ConsultationNote.builder()
                    .appointment(appt)
                    .doctor(doctors.get(dIdx))
                    .diagnosis(n.diagnosis())
                    .treatmentPlan(n.treatmentPlan())
                    .prescriptions(n.prescriptions())
                    .followUpDate(TODAY.plusDays(n.followUpDays()))
                    .phiVersion("v1")
                    .build();
                noteRepo.save(note);
                created++;
            }
        }
        log.info("[Seed] Consultation notes ready ({} created)", created);
    }

    private void seedReviews(List<Appointment> appointments) {
        record ReviewData(int dIdx, int localSeq, int rating, String comment) {}
        List<ReviewData> reviews = List.of(
            new ReviewData(0, 1, 5, "Exceptional cardiologist. Dr Obiora was thorough, explained everything clearly, and made me feel at ease throughout the consultation."),
            new ReviewData(0, 2, 4, "Very professional and knowledgeable. The waiting time was a little long but the consultation itself was excellent."),
            new ReviewData(1, 1, 5, "Dr Mohammed is brilliant with skin conditions. My eczema has improved dramatically following her treatment plan."),
            new ReviewData(1, 2, 4, "Very thorough examination and a clear explanation of all treatment options. Highly recommend her practice."),
            new ReviewData(2, 1, 5, "Dr Nwosu is wonderful with children. My son felt completely comfortable and the wellness check was very comprehensive."),
            new ReviewData(2, 2, 5, "Excellent bedside manner and swift, accurate diagnosis. Recovery was swift after her prescribed course of treatment."),
            new ReviewData(3, 1, 4, "Highly knowledgeable neurologist. The migraine prevention plan Dr Aliyu recommended has already made a significant difference."),
            new ReviewData(3, 2, 5, "Outstanding care and expertise. Dr Aliyu's management of my epilepsy has been genuinely life-changing."),
            new ReviewData(4, 1, 5, "Wonderful family doctor. The annual check-up was thorough and she gave great lifestyle health advice."),
            new ReviewData(4, 2, 4, "Good ongoing management of my diabetes. The lifestyle modification advice is actually working well for me.")
        );

        int created = 0;
        for (ReviewData r : reviews) {
            String code = "SEED-D%d-%03d".formatted(r.dIdx(), r.localSeq());
            Appointment appt = findApptByCode(appointments, code);
            if (appt == null || reviewRepo.existsByAppointmentId(appt.getId())) continue;

            DoctorReview review = DoctorReview.builder()
                .appointment(appt)
                .patient(appt.getPatient())
                .doctor(appt.getDoctor())
                .rating((byte) r.rating())
                .comment(r.comment())
                .status("APPROVED")
                .moderatedAt(appt.getScheduledAt().plusDays(1))
                .build();
            reviewRepo.save(review);
            created++;

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

    private Appointment findApptByCode(List<Appointment> appointments, String code) {
        return appointments.stream()
            .filter(a -> code.equals(a.getConfirmationCode()))
            .findFirst()
            .orElse(null);
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
