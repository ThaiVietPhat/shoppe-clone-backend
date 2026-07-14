package com.shopee.monolith.modules.wishlist.dto.request;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.UUID;

public record WishlistCheckRequest(
        @NotEmpty
        List<UUID> productIds
) {}
