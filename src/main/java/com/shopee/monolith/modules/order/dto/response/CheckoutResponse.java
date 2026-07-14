package com.shopee.monolith.modules.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
@Schema(name = "CheckoutResponse", description = "Response payload after successful checkout reservation")
public record CheckoutResponse(
        @Schema(description = "ID of the created checkout session", example = "d3b07384-d113-49c3-a3d8-3a54d6d63428")
        UUID checkoutSessionId,

        @Schema(description = "List of created order IDs", example = "[\"e4b07384-d113-49c3-a3d8-3a54d6d63429\"]")
        List<UUID> orderIds,

        @Schema(description = "Status of the checkout session", example = "PENDING_PAYMENT")
        String status,

        @Schema(description = "Sum of all item prices", example = "50.00")
        BigDecimal itemsSubtotal,

        @Schema(description = "Sum of all shipping fees", example = "30.00")
        BigDecimal shippingFee,

        @Schema(description = "Total checkout amount (itemsSubtotal + shippingFee - discountAmount)", example = "80.00")
        BigDecimal totalAmount,

        @Schema(description = "Expiration timestamp of the reservation", example = "2026-06-05T18:30:00Z")
        Instant expiresAt,

        @Schema(description = "Discount amount applied from the voucher code, if any", example = "10.00", nullable = true)
        BigDecimal discountAmount
) {}
