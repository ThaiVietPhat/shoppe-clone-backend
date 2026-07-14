package com.shopee.monolith.modules.wallet.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.modules.wallet.model.PayoutStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mock instant payout — no real bank transfer integration, same "mock" philosophy as COD.
 * The request is created and completed in the same transaction; status is kept for extensibility
 * rather than because there's currently an async settlement step.
 */
@Entity
@Table(name = "payout_requests")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class PayoutRequest extends BaseEntity {

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Column(name = "seller_id", nullable = false)
    private UUID sellerId;

    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private PayoutStatus status = PayoutStatus.REQUESTED;

    @Column(name = "requested_at", nullable = false)
    @lombok.Builder.Default
    private Instant requestedAt = Instant.now();

    @Column(name = "processed_at")
    private Instant processedAt;

    @Version
    @Column(name = "version", nullable = false)
    @lombok.Builder.Default
    private int version = 0;

    public void complete(Instant now) {
        this.status = PayoutStatus.COMPLETED;
        this.processedAt = now;
    }
}
