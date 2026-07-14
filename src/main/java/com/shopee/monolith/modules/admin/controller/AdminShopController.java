package com.shopee.monolith.modules.admin.controller;

import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.admin.dto.response.AdminShopResponse;
import com.shopee.monolith.modules.admin.service.AdminShopService;
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
@RequestMapping("/api/admin/shops")
@RequiredArgsConstructor
@Tag(name = "Admin - Shops", description = "Admin shop moderation APIs")
public class AdminShopController {

    private final AdminShopService adminShopService;

    @Operation(summary = "List shops", description = "Paginated list of all shops.", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ApiResponse<PagedResponse<AdminShopResponse>> listShops(
            @Parameter(description = "Page index (0-indexed)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(adminShopService.listShops(page, size));
    }

    @Operation(summary = "Suspend shop", description = "Suspends a shop, hiding it from moderation review pending further action.", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{shopId}/suspend")
    public ApiResponse<Void> suspendShop(@Parameter(description = "Shop unique ID") @PathVariable UUID shopId) {
        adminShopService.suspendShop(shopId);
        return ApiResponse.success();
    }

    @Operation(summary = "Reinstate shop", description = "Reinstates a previously suspended shop.", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{shopId}/reinstate")
    public ApiResponse<Void> reinstateShop(@Parameter(description = "Shop unique ID") @PathVariable UUID shopId) {
        adminShopService.reinstateShop(shopId);
        return ApiResponse.success();
    }

    @Operation(summary = "Verify shop", description = "Marks a shop as verified.", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{shopId}/verify")
    public ApiResponse<Void> verifyShop(@Parameter(description = "Shop unique ID") @PathVariable UUID shopId) {
        adminShopService.verifyShop(shopId);
        return ApiResponse.success();
    }
}
