package com.shopee.monolith.modules.admin.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.admin.dto.response.AdminUserResponse;

import java.util.UUID;

public interface AdminUserService {

    PagedResponse<AdminUserResponse> listUsers(int page, int size);

    void banUser(UUID userId);

    void unbanUser(UUID userId);
}
