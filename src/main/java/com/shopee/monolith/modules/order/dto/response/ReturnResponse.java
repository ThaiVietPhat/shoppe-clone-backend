package com.shopee.monolith.modules.order.dto.response;

import com.shopee.monolith.modules.order.model.ReturnReasonCategory;
import com.shopee.monolith.modules.order.model.ReturnStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Builder
@Schema(description = "Return/dispute request details")
public record ReturnResponse(
        UUID id,
        UUID orderId,
        UUID buyerId,
        ReturnReasonCategory reasonCategory,
        String description,
        ReturnStatus status,
        BigDecimal refundAmount,
        String resolutionNote,
        Instant resolvedAt,
        Instant requestedAt,
        List<UUID> evidenceMediaIds
) {}
