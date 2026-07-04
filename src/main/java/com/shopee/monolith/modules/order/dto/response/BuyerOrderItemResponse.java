package com.shopee.monolith.modules.order.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

@Builder
@Schema(name = "BuyerOrderItemResponse", description = "Immutable order item snapshot")
public record BuyerOrderItemResponse(
        @Schema(description = "Order item ID")
        UUID id,

        @Schema(description = "Product variant ID")
        UUID variantId,

        @Schema(description = "Product name at checkout time", example = "Wireless Mouse")
        String productName,

        @Schema(description = "Variant name at checkout time", example = "Black")
        String variantName,

        @Schema(description = "Variant SKU at checkout time", example = "WM-BLK-01")
        String sku,

        @Schema(description = "Unit price snapshot", example = "120000.00")
        BigDecimal price,

        @Schema(description = "Quantity ordered", example = "2")
        int quantity,

        @Schema(description = "Line subtotal = price × quantity", example = "240000.00")
        BigDecimal subtotal,

        @Schema(description = "Whether the buyer already submitted a review for this item")
        boolean reviewed
) {
}
