package com.medibook.domain.consent.repository;

import com.medibook.domain.consent.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    List<UserConsent> findByUserId(Long userId);

    Optional<UserConsent> findByUserIdAndDoctorIdAndConsentType(Long userId, Long doctorId, String consentType);
}
