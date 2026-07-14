package com.shopee.monolith.modules.moderation.dto.request;

import com.shopee.monolith.modules.moderation.model.ReportReasonCategory;
import com.shopee.monolith.modules.moderation.model.ReportTargetType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.UUID;

@Builder
@Schema(description = "Request payload to report a product or shop for a policy violation")
public record CreateReportRequest(
        @NotNull(message = "Target type is required")
        ReportTargetType targetType,

        @NotNull(message = "Target ID is required")
        UUID targetId,

        @NotNull(message = "Reason category is required")
        ReportReasonCategory reasonCategory,

        @Size(max = 2000, message = "Description must be at most 2000 characters")
        String description
) {}
