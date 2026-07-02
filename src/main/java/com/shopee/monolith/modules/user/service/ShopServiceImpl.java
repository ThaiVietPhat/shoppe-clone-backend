package com.shopee.monolith.modules.user.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.media.dto.internal.MediaOwnerTypeCode;
import com.shopee.monolith.modules.media.dto.internal.MediaPurposeCode;
import com.shopee.monolith.modules.media.dto.response.MediaAssetResponse;
import com.shopee.monolith.modules.media.service.MediaService;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.dto.internal.UserAuthenticationData;
import com.shopee.monolith.modules.user.dto.request.CreateShopRequest;
import com.shopee.monolith.modules.user.dto.request.UpdateShopRequest;
import com.shopee.monolith.modules.user.dto.response.ShopResponse;
import com.shopee.monolith.modules.user.entity.Shop;
import com.shopee.monolith.modules.user.mapper.ShopMapper;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShopServiceImpl implements ShopService {

    private final ShopRepository shopRepository;
    private final ShopMapper shopMapper;
    private final UserService userService;
    private final MediaService mediaService;

    @Override
    @Transactional
    public ShopResponse createShop(UUID ownerId, CreateShopRequest request) {
        UserAuthenticationData userAuth = userService.findAuthenticationDataById(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (userAuth.status() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        if (shopRepository.existsByOwnerId(ownerId)) {
            throw new AppException(ErrorCode.SHOP_ALREADY_EXISTS);
        }

        Shop shop = Shop.builder()
                .ownerId(ownerId)
                .name(request.name())
                .description(request.description())
                .build();

        try {
            Shop savedShop = shopRepository.saveAndFlush(shop);
            userService.promoteToSeller(ownerId);
            return toResponse(savedShop);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.SHOP_ALREADY_EXISTS);
        }
    }

    @Override
    public ShopResponse getShopByOwnerId(UUID ownerId) {
        Shop shop = shopRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
        return toResponse(shop);
    }

    @Override
    public ShopResponse getShopById(UUID shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
        return toResponse(shop);
    }

    @Override
    @Transactional
    public ShopResponse updateShop(UUID ownerId, UpdateShopRequest request) {
        Shop shop = shopRepository.findByOwnerId(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));

        shop.update(request.name(), request.description());
        Shop updatedShop = shopRepository.saveAndFlush(shop);
        return toResponse(updatedShop);
    }

    @Override
    public Optional<ShopLookupData> findShopLookupDataById(UUID shopId) {
        return shopRepository.findById(shopId)
                .map(shopMapper::toLookupData);
    }

    @Override
    public Map<UUID, ShopLookupData> findShopLookupDataByIds(Collection<UUID> shopIds) {
        if (shopIds == null || shopIds.isEmpty()) {
            return Map.of();
        }
        return shopRepository.findAllById(shopIds).stream()
                .map(shopMapper::toLookupData)
                .collect(Collectors.toMap(ShopLookupData::id, lookup -> lookup));
    }

    @Override
    public Optional<ShopLookupData> findShopLookupDataByOwnerId(UUID ownerId) {
        return shopRepository.findByOwnerId(ownerId)
                .map(shopMapper::toLookupData);
    }

    private ShopResponse toResponse(Shop shop) {
        MediaAssetResponse logo = mediaService.findLatestReadyMedia(shop.getId(), MediaOwnerTypeCode.SHOP, MediaPurposeCode.SHOP_LOGO)
                .orElse(null);
        return ShopResponse.builder()
                .id(shop.getId())
                .ownerId(shop.getOwnerId())
                .name(shop.getName())
                .description(shop.getDescription())
                .rating(shop.getRating())
                .logo(logo)
                .createdAt(shop.getCreatedAt())
                .updatedAt(shop.getUpdatedAt())
                .build();
    }
}
