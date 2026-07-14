package com.shopee.monolith.modules.voucher.repository;

import com.shopee.monolith.modules.voucher.entity.VoucherUsage;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface VoucherUsageRepository extends JpaRepository<VoucherUsage, UUID> {

    Optional<VoucherUsage> findByCheckoutSessionId(UUID checkoutSessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from VoucherUsage u where u.checkoutSessionId = :checkoutSessionId")
    Optional<VoucherUsage> findByCheckoutSessionIdForUpdate(@Param("checkoutSessionId") UUID checkoutSessionId);
}
