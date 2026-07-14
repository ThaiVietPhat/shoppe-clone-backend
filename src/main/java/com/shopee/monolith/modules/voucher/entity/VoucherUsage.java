package com.shopee.monolith.modules.voucher.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.modules.voucher.model.VoucherUsageStatus;
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
import java.util.UUID;

@Entity
@Table(name = "voucher_usages")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class VoucherUsage extends BaseEntity {

    @Column(name = "voucher_id", nullable = false)
    private UUID voucherId;

    @Column(name = "checkout_session_id", nullable = false, unique = true)
    private UUID checkoutSessionId;

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal discountAmount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private VoucherUsageStatus status = VoucherUsageStatus.RESERVED;

    @Version
    @Column(name = "version", nullable = false)
    @lombok.Builder.Default
    private int version = 0;

    public void confirm() {
        this.status = VoucherUsageStatus.CONFIRMED;
    }

    public void release() {
        this.status = VoucherUsageStatus.RELEASED;
    }
}
