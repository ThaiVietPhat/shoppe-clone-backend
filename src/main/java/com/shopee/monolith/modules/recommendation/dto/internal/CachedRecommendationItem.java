package com.shopee.monolith.modules.recommendation.dto.internal;

import com.shopee.monolith.modules.recommendation.dto.RecommendationReasonCode;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

/**
 * Last-known-good home recommendation entry persisted in Redis so a temporary AI outage
 * can keep serving the previous AI-backed picks instead of reverting to the newest-products fallback.
 */
@Builder
public record CachedRecommendationItem(
        UUID productId,
        List<RecommendationReasonCode> reasonCodes
) {
}
