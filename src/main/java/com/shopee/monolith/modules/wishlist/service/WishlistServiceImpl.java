package com.shopee.monolith.modules.wishlist.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.wishlist.entity.WishlistItem;
import com.shopee.monolith.modules.wishlist.repository.WishlistItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class WishlistServiceImpl implements WishlistService {

    private final WishlistItemRepository wishlistItemRepository;
    private final ProductService productService;

    @Override
    @Transactional
    public void add(UUID userId, UUID productId) {
        productService.findActiveProductLookupDataById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        try {
            wishlistItemRepository.saveAndFlush(WishlistItem.builder()
                    .userId(userId)
                    .productId(productId)
                    .build());
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.WISHLIST_ITEM_ALREADY_EXISTS);
        }
    }

    @Override
    @Transactional
    public void remove(UUID userId, UUID productId) {
        wishlistItemRepository.deleteByUserIdAndProductId(userId, productId);
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ProductCardResponse> list(UUID userId, Pageable pageable) {
        Page<WishlistItem> page = wishlistItemRepository.findAllByUserIdOrderByCreatedAtDesc(userId, pageable);
        List<UUID> productIds = page.getContent().stream().map(WishlistItem::getProductId).toList();
        List<ProductCardResponse> cards = productService.loadActiveProductCards(productIds);
        return PagedResponse.from(page, cards);
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Boolean> checkStatus(UUID userId, List<UUID> productIds) {
        Set<UUID> wishlisted = wishlistItemRepository.findAllByUserIdAndProductIdIn(userId, productIds).stream()
                .map(WishlistItem::getProductId)
                .collect(Collectors.toCollection(HashSet::new));
        return productIds.stream().distinct()
                .collect(Collectors.toMap(id -> id, wishlisted::contains));
    }
}
