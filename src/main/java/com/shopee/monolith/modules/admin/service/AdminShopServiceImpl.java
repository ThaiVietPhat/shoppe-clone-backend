package com.shopee.monolith.modules.admin.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.admin.dto.response.AdminShopResponse;
import com.shopee.monolith.modules.user.entity.Shop;
import com.shopee.monolith.modules.user.model.ShopStatus;
import com.shopee.monolith.modules.user.repository.ShopRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AdminShopServiceImpl implements AdminShopService {

    private final ShopRepository shopRepository;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<AdminShopResponse> listShops(int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        var shopPage = shopRepository.findAll(pageable).map(this::toResponse);
        return PagedResponse.from(shopPage);
    }

    @Override
    @Transactional
    public void suspendShop(UUID shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
        if (shop.getStatus() == ShopStatus.SUSPENDED) {
            throw new AppException(ErrorCode.SHOP_ALREADY_SUSPENDED);
        }
        shop.suspend();
        shopRepository.save(shop);
    }

    @Override
    @Transactional
    public void reinstateShop(UUID shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
        if (shop.getStatus() == ShopStatus.ACTIVE) {
            throw new AppException(ErrorCode.SHOP_ALREADY_ACTIVE);
        }
        shop.reinstate();
        shopRepository.save(shop);
    }

    @Override
    @Transactional
    public void verifyShop(UUID shopId) {
        Shop shop = shopRepository.findById(shopId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
        shop.verify();
        shopRepository.save(shop);
    }

    private AdminShopResponse toResponse(Shop shop) {
        return AdminShopResponse.builder()
                .id(shop.getId())
                .ownerId(shop.getOwnerId())
                .name(shop.getName())
                .status(shop.getStatus())
                .verified(shop.isVerified())
                .createdAt(shop.getCreatedAt())
                .build();
    }
}
