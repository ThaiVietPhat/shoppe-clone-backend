package com.shopee.monolith.modules.cart.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Builder
@Schema(description = "Details of a single item in the shopping cart")
public record CartItemResponse(
        @Schema(description = "ID of the product variant")
        UUID variantId,

        @Schema(description = "ID of the product")
        UUID productId,

        @Schema(description = "ID of the shop owning this product")
        UUID shopId,

        @Schema(description = "Name of the shop owning this product")
        String shopName,

        @Schema(description = "Name of the product")
        String productName,

        @Schema(description = "Name of the product variant")
        String variantName,

        @Schema(description = "Option labels for display (e.g. color/size/storage)",
                example = "{\"color\": \"Black Titanium\", \"storage\": \"256GB\"}")
        Map<String, String> optionLabels,

        @Schema(description = "SKU code of the product variant")
        String sku,

        @Schema(description = "Unit price of the product variant")
        BigDecimal price,

        @Schema(description = "Public URL of the product's cover image (null if no cover)")
        String coverImageUrl,

        @Schema(description = "Available stock for this variant", example = "42")
        int availableStock,

        @Schema(description = "Whether this item can be checked out (active + positive price + in stock)")
        boolean checkoutEligible,

        @Schema(description = "Quantity of this variant in the cart")
        int quantity,

        @Schema(description = "Whether this item is selected for checkout")
        boolean selected
) {}
