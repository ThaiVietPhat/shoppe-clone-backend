package com.shopee.monolith.modules.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.UUID;

@Builder
@Schema(name = "CheckoutPreviewRequest", description = "Request for checkout cost preview without reserving inventory")
public record CheckoutPreviewRequest(
        @Schema(
                description = "Address ID for shipping cost estimate. If null, the user's default address is used.",
                example = "7a123eb4-7b7d-4bad-9bdd-2b0d7b3dcb6d",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        UUID addressId,

        @Schema(
                description = "Voucher code to preview a discount for. If invalid, the preview still succeeds "
                        + "with voucherError populated instead of failing the whole request.",
                example = "WELCOME10",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        String voucherCode
) {}
