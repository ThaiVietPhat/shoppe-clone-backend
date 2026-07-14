package com.shopee.monolith.modules.admin.dto.response;

import com.shopee.monolith.modules.user.model.ShopStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
@Schema(description = "Shop details for admin moderation")
public record AdminShopResponse(
        UUID id,
        UUID ownerId,
        String name,
        ShopStatus status,
        boolean verified,
        Instant createdAt
) {}
