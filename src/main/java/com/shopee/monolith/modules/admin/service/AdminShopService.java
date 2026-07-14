package com.shopee.monolith.modules.admin.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.admin.dto.response.AdminShopResponse;

import java.util.UUID;

public interface AdminShopService {

    PagedResponse<AdminShopResponse> listShops(int page, int size);

    void suspendShop(UUID shopId);

    void reinstateShop(UUID shopId);

    void verifyShop(UUID shopId);
}
