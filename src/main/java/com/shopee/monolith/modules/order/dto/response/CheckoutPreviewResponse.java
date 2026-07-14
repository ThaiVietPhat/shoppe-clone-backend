package com.shopee.monolith.modules.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Builder
@Schema(name = "CheckoutPreviewResponse", description = "Full cost breakdown for selected cart items — no inventory reserved")
public record CheckoutPreviewResponse(
        @Schema(description = "Per-shop grouped items and fees") List<CheckoutPreviewShopGroup> shops,
        @Schema(description = "Invalid items whose shop could not be resolved (variant or product inactive); "
                + "always carry valid=false and an invalidReasonCode") List<CheckoutPreviewItemResult> invalidItems,
        @Schema(description = "Sum of all shop itemsSubtotals") BigDecimal totalItemsSubtotal,
        @Schema(description = "Sum of all shop shippingFees") BigDecimal totalShippingFee,
        @Schema(description = "totalItemsSubtotal + totalShippingFee - discountAmount") BigDecimal grandTotal,
        @Schema(description = "True only when every item in every shop is valid") boolean allItemsValid,
        @Schema(description = "Address used for the estimate", nullable = true) UUID addressId,
        @Schema(description = "Cart version at time of preview — include in POST /api/orders to detect cart drift") long cartVersion,
        @Schema(description = "Discount amount from the requested voucher code, if valid", nullable = true) BigDecimal discountAmount,
        @Schema(description = "Error message if the requested voucher code could not be applied", nullable = true) String voucherError
) {}
