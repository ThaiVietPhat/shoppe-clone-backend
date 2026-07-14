package com.shopee.monolith.modules.order.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.order.model.FulfillmentStatus;
import com.shopee.monolith.modules.order.model.OrderPaymentStatus;
import com.shopee.monolith.modules.order.model.OrderStatus;
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
@Table(name = "orders")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

    @Column(name = "buyer_id", nullable = false)
    private UUID buyerId;

    @Column(name = "shop_id", nullable = false)
    private UUID shopId;

    @Column(name = "checkout_session_id", nullable = false)
    private UUID checkoutSessionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private OrderStatus status;

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

    @Column(name = "payment_method", length = 20)
    private String paymentMethod;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_status", nullable = false, length = 30)
    @lombok.Builder.Default
    private OrderPaymentStatus paymentStatus = OrderPaymentStatus.UNPAID;

    @Enumerated(EnumType.STRING)
    @Column(name = "fulfillment_status", length = 30)
    private FulfillmentStatus fulfillmentStatus;

    @Version
    @Column(name = "version", nullable = false)
    @lombok.Builder.Default
    private int version = 0;

    public void cancel() {
        this.status = OrderStatus.CANCELLED;
    }

    /**
     * Applies this order's share of a checkout-level voucher discount, reducing totalAmount.
     * Called once at checkout creation — never re-applied after that.
     */
    public void applyDiscount(BigDecimal orderDiscountShare) {
        this.discountAmount = orderDiscountShare;
        this.totalAmount = this.totalAmount.subtract(orderDiscountShare);
    }

    public void markPaid(String method) {
        this.status = OrderStatus.PAID;
        this.paymentStatus = OrderPaymentStatus.PAID;
        this.paymentMethod = method;
        this.fulfillmentStatus = FulfillmentStatus.READY_TO_SHIP;
    }

    /**
     * Settles a COD checkout: inventory and fulfillment are confirmed immediately so the seller
     * can pack and ship, but the order stays UNPAID — cash is only collected on delivery, see
     * {@link #deliver()}. Never conflate COD with {@link #markPaid}, which represents an actual
     * gateway settlement.
     */
    public void confirmCod(String method) {
        this.status = OrderStatus.CONFIRMED;
        this.paymentStatus = OrderPaymentStatus.UNPAID;
        this.paymentMethod = method;
        this.fulfillmentStatus = FulfillmentStatus.READY_TO_SHIP;
    }

    public void ship() {
        if (this.fulfillmentStatus != FulfillmentStatus.READY_TO_SHIP) {
            throw new AppException(ErrorCode.ORDER_FULFILLMENT_INVALID_STATE);
        }
        this.fulfillmentStatus = FulfillmentStatus.SHIPPED;
        this.status = OrderStatus.FULFILLED;
    }

    public void deliver() {
        if (this.fulfillmentStatus != FulfillmentStatus.SHIPPED) {
            throw new AppException(ErrorCode.ORDER_FULFILLMENT_INVALID_STATE);
        }
        this.fulfillmentStatus = FulfillmentStatus.DELIVERED;
        this.status = OrderStatus.DELIVERED;
        // COD cash is collected by the carrier at handover — this is where an unpaid COD order
        // actually becomes paid. Orders already PAID (online gateway) are left untouched.
        if (this.paymentStatus == OrderPaymentStatus.UNPAID) {
            this.paymentStatus = OrderPaymentStatus.PAID;
        }
    }

    public void markPaymentExpired() {
        this.status = OrderStatus.CANCELLED;
        this.paymentStatus = OrderPaymentStatus.EXPIRED;
    }

    public void markPaymentFailed() {
        this.status = OrderStatus.CANCELLED;
        this.paymentStatus = OrderPaymentStatus.FAILED;
    }
}
