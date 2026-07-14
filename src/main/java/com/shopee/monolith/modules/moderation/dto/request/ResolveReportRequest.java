package com.shopee.monolith.modules.moderation.dto.request;

import com.shopee.monolith.modules.moderation.model.ReportStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

@Builder
@Schema(description = "Request payload to resolve or reject a report")
public record ResolveReportRequest(
        @NotNull(message = "Outcome is required")
        @Schema(description = "Must be RESOLVED or REJECTED", example = "RESOLVED")
        ReportStatus outcome,

        @Size(max = 2000, message = "Note must be at most 2000 characters")
        String note
) {}
