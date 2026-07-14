package com.shopee.monolith.modules.admin.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.admin.dto.response.AdminShopResponse;
import com.shopee.monolith.modules.user.entity.Shop;
import com.shopee.monolith.modules.user.model.ShopStatus;
import com.shopee.monolith.modules.user.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminShopServiceImplTest {

    @Mock
    private ShopRepository shopRepository;

    private AdminShopServiceImpl adminShopService;

    @BeforeEach
    void setUp() {
        adminShopService = new AdminShopServiceImpl(shopRepository);
    }

    private Shop.ShopBuilder<?, ?> activeShop(UUID id) {
        return Shop.builder().id(id).ownerId(UUID.randomUUID()).name("Test Shop").status(ShopStatus.ACTIVE);
    }

    @Test
    void listShopsShouldMapPage() {
        Shop shop = activeShop(UUID.randomUUID()).build();
        Page<Shop> page = new PageImpl<>(List.of(shop), PageRequest.of(0, 20), 1);
        when(shopRepository.findAll(any(org.springframework.data.domain.Pageable.class))).thenReturn(page);

        PagedResponse<AdminShopResponse> result = adminShopService.listShops(0, 20);

        assertEquals(1, result.items().size());
        assertEquals(shop.getName(), result.items().get(0).name());
    }

    @Test
    void suspendShopWhenActiveShouldSuspend() {
        UUID shopId = UUID.randomUUID();
        Shop shop = activeShop(shopId).build();
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(shop));
        when(shopRepository.save(shop)).thenReturn(shop);

        adminShopService.suspendShop(shopId);

        assertEquals(ShopStatus.SUSPENDED, shop.getStatus());
    }

    @Test
    void suspendShopWhenAlreadySuspendedShouldThrow() {
        UUID shopId = UUID.randomUUID();
        Shop shop = activeShop(shopId).status(ShopStatus.SUSPENDED).build();
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(shop));

        AppException exception = assertThrows(AppException.class, () -> adminShopService.suspendShop(shopId));
        assertEquals(ErrorCode.SHOP_ALREADY_SUSPENDED, exception.getErrorCode());
    }

    @Test
    void reinstateShopWhenSuspendedShouldReinstate() {
        UUID shopId = UUID.randomUUID();
        Shop shop = activeShop(shopId).status(ShopStatus.SUSPENDED).build();
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(shop));
        when(shopRepository.save(shop)).thenReturn(shop);

        adminShopService.reinstateShop(shopId);

        assertEquals(ShopStatus.ACTIVE, shop.getStatus());
    }

    @Test
    void reinstateShopWhenAlreadyActiveShouldThrow() {
        UUID shopId = UUID.randomUUID();
        Shop shop = activeShop(shopId).build();
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(shop));

        AppException exception = assertThrows(AppException.class, () -> adminShopService.reinstateShop(shopId));
        assertEquals(ErrorCode.SHOP_ALREADY_ACTIVE, exception.getErrorCode());
    }

    @Test
    void verifyShopShouldSetVerifiedTrue() {
        UUID shopId = UUID.randomUUID();
        Shop shop = activeShop(shopId).build();
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(shop));
        when(shopRepository.save(shop)).thenReturn(shop);

        adminShopService.verifyShop(shopId);

        assertTrue(shop.isVerified());
    }

    @Test
    void suspendShopWhenMissingShouldThrowNotFound() {
        UUID shopId = UUID.randomUUID();
        when(shopRepository.findById(shopId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> adminShopService.suspendShop(shopId));
        assertEquals(ErrorCode.SHOP_NOT_FOUND, exception.getErrorCode());
    }
}
