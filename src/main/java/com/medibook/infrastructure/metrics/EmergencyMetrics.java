package com.medibook.infrastructure.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Prometheus metrics for emergency consultation SLA monitoring.
 *
 * Key metrics:
 *  - medibook_emergency_requests_total          — total emergency requests by outcome
 *  - medibook_emergency_doctor_assignment_seconds — doctor assignment latency histogram
 *  - medibook_emergency_no_doctor_total          — no-doctor-available rate (SLA breach signal)
 *  - medibook_emergency_outstanding_debt_total   — blocked-by-debt count
 *  - medibook_emergency_session_window_violations_total — telemedicine window violations
 */
@Component
public class EmergencyMetrics {

    private final Counter requestsTotal;
    private final Counter noDoctorTotal;
    private final Counter outstandingDebtTotal;
    private final Counter sessionWindowViolationsTotal;
    private final Timer   doctorAssignmentTimer;
    private final DistributionSummary invoiceAmountSummary;

    public EmergencyMetrics(MeterRegistry registry) {
        this.requestsTotal = Counter.builder("medibook_emergency_requests_total")
                .description("Total emergency consultation requests")
                .tag("outcome", "initiated")
                .register(registry);

        this.noDoctorTotal = Counter.builder("medibook_emergency_no_doctor_total")
                .description("Emergency requests that failed due to no available doctor")
                .register(registry);

        this.outstandingDebtTotal = Counter.builder("medibook_emergency_outstanding_debt_total")
                .description("Emergency requests blocked by outstanding debt")
                .register(registry);

        this.sessionWindowViolationsTotal = Counter.builder("medibook_emergency_session_window_violations_total")
                .description("Telemedicine join attempts outside the ±10 min session window")
                .register(registry);

        this.doctorAssignmentTimer = Timer.builder("medibook_emergency_doctor_assignment_seconds")
                .description("Time taken to assign a doctor to an emergency consultation")
                .publishPercentiles(0.5, 0.95, 0.99)
                .publishPercentileHistogram()
                .register(registry);

        this.invoiceAmountSummary = DistributionSummary.builder("medibook_emergency_invoice_amount")
                .description("Emergency consultation invoice amounts in NGN")
                .baseUnit("NGN")
                .publishPercentiles(0.5, 0.95)
                .register(registry);
    }

    public void recordEmergencyRequest() {
        requestsTotal.increment();
    }

    public void recordNoDoctorAvailable() {
        noDoctorTotal.increment();
    }

    public void recordOutstandingDebtBlock() {
        outstandingDebtTotal.increment();
    }

    public void recordSessionWindowViolation() {
        sessionWindowViolationsTotal.increment();
    }

    /** Wrap the doctor-assignment block and record its duration. */
    public <T> T timeAssignment(java.util.concurrent.Callable<T> task) throws Exception {
        return doctorAssignmentTimer.recordCallable(task);
    }

    public void recordInvoiceAmount(double amount) {
        invoiceAmountSummary.record(amount);
    }
}
