package com.shopee.monolith.modules.voucher.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
@Schema(description = "Result of validating a voucher code against an order subtotal")
public record ValidateVoucherResponse(
        String code,
        BigDecimal discountAmount
) {}
