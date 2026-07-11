package com.shopee.monolith.modules.user.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.UUID;

@Builder
@Schema(description = "Request payload for creating a seller shop")
public record CreateShopRequest(
        @NotBlank(message = "Shop name is required")
        @Size(min = 3, max = 100, message = "Shop name must be between 3 and 100 characters")
        @Schema(description = "Name of the shop", example = "Shopee Mall Demo", requiredMode = Schema.RequiredMode.REQUIRED)
        String name,

        @Size(max = 1000, message = "Description must not exceed 1000 characters")
        @Schema(description = "Optional shop description", example = "Official store for demo products")
        String description,

        @NotNull(message = "Shop address is required")
        @Schema(description = "ID of one of the seller's own saved addresses to use as the shop's business address",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID addressId
) {}
