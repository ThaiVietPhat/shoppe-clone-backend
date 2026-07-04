package com.shopee.monolith.modules.product.dto.internal;

import lombok.Builder;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Builder
public record VariantCartSummaryData(
        UUID variantId,
        UUID productId,
        UUID shopId,
        String shopName,
        String productName,
        String variantName,
        Map<String, String> optionLabels,
        String sku,
        BigDecimal price,
        String coverImageUrl,
        int availableStock,
        boolean checkoutEligible
) {}
