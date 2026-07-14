package com.shopee.monolith.modules.order.dto.request;

import jakarta.validation.constraints.Size;

public record ResolveReturnRequest(
        @Size(max = 1000)
        String resolutionNote
) {}
