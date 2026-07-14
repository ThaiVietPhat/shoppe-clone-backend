package com.shopee.monolith.modules.order.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.order.dto.request.RequestReturnRequest;
import com.shopee.monolith.modules.order.dto.response.ReturnResponse;
import com.shopee.monolith.modules.order.service.ReturnService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/buyer/orders/{orderId}/return")
@RequiredArgsConstructor
@Tag(name = "Returns", description = "Buyer-facing return/refund request for a delivered order")
public class ReturnController {

    private final ReturnService returnService;

    @Operation(
            summary = "Request a return/refund",
            description = "Only allowed when the order is DELIVERED and within the return window. "
                    + "Refund settles as a wallet credit, not a real gateway/cash reversal.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping
    public ApiResponse<ReturnResponse> requestReturn(
            @PathVariable UUID orderId,
            @Valid @RequestBody RequestReturnRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(returnService.requestReturn(claims.userId(), orderId, request));
    }

    @Operation(
            summary = "Get the return request for an order, if any",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @GetMapping
    public ApiResponse<ReturnResponse> getReturn(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(returnService.getReturnForOrder(claims.userId(), orderId).orElse(null));
    }

    private void requireAuthenticated(AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }
}
