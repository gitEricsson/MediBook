package com.medibook.domain.consent.service;

import com.medibook.domain.consent.dto.ConsentRequest;
import com.medibook.domain.consent.entity.UserConsent;
import com.medibook.domain.consent.repository.UserConsentRepository;
import com.medibook.domain.user.entity.User;
import com.medibook.domain.user.repository.UserRepository;
import com.medibook.security.UserPrincipal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConsentService {

    private final UserConsentRepository consentRepository;
    private final UserRepository        userRepository;

    @Transactional
    public UserConsent updateConsent(ConsentRequest req, UserPrincipal principal, String ipAddress) {
        UserConsent consent = consentRepository
                .findByUserIdAndConsentType(principal.getId(), req.getConsentType())
                .orElseGet(() -> {
                    User user = userRepository.getReferenceById(principal.getId());
                    return UserConsent.builder()
                            .user(user)
                            .consentType(req.getConsentType())
                            .build();
                });

        consent.setGranted(req.isGranted());
        consent.setIpAddress(ipAddress);

        if (req.isGranted()) {
            consent.setGrantedAt(LocalDateTime.now());
            consent.setRevokedAt(null);
        } else {
            consent.setRevokedAt(LocalDateTime.now());
        }

        UserConsent saved = consentRepository.save(consent);
        log.info("Consent [{}] {} for user [{}]", req.getConsentType(),
                req.isGranted() ? "granted" : "revoked", principal.getId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<UserConsent> getMyConsents(UserPrincipal principal) {
        return consentRepository.findByUserId(principal.getId());
    }
}
