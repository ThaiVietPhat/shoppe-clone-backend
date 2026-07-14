package com.shopee.monolith.modules.admin.dto.response;

import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
@Schema(description = "User details for admin moderation")
public record AdminUserResponse(
        UUID id,
        String email,
        Role role,
        UserStatus status,
        Instant createdAt
) {}
