package com.shopee.monolith.modules.wishlist.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.wishlist.dto.request.WishlistCheckRequest;
import com.shopee.monolith.modules.wishlist.service.WishlistService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/wishlist")
@RequiredArgsConstructor
@Tag(name = "Wishlist", description = "Buyer product wishlist/favorites")
public class WishlistController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WishlistService wishlistService;

    @Operation(summary = "Add a product to the wishlist", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{productId}")
    public ApiResponse<Void> add(@PathVariable UUID productId, @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        wishlistService.add(claims.userId(), productId);
        return ApiResponse.success();
    }

    @Operation(summary = "Remove a product from the wishlist", security = @SecurityRequirement(name = "bearerAuth"))
    @DeleteMapping("/{productId}")
    public ApiResponse<Void> remove(@PathVariable UUID productId, @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        wishlistService.remove(claims.userId(), productId);
        return ApiResponse.success();
    }

    @Operation(summary = "Paged wishlist, newest first", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ApiResponse<PagedResponse<ProductCardResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ApiResponse.success(wishlistService.list(claims.userId(), PageRequest.of(Math.max(page, 0), cappedSize)));
    }

    @Operation(
            summary = "Bulk wishlist heart-icon state",
            description = "Returns which of the given product IDs are in the caller's wishlist.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/check")
    public ApiResponse<Map<UUID, Boolean>> checkStatus(
            @Valid @RequestBody WishlistCheckRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(wishlistService.checkStatus(claims.userId(), request.productIds()));
    }

    private void requireAuthenticated(AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }
}
