package com.shopee.monolith.modules.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
@Schema(name = "BuyerOrderDetailResponse", description = "Buyer order detail with immutable snapshots and timeline")
public record BuyerOrderDetailResponse(
        @Schema(description = "Order ID")
        UUID orderId,

        @Schema(description = "Checkout session the order belongs to")
        UUID checkoutSessionId,

        @Schema(description = "Shop ID")
        UUID shopId,

        @Schema(description = "Shop name resolved at read time", example = "Shopee Mall Demo")
        String shopName,

        @Schema(description = "Order status", example = "PAID")
        String status,

        @Schema(description = "Payment status", example = "PAID")
        String paymentStatus,

        @Schema(description = "Payment method used, if paid", example = "COD")
        String paymentMethod,

        @Schema(description = "Items subtotal snapshot", example = "120000.00")
        BigDecimal itemsSubtotal,

        @Schema(description = "Shipping fee snapshot", example = "30000.00")
        BigDecimal shippingFee,

        @Schema(description = "Grand total snapshot", example = "150000.00")
        BigDecimal totalAmount,

        @Schema(description = "Voucher discount applied to this order, if any", example = "0.00")
        BigDecimal discountAmount,

        @Schema(description = "Recipient name snapshot", example = "Nguyen Van A")
        String shippingRecipientName,

        @Schema(description = "Recipient phone snapshot", example = "0987654321")
        String shippingPhone,

        @Schema(description = "Address line snapshot", example = "123 Le Loi")
        String shippingAddressLine,

        @Schema(description = "Ward name snapshot", example = "Ward 1")
        String shippingWardName,

        @Schema(description = "District name snapshot", example = "District 1")
        String shippingDistrictName,

        @Schema(description = "Province name snapshot", example = "Ho Chi Minh City")
        String shippingProvinceName,

        @Schema(description = "Immutable order item snapshots")
        List<BuyerOrderItemResponse> items,

        @Schema(description = "Order timeline events in chronological order")
        List<BuyerOrderTimelineEvent> timeline,

        @Schema(description = "Order creation timestamp")
        Instant createdAt
) {
}
