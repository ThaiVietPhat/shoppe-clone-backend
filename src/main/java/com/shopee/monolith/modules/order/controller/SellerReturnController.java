package com.shopee.monolith.modules.order.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.order.dto.request.ResolveReturnRequest;
import com.shopee.monolith.modules.order.dto.response.ReturnResponse;
import com.shopee.monolith.modules.order.model.ReturnStatus;
import com.shopee.monolith.modules.order.service.ReturnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/seller/returns")
@RequiredArgsConstructor
@Tag(name = "Seller Returns", description = "Seller-scoped return/dispute resolution — single-level approve/reject")
public class SellerReturnController {

    private static final int MAX_PAGE_SIZE = 100;

    private final ReturnService returnService;

    @Operation(
            summary = "List return requests for the current seller's shop",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping
    public ApiResponse<PagedResponse<ReturnResponse>> listReturns(
            @Parameter(description = "Optional status filter", example = "REQUESTED")
            @RequestParam(required = false) ReturnStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ApiResponse.success(returnService.listSellerReturns(
                claims.userId(), status, PageRequest.of(Math.max(page, 0), cappedSize)));
    }

    @Operation(
            summary = "Approve a return request",
            description = "Refunds the buyer's wallet, claws back the seller's wallet earnings for the "
                    + "order, and restocks the returned items — all atomically.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/{returnId}/approve")
    public ApiResponse<ReturnResponse> approveReturn(
            @PathVariable UUID returnId,
            @Valid @RequestBody ResolveReturnRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(returnService.approveReturn(claims.userId(), returnId, request));
    }

    @Operation(summary = "Reject a return request", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/{returnId}/reject")
    public ApiResponse<ReturnResponse> rejectReturn(
            @PathVariable UUID returnId,
            @Valid @RequestBody ResolveReturnRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(returnService.rejectReturn(claims.userId(), returnId, request));
    }

    private void requireAuthenticated(AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }
}
