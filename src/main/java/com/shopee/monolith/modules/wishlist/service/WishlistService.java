package com.shopee.monolith.modules.wishlist.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WishlistService {

    void add(UUID userId, UUID productId);

    void remove(UUID userId, UUID productId);

    PagedResponse<ProductCardResponse> list(UUID userId, Pageable pageable);

    /** Bulk heart-icon state for a set of products on a listing page. */
    Map<UUID, Boolean> checkStatus(UUID userId, List<UUID> productIds);
}
