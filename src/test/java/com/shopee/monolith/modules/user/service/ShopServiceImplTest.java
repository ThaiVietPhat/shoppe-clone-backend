package com.shopee.monolith.modules.user.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.media.dto.internal.MediaOwnerTypeCode;
import com.shopee.monolith.modules.media.dto.internal.MediaPurposeCode;
import com.shopee.monolith.modules.media.service.MediaService;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.dto.internal.UserAuthenticationData;
import com.shopee.monolith.modules.user.dto.request.CreateShopRequest;
import com.shopee.monolith.modules.user.dto.request.UpdateShopRequest;
import com.shopee.monolith.modules.user.dto.response.AddressResponse;
import com.shopee.monolith.modules.user.dto.response.ShopResponse;
import com.shopee.monolith.modules.user.entity.Shop;
import com.shopee.monolith.modules.user.mapper.ShopMapper;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.ShopRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShopServiceImplTest {

    @Mock
    private ShopRepository shopRepository;

    @Mock
    private ShopMapper shopMapper;

    @Mock
    private UserService userService;

    @Mock
    private MediaService mediaService;

    @Mock
    private AddressService addressService;

    @InjectMocks
    private ShopServiceImpl shopService;

    private final UUID ownerId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();
    private final UUID addressId = UUID.randomUUID();
    private CreateShopRequest createRequest;
    private UpdateShopRequest updateRequest;
    private UserAuthenticationData activeUser;
    private UserAuthenticationData inactiveUser;
    private Shop shop;
    private ShopResponse shopResponse;
    private ShopLookupData lookupData;
    private AddressResponse addressResponse;

    @BeforeEach
    void setUp() {
        createRequest = CreateShopRequest.builder()
                .name("Official Shop")
                .description("Shop description")
                .addressId(addressId)
                .build();

        updateRequest = UpdateShopRequest.builder()
                .name("Updated Shop")
                .description("Updated description")
                .build();

        activeUser = UserAuthenticationData.builder()
                .id(ownerId)
                .email("seller@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();

        inactiveUser = UserAuthenticationData.builder()
                .id(ownerId)
                .email("seller@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.PENDING_VERIFICATION)
                .build();

        shop = Shop.builder()
                .id(shopId)
                .ownerId(ownerId)
                .addressId(addressId)
                .name("Official Shop")
                .description("Shop description")
                .rating(BigDecimal.ZERO)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        addressResponse = AddressResponse.builder()
                .id(addressId)
                .userId(ownerId)
                .recipientName("Official Shop")
                .phone("0987654321")
                .addressLine("123 Main Street")
                .wardCode("20314")
                .wardName("Phường Liễu Giai")
                .districtCode("1442")
                .districtName("Quận Ba Đình")
                .provinceCode("201")
                .provinceName("Thành phố Hà Nội")
                .isDefault(true)
                .build();

        shopResponse = ShopResponse.builder()
                .id(shopId)
                .ownerId(ownerId)
                .name("Official Shop")
                .address(addressResponse)
                .description("Shop description")
                .rating(BigDecimal.ZERO)
                .createdAt(shop.getCreatedAt())
                .updatedAt(shop.getUpdatedAt())
                .build();

        lookupData = ShopLookupData.builder()
                .id(shopId)
                .ownerId(ownerId)
                .name("Official Shop")
                .description("Shop description")
                .rating(BigDecimal.ZERO)
                .build();

        org.mockito.Mockito.lenient()
                .when(mediaService.findLatestReadyMedia(shopId, MediaOwnerTypeCode.SHOP, MediaPurposeCode.SHOP_LOGO))
                .thenReturn(Optional.empty());

        org.mockito.Mockito.lenient()
                .when(addressService.findAddressByIdAndUserId(addressId, ownerId))
                .thenReturn(Optional.of(addressResponse));
    }

    @Test
    void createShopWhenUserActiveAndNewShopShouldSucceed() {
        when(userService.findAuthenticationDataById(ownerId)).thenReturn(Optional.of(activeUser));
        when(shopRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(shopRepository.saveAndFlush(any(Shop.class))).thenReturn(shop);

        ShopResponse result = shopService.createShop(ownerId, createRequest);

        assertEquals(shopResponse, result);
        verify(shopRepository).saveAndFlush(any(Shop.class));
        verify(userService).promoteToSeller(ownerId);
    }

    @Test
    void createShopWhenUserNotFoundShouldThrowUserNotFound() {
        when(userService.findAuthenticationDataById(ownerId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> shopService.createShop(ownerId, createRequest));

        assertEquals(ErrorCode.USER_NOT_FOUND, ex.getErrorCode());
        verify(shopRepository, never()).saveAndFlush(any(Shop.class));
    }

    @Test
    void createShopWhenUserNotActiveShouldThrowAccountNotActive() {
        when(userService.findAuthenticationDataById(ownerId)).thenReturn(Optional.of(inactiveUser));

        AppException ex = assertThrows(AppException.class, () -> shopService.createShop(ownerId, createRequest));

        assertEquals(ErrorCode.ACCOUNT_NOT_ACTIVE, ex.getErrorCode());
        verify(shopRepository, never()).saveAndFlush(any(Shop.class));
    }

    @Test
    void createShopWhenAddressDoesNotBelongToOwnerShouldThrowAddressNotFound() {
        when(userService.findAuthenticationDataById(ownerId)).thenReturn(Optional.of(activeUser));
        when(shopRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(addressService.findAddressByIdAndUserId(addressId, ownerId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> shopService.createShop(ownerId, createRequest));

        assertEquals(ErrorCode.ADDRESS_NOT_FOUND, ex.getErrorCode());
        verify(shopRepository, never()).saveAndFlush(any(Shop.class));
        verify(userService, never()).promoteToSeller(any());
    }

    @Test
    void createShopWhenShopAlreadyExistsShouldThrowShopAlreadyExists() {
        when(userService.findAuthenticationDataById(ownerId)).thenReturn(Optional.of(activeUser));
        when(shopRepository.existsByOwnerId(ownerId)).thenReturn(true);

        AppException ex = assertThrows(AppException.class, () -> shopService.createShop(ownerId, createRequest));

        assertEquals(ErrorCode.SHOP_ALREADY_EXISTS, ex.getErrorCode());
        verify(shopRepository, never()).saveAndFlush(any(Shop.class));
    }

    @Test
    void createShopWhenSaveThrowsDataIntegrityViolationShouldThrowShopAlreadyExists() {
        when(userService.findAuthenticationDataById(ownerId)).thenReturn(Optional.of(activeUser));
        when(shopRepository.existsByOwnerId(ownerId)).thenReturn(false);
        when(shopRepository.saveAndFlush(any(Shop.class))).thenThrow(DataIntegrityViolationException.class);

        AppException ex = assertThrows(AppException.class, () -> shopService.createShop(ownerId, createRequest));

        assertEquals(ErrorCode.SHOP_ALREADY_EXISTS, ex.getErrorCode());
        verify(userService, never()).promoteToSeller(any());
    }

    @Test
    void getShopByOwnerIdWhenShopExistsShouldReturnShop() {
        when(shopRepository.findByOwnerId(ownerId)).thenReturn(Optional.of(shop));

        ShopResponse result = shopService.getShopByOwnerId(ownerId);

        assertEquals(shopResponse, result);
    }

    @Test
    void getShopByOwnerIdWhenShopDoesNotExistShouldThrowShopNotFound() {
        when(shopRepository.findByOwnerId(ownerId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> shopService.getShopByOwnerId(ownerId));

        assertEquals(ErrorCode.SHOP_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void getShopByIdWhenShopExistsShouldReturnShop() {
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(shop));

        ShopResponse result = shopService.getShopById(shopId);

        assertEquals(shopResponse, result);
    }

    @Test
    void getShopByIdWhenShopDoesNotExistShouldThrowShopNotFound() {
        when(shopRepository.findById(shopId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> shopService.getShopById(shopId));

        assertEquals(ErrorCode.SHOP_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void updateShopWhenShopExistsShouldUpdateShopDetails() {
        when(shopRepository.findByOwnerId(ownerId)).thenReturn(Optional.of(shop));
        when(shopRepository.saveAndFlush(shop)).thenReturn(shop);

        ShopResponse result = shopService.updateShop(ownerId, updateRequest);

        assertEquals("Updated Shop", result.name());
        assertEquals("Updated description", result.description());
        assertEquals("Updated Shop", shop.getName());
        assertEquals("Updated description", shop.getDescription());
    }

    @Test
    void updateShopWhenShopDoesNotExistShouldThrowShopNotFound() {
        when(shopRepository.findByOwnerId(ownerId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class, () -> shopService.updateShop(ownerId, updateRequest));

        assertEquals(ErrorCode.SHOP_NOT_FOUND, ex.getErrorCode());
        verify(shopRepository, never()).saveAndFlush(any(Shop.class));
    }

    @Test
    void findShopLookupDataByIdWhenShopExistsShouldReturnLookupData() {
        when(shopRepository.findById(shopId)).thenReturn(Optional.of(shop));
        when(shopMapper.toLookupData(shop)).thenReturn(lookupData);

        Optional<ShopLookupData> result = shopService.findShopLookupDataById(shopId);

        assertTrue(result.isPresent());
        assertEquals(lookupData, result.get());
    }

    @Test
    void findShopLookupDataByIdWhenShopDoesNotExistShouldReturnEmpty() {
        when(shopRepository.findById(shopId)).thenReturn(Optional.empty());

        Optional<ShopLookupData> result = shopService.findShopLookupDataById(shopId);

        assertFalse(result.isPresent());
    }

    @Test
    void findShopLookupDataByIdsWhenShopsExistShouldReturnLookupMap() {
        when(shopRepository.findAllById(List.of(shopId))).thenReturn(List.of(shop));
        when(shopMapper.toLookupData(shop)).thenReturn(lookupData);

        Map<UUID, ShopLookupData> result = shopService.findShopLookupDataByIds(List.of(shopId));

        assertEquals(1, result.size());
        assertEquals(lookupData, result.get(shopId));
    }
}
