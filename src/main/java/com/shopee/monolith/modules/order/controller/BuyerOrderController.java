package com.shopee.monolith.modules.order.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.common.response.SwaggerResponses;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderDetailResponse;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderSummaryResponse;
import com.shopee.monolith.modules.order.model.OrderStatus;
import com.shopee.monolith.modules.order.service.BuyerOrderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/buyer/orders")
@RequiredArgsConstructor
@Tag(name = "Buyer Orders", description = "Buyer-facing order list, detail and cancellation APIs")
public class BuyerOrderController {

    private static final int MAX_PAGE_SIZE = 100;

    private final BuyerOrderService buyerOrderService;

    @Operation(
            summary = "List current buyer's orders",
            description = "Paged list of the authenticated buyer's orders, newest first.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Orders returned.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @GetMapping
    public ApiResponse<PagedResponse<BuyerOrderSummaryResponse>> listOrders(
            @RequestParam(required = false) OrderStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ApiResponse.success(
                buyerOrderService.listOrders(claims.userId(), status, PageRequest.of(Math.max(page, 0), cappedSize)));
    }

    @Operation(
            summary = "Get buyer order detail",
            description = "Order detail with immutable address/item snapshots, payment info and timeline. "
                    + "Only the owning buyer can read it.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Order detail returned.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Order not found or not owned by the caller.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @GetMapping("/{orderId}")
    public ApiResponse<BuyerOrderDetailResponse> getOrderDetail(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(buyerOrderService.getOrderDetail(claims.userId(), orderId));
    }

    @Operation(
            summary = "Cancel checkout session via any order in it",
            description = "Cancels the entire checkout session that contains this order. "
                    + "All PENDING_PAYMENT orders in the session are cancelled and all reserved inventory is released. "
                    + "Any pending payment attempt for the session is expired so the VNPay redirect URL becomes invalid. "
                    + "Only allowed while the session is in PENDING_PAYMENT state. "
                    + "In a multi-shop checkout, cancelling one order cancels the whole session.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Checkout session and all its orders cancelled.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Order not found or not owned by the caller.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409", description = "Checkout session is not in a cancellable state (already paid, expired, or cancelled).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @PostMapping("/{orderId}/cancel")
    public ApiResponse<Void> cancelOrder(
            @PathVariable UUID orderId,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        buyerOrderService.cancelOrder(claims.userId(), orderId);
        return ApiResponse.success(null);
    }

    private void requireAuthenticated(AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }
}
