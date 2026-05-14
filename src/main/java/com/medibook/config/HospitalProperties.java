package com.medibook.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Hospital-wide pricing configuration.
 * Single consultation fee for all doctors, with an experience-based premium
 * for senior consultants (doctors with experience exceeding the threshold).
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.hospital")
public class HospitalProperties {

    /** Base consultation fee for the hospital (applies to all doctors) */
    private BigDecimal consultationFee = BigDecimal.valueOf(5000.00);

    /** Premium percentage added for senior consultants */
    private int experiencePremiumPercent = 20;

    /** Years of experience threshold to qualify as senior consultant */
    private int experienceThresholdYears = 10;

    public BigDecimal getBaseFee() {
        return consultationFee;
    }

    public BigDecimal getSeniorFee() {
        BigDecimal premium = consultationFee.multiply(
                BigDecimal.valueOf(experiencePremiumPercent).divide(BigDecimal.valueOf(100)));
        return consultationFee.add(premium);
    }

    public BigDecimal getFeeForDoctor(int yearsOfExperience) {
        return yearsOfExperience > experienceThresholdYears ? getSeniorFee() : getBaseFee();
    }

    public boolean isSeniorConsultant(int yearsOfExperience) {
        return yearsOfExperience > experienceThresholdYears;
    }
}
