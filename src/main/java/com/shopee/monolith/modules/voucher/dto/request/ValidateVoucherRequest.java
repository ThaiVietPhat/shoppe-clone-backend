package com.shopee.monolith.modules.voucher.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
@Schema(description = "Request payload to validate a voucher code against an order subtotal")
public record ValidateVoucherRequest(
        @NotBlank(message = "Code is required")
        @Schema(description = "Voucher code to validate", example = "WELCOME10")
        String code,

        @NotNull(message = "Order subtotal is required")
        @PositiveOrZero(message = "Order subtotal must be zero or positive")
        @Schema(description = "Items subtotal the discount would be applied against")
        BigDecimal orderSubtotal
) {}
