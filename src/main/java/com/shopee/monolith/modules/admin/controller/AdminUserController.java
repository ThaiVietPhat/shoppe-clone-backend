package com.shopee.monolith.modules.admin.controller;

import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.admin.dto.response.AdminUserResponse;
import com.shopee.monolith.modules.admin.service.AdminUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@Tag(name = "Admin - Users", description = "Admin user moderation APIs")
public class AdminUserController {

    private final AdminUserService adminUserService;

    @Operation(summary = "List users", description = "Paginated list of all users.", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ApiResponse<PagedResponse<AdminUserResponse>> listUsers(
            @Parameter(description = "Page index (0-indexed)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(adminUserService.listUsers(page, size));
    }

    @Operation(summary = "Ban user", description = "Locks the user account and revokes all active sessions.", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{userId}/ban")
    public ApiResponse<Void> banUser(@Parameter(description = "User unique ID") @PathVariable UUID userId) {
        adminUserService.banUser(userId);
        return ApiResponse.success();
    }

    @Operation(summary = "Unban user", description = "Reactivates a previously banned user account.", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{userId}/unban")
    public ApiResponse<Void> unbanUser(@Parameter(description = "User unique ID") @PathVariable UUID userId) {
        adminUserService.unbanUser(userId);
        return ApiResponse.success();
    }
}
