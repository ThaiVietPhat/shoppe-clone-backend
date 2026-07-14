package com.shopee.monolith.modules.voucher.dto.request;

import com.shopee.monolith.modules.voucher.model.DiscountType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
@Schema(description = "Request payload to create a platform-wide voucher")
public record CreateVoucherRequest(
        @NotBlank(message = "Code is required")
        @Size(min = 3, max = 30, message = "Code must be between 3 and 30 characters")
        @Schema(description = "Unique voucher code", example = "WELCOME10")
        String code,

        @NotNull(message = "Discount type is required")
        DiscountType discountType,

        @NotNull(message = "Discount value is required")
        @Positive(message = "Discount value must be positive")
        BigDecimal discountValue,

        @PositiveOrZero(message = "Max discount amount must be zero or positive")
        BigDecimal maxDiscountAmount,

        @NotNull(message = "Min order amount is required")
        @PositiveOrZero(message = "Min order amount must be zero or positive")
        BigDecimal minOrderAmount,

        @Positive(message = "Usage limit must be positive")
        Integer usageLimit,

        @NotNull(message = "Start date is required")
        Instant startsAt,

        @NotNull(message = "Expiry date is required")
        Instant expiresAt
) {}
