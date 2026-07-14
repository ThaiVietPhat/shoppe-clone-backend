package com.shopee.monolith.modules.voucher.dto.response;

import com.shopee.monolith.modules.voucher.model.DiscountType;
import com.shopee.monolith.modules.voucher.model.VoucherStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder
@Schema(description = "Voucher details")
public record VoucherResponse(
        UUID id,
        String code,
        DiscountType discountType,
        BigDecimal discountValue,
        BigDecimal maxDiscountAmount,
        BigDecimal minOrderAmount,
        Integer usageLimit,
        int usedCount,
        Instant startsAt,
        Instant expiresAt,
        VoucherStatus status,
        Instant createdAt
) {}
