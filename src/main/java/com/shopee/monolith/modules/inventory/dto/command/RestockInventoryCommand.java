package com.shopee.monolith.modules.inventory.dto.command;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record RestockInventoryCommand(
        @NotNull
        UUID variantId,

        @Min(1)
        int quantity
) {}
