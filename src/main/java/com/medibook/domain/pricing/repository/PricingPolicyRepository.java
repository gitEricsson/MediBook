package com.medibook.domain.pricing.repository;

import com.medibook.domain.pricing.entity.PricingPolicy;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PricingPolicyRepository extends JpaRepository<PricingPolicy, Long> {
}
