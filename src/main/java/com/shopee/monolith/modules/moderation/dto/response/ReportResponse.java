package com.shopee.monolith.modules.moderation.dto.response;

import com.shopee.monolith.modules.moderation.model.ReportReasonCategory;
import com.shopee.monolith.modules.moderation.model.ReportStatus;
import com.shopee.monolith.modules.moderation.model.ReportTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
@Schema(description = "Report details")
public record ReportResponse(
        UUID id,
        UUID reporterId,
        ReportTargetType targetType,
        UUID targetId,
        ReportReasonCategory reasonCategory,
        String description,
        ReportStatus status,
        String resolutionNote,
        UUID resolvedBy,
        Instant resolvedAt,
        Instant createdAt
) {}
