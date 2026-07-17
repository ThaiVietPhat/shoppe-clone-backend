package com.shopee.monolith.modules.payment.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.SwaggerResponses;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.auth.security.ClientIpResolver;
import com.shopee.monolith.modules.payment.dto.request.InitiatePaymentRequest;
import com.shopee.monolith.modules.payment.dto.response.PaymentStatusResponse;
import com.shopee.monolith.modules.payment.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/payments")
@RequiredArgsConstructor
@Tag(name = "Payments", description = "Payment attempt initiation and buyer-facing payment status APIs")
public class PaymentController {

    private final PaymentService paymentService;
    private final ClientIpResolver clientIpResolver;

    @Operation(
            summary = "Initiate a payment attempt",
            description = "Creates a payment attempt for the buyer's PENDING_PAYMENT checkout session. "
                    + "COD confirms inventory and the order immediately (status CONFIRMED, fulfillment "
                    + "READY_TO_SHIP) but leaves it UNPAID until the seller marks it delivered — cash is "
                    + "collected on delivery, not at initiate time. VNPAY returns a sandbox payment URL "
                    + "in nextAction and only marks the order PAID once the webhook confirms the gateway "
                    + "charge. Only one non-terminal attempt may exist per session.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Payment attempt created or existing pending attempt returned.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Checkout session not found or not owned by the caller.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Session not payable, or another payment attempt is in progress.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @PostMapping("/initiate")
    public ApiResponse<PaymentStatusResponse> initiatePayment(
            @Valid @RequestBody InitiatePaymentRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims,
            HttpServletRequest httpRequest) {
        requireAuthenticated(claims);
        String clientIp = clientIpResolver.resolveIp(httpRequest);
        return ApiResponse.success(paymentService.initiatePayment(claims.userId(), request, clientIp));
    }

    @Operation(
            summary = "Get payment status of a checkout session",
            description = "Polling endpoint for payment return pages. Returns the latest attempt status, "
                    + "order IDs, next action and reconciliation reason if any.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Payment status returned.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Checkout session not found or not owned by the caller.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @GetMapping("/status/{checkoutSessionId}")
    public ApiResponse<PaymentStatusResponse> getPaymentStatus(
            @PathVariable UUID checkoutSessionId,
            @AuthenticationPrincipal AccessTokenClaims claims,
            HttpServletRequest httpRequest) {
        requireAuthenticated(claims);
        String clientIp = clientIpResolver.resolveIp(httpRequest);
        return ApiResponse.success(paymentService.getPaymentStatus(checkoutSessionId, claims.userId(), clientIp));
    }

    private void requireAuthenticated(AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }
}
