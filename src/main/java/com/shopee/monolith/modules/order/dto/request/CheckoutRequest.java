package com.shopee.monolith.modules.order.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import java.util.UUID;

@Builder
@Schema(name = "CheckoutRequest", description = "Payload for order checkout creation request")
public record CheckoutRequest(
        @Schema(
                description = "Address ID to use for shipping. If null, the user's default address will be used.",
                example = "7a123eb4-7b7d-4bad-9bdd-2b0d7b3dcb6d",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        UUID addressId,

        @Schema(
                description = "Voucher code to apply to this checkout. Re-validated server-side against the final total.",
                example = "WELCOME10",
                requiredMode = Schema.RequiredMode.NOT_REQUIRED
        )
        String voucherCode
) {}
