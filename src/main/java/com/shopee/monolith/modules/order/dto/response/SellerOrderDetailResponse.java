package com.shopee.monolith.modules.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
@Schema(name = "SellerOrderDetailResponse",
        description = "Seller order detail with fulfillment-safe customer snapshot and item snapshots")
public record SellerOrderDetailResponse(
        @Schema(description = "Order ID")
        UUID orderId,

        @Schema(description = "Checkout session ID")
        UUID checkoutSessionId,

        @Schema(description = "Order status", example = "PAID")
        String status,

        @Schema(description = "Payment status", example = "PAID")
        String paymentStatus,

        @Schema(description = "Payment method", example = "VNPAY")
        String paymentMethod,

        @Schema(description = "Fulfillment status; null until the order is paid", example = "READY_TO_SHIP")
        String fulfillmentStatus,

        @Schema(description = "Items subtotal snapshot", example = "120000.00")
        BigDecimal itemsSubtotal,

        @Schema(description = "Shipping fee snapshot", example = "30000.00")
        BigDecimal shippingFee,

        @Schema(description = "Order grand total", example = "150000.00")
        BigDecimal totalAmount,

        @Schema(description = "Voucher discount applied to this order, if any", example = "0.00")
        BigDecimal discountAmount,

        @Schema(description = "Recipient name from the immutable shipping snapshot", example = "Nguyen Van A")
        String shippingRecipientName,

        @Schema(description = "Recipient phone from the immutable shipping snapshot", example = "0987654321")
        String shippingPhone,

        @Schema(description = "Shipping address line snapshot", example = "123 Demo Street")
        String shippingAddressLine,

        @Schema(description = "Ward name snapshot", example = "Ward 1")
        String shippingWardName,

        @Schema(description = "District name snapshot", example = "District 1")
        String shippingDistrictName,

        @Schema(description = "Province name snapshot", example = "Ho Chi Minh City")
        String shippingProvinceName,

        @Schema(description = "Immutable order item snapshots")
        List<BuyerOrderItemResponse> items,

        @Schema(description = "Order creation timestamp")
        Instant createdAt,

        @Schema(description = "Last update timestamp")
        Instant updatedAt
) {
}
