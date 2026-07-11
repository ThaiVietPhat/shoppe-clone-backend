package com.shopee.monolith.modules.user.dto.response;

import com.shopee.monolith.modules.media.dto.response.MediaAssetResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder
@Schema(description = "Response payload containing seller shop details")
public record ShopResponse(
        @Schema(description = "Shop unique ID", example = "4a123eb4-7b7d-4bad-9bdd-2b0d7b3dcb6d")
        UUID id,

        @Schema(description = "Shop owner unique user ID", example = "9b1deb4d-3b7d-4bad-9bdd-2b0d7b3dcb6d")
        UUID ownerId,

        @Schema(description = "Shop name", example = "Shopee Mall Demo")
        String name,

        @Schema(description = "Shop's business address, if set")
        AddressResponse address,

        @Schema(description = "Shop description", example = "Official store for demo products")
        String description,

        @Schema(description = "Shop rating (0.00 to 5.00)", example = "4.85")
        BigDecimal rating,

        @Schema(description = "Latest uploaded shop logo media, if any")
        MediaAssetResponse logo,

        @Schema(description = "Timestamp when the shop profile was created")
        Instant createdAt,

        @Schema(description = "Timestamp when the shop profile was last updated")
        Instant updatedAt
) {}
