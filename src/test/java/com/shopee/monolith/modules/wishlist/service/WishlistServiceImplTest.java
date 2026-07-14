package com.shopee.monolith.modules.wishlist.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.product.dto.internal.ProductLookupData;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.wishlist.entity.WishlistItem;
import com.shopee.monolith.modules.wishlist.repository.WishlistItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WishlistServiceImplTest {

    @Mock
    private WishlistItemRepository wishlistItemRepository;
    @Mock
    private ProductService productService;

    private WishlistServiceImpl wishlistService;

    private final UUID userId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        wishlistService = new WishlistServiceImpl(wishlistItemRepository, productService);
    }

    @Test
    void addWhenProductNotActiveShouldThrowProductNotFound() {
        when(productService.findActiveProductLookupDataById(productId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> wishlistService.add(userId, productId));
        assertEquals(ErrorCode.PRODUCT_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void addWhenAlreadyWishlistedShouldThrowAlreadyExists() {
        when(productService.findActiveProductLookupDataById(productId))
                .thenReturn(Optional.of(ProductLookupData.builder().id(productId).build()));
        when(wishlistItemRepository.saveAndFlush(any(WishlistItem.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        AppException ex = assertThrows(AppException.class, () -> wishlistService.add(userId, productId));
        assertEquals(ErrorCode.WISHLIST_ITEM_ALREADY_EXISTS, ex.getErrorCode());
    }

    @Test
    void addWhenEligibleShouldPersist() {
        when(productService.findActiveProductLookupDataById(productId))
                .thenReturn(Optional.of(ProductLookupData.builder().id(productId).build()));
        when(wishlistItemRepository.saveAndFlush(any(WishlistItem.class))).thenAnswer(inv -> inv.getArgument(0));

        wishlistService.add(userId, productId);
    }

    @Test
    void listShouldHydrateProductCardsPreservingPageMetadata() {
        WishlistItem item = WishlistItem.builder().userId(userId).productId(productId).build();
        Page<WishlistItem> page = new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);
        when(wishlistItemRepository.findAllByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, 20)))
                .thenReturn(page);
        when(productService.loadActiveProductCards(List.of(productId)))
                .thenReturn(List.of(ProductCardResponse.builder().id(productId).build()));

        PagedResponse<ProductCardResponse> result = wishlistService.list(userId, PageRequest.of(0, 20));

        assertEquals(1, result.items().size());
        assertEquals(productId, result.items().get(0).id());
        assertEquals(1, result.totalElements());
    }

    @Test
    void checkStatusShouldMarkWishlistedAndNonWishlisted() {
        UUID otherProductId = UUID.randomUUID();
        WishlistItem item = WishlistItem.builder().userId(userId).productId(productId).build();
        when(wishlistItemRepository.findAllByUserIdAndProductIdIn(userId, List.of(productId, otherProductId)))
                .thenReturn(List.of(item));

        Map<UUID, Boolean> result = wishlistService.checkStatus(userId, List.of(productId, otherProductId));

        assertTrue(result.get(productId));
        assertFalse(result.get(otherProductId));
    }
}
