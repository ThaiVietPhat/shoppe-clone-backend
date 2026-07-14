package com.shopee.monolith.modules.order.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.modules.order.model.ReturnReasonCategory;
import com.shopee.monolith.modules.order.model.ReturnStatus;
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

@Entity
@Table(name = "returns")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Return extends BaseEntity {

    @Column(name = "order_id", nullable = false, unique = true)
    private UUID orderId;

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    /** Denormalized snapshot so the seller-scoped return list can query directly, same style as OrderItem. */
    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_category", nullable = false, length = 30)
    private ReturnReasonCategory reasonCategory;

    @Column(name = "description")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    @lombok.Builder.Default
    private ReturnStatus status = ReturnStatus.REQUESTED;

    @Column(name = "refund_amount", precision = 15, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "resolution_note")
    private String resolutionNote;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;

    @Column(name = "requested_at", nullable = false)
    @lombok.Builder.Default
    private Instant requestedAt = Instant.now();

    @Version
    @Column(name = "version", nullable = false)
    @lombok.Builder.Default
    private int version = 0;

    public void approve(UUID sellerId, String note, BigDecimal refundAmount, Instant now) {
        this.status = ReturnStatus.APPROVED;
        this.resolutionNote = note;
        this.resolvedBy = sellerId;
        this.resolvedAt = now;
        this.refundAmount = refundAmount;
    }

    public void reject(UUID sellerId, String note, Instant now) {
        this.status = ReturnStatus.REJECTED;
        this.resolutionNote = note;
        this.resolvedBy = sellerId;
        this.resolvedAt = now;
    }
}
