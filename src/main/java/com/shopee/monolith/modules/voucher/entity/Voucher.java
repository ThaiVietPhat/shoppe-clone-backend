package com.shopee.monolith.modules.voucher.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.modules.voucher.model.DiscountType;
import com.shopee.monolith.modules.voucher.model.VoucherStatus;
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
import java.math.RoundingMode;
import java.time.Instant;

@Entity
@Table(name = "vouchers")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Voucher extends BaseEntity {

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(name = "discount_type", nullable = false, length = 20)
    private DiscountType discountType;

    @Column(name = "discount_value", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountValue;

    @Column(name = "max_discount_amount", precision = 15, scale = 2)
    private BigDecimal maxDiscountAmount;

    @Column(name = "min_order_amount", nullable = false, precision = 15, scale = 2)
    @lombok.Builder.Default
    private BigDecimal minOrderAmount = BigDecimal.ZERO;

    @Column(name = "usage_limit")
    private Integer usageLimit;

    @Column(name = "used_count", nullable = false)
    @lombok.Builder.Default
    private int usedCount = 0;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private VoucherStatus status = VoucherStatus.ACTIVE;

    @Version
    @Column(name = "version", nullable = false)
    @lombok.Builder.Default
    private int version = 0;

    public boolean isActive() {
        return status == VoucherStatus.ACTIVE;
    }

    public boolean isWithinValidityWindow(Instant now) {
        return !now.isBefore(startsAt) && !now.isAfter(expiresAt);
    }

    public boolean hasUsageRemaining() {
        return usageLimit == null || usedCount < usageLimit;
    }

    public boolean isOrderTotalEligible(BigDecimal orderSubtotal) {
        return orderSubtotal.compareTo(minOrderAmount) >= 0;
    }

    /**
     * Percentage discounts are computed on the items subtotal only (never shipping),
     * capped by maxDiscountAmount and never exceeding the subtotal itself.
     */
    public BigDecimal computeDiscount(BigDecimal orderSubtotal) {
        BigDecimal raw;
        if (discountType == DiscountType.PERCENTAGE) {
            raw = orderSubtotal.multiply(discountValue)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            if (maxDiscountAmount != null && raw.compareTo(maxDiscountAmount) > 0) {
                raw = maxDiscountAmount;
            }
        } else {
            raw = discountValue;
        }
        if (raw.compareTo(orderSubtotal) > 0) {
            raw = orderSubtotal;
        }
        return raw.setScale(2, RoundingMode.HALF_UP);
    }

    public void incrementUsage() {
        this.usedCount += 1;
    }

    public void decrementUsage() {
        if (this.usedCount > 0) {
            this.usedCount -= 1;
        }
    }

    public void activate() {
        this.status = VoucherStatus.ACTIVE;
    }

    public void deactivate() {
        this.status = VoucherStatus.INACTIVE;
    }

    public void softDelete() {
        this.status = VoucherStatus.DELETED;
    }

    public void update(DiscountType discountType, BigDecimal discountValue, BigDecimal maxDiscountAmount,
                        BigDecimal minOrderAmount, Integer usageLimit, Instant startsAt, Instant expiresAt) {
        this.discountType = discountType;
        this.discountValue = discountValue;
        this.maxDiscountAmount = maxDiscountAmount;
        this.minOrderAmount = minOrderAmount;
        this.usageLimit = usageLimit;
        this.startsAt = startsAt;
        this.expiresAt = expiresAt;
    }
}
