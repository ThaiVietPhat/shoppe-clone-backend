package com.shopee.monolith.modules.order.dto.request;

import com.shopee.monolith.modules.order.model.ReturnReasonCategory;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record RequestReturnRequest(
        @NotNull
        ReturnReasonCategory reasonCategory,

        @Size(max = 2000)
        String description,

        List<UUID> evidenceMediaIds
) {}
