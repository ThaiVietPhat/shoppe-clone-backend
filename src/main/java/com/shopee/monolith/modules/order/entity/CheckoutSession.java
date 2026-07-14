package com.shopee.monolith.modules.order.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.modules.order.model.CheckoutSessionStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "checkout_sessions")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class CheckoutSession extends BaseEntity {

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private CheckoutSessionStatus status;

    @Column(name = "total_amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "items_subtotal", nullable = false, precision = 15, scale = 2)
    @lombok.Builder.Default
    private BigDecimal itemsSubtotal = BigDecimal.ZERO;

    @Column(name = "shipping_fee", nullable = false, precision = 15, scale = 2)
    @lombok.Builder.Default
    private BigDecimal shippingFee = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 15, scale = 2)
    @lombok.Builder.Default
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "shipping_recipient_name", nullable = false)
    private String shippingRecipientName;

    @Column(name = "shipping_phone", nullable = false)
    private String shippingPhone;

    @Column(name = "shipping_address_line", nullable = false)
    private String shippingAddressLine;

    @Column(name = "shipping_ward_code", nullable = false)
    private String shippingWardCode;

    @Column(name = "shipping_ward_name", nullable = false)
    private String shippingWardName;

    @Column(name = "shipping_district_code", nullable = false)
    private String shippingDistrictCode;

    @Column(name = "shipping_district_name", nullable = false)
    private String shippingDistrictName;

    @Column(name = "shipping_province_code", nullable = false)
    private String shippingProvinceCode;

    @Column(name = "shipping_province_name", nullable = false)
    private String shippingProvinceName;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public void expire() {
        this.status = CheckoutSessionStatus.EXPIRED;
    }

    public void complete() {
        this.status = CheckoutSessionStatus.COMPLETED;
    }

    public void markPaymentExpired() {
        this.status = CheckoutSessionStatus.PAYMENT_EXPIRED;
    }

    public void cancel() {
        this.status = CheckoutSessionStatus.CANCELLED;
    }

    public void updateTotalAmount(BigDecimal totalAmount) {
        this.totalAmount = totalAmount;
    }

    public void updateTotals(BigDecimal subtotal, BigDecimal fee) {
        this.itemsSubtotal = subtotal;
        this.shippingFee = fee;
        this.totalAmount = subtotal.add(fee);
    }

    /**
     * Applies a checkout-level voucher discount on top of already-set item/shipping totals.
     */
    public void applyDiscount(BigDecimal discount) {
        this.discountAmount = discount;
        this.totalAmount = this.totalAmount.subtract(discount);
    }
}
