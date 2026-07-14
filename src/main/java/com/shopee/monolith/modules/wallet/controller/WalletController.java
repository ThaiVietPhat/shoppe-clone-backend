package com.shopee.monolith.modules.wallet.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.wallet.dto.request.WithdrawRequest;
import com.shopee.monolith.modules.wallet.dto.response.WalletResponse;
import com.shopee.monolith.modules.wallet.dto.response.WalletTransactionResponse;
import com.shopee.monolith.modules.wallet.service.WalletService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/wallet")
@RequiredArgsConstructor
@Tag(name = "Wallet", description = "Buyer refund credit and seller earnings ledger + mock instant withdraw")
public class WalletController {

    private static final int MAX_PAGE_SIZE = 100;

    private final WalletService walletService;

    @Operation(summary = "Get current user's wallet balance", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ApiResponse<WalletResponse> getWallet(@AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(walletService.getWallet(claims.userId()));
    }

    @Operation(summary = "Paged wallet ledger history, newest first", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/transactions")
    public ApiResponse<PagedResponse<WalletTransactionResponse>> listTransactions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ApiResponse.success(walletService.listTransactions(
                claims.userId(), PageRequest.of(Math.max(page, 0), cappedSize)));
    }

    @Operation(
            summary = "Withdraw wallet balance (mock instant payout)",
            description = "Seller-only. No real bank transfer — completes instantly in the same request, "
                    + "same mock philosophy as COD. Amount cannot exceed the current balance.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @PostMapping("/withdraw")
    public ApiResponse<WalletResponse> withdraw(
            @Valid @RequestBody WithdrawRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(
                walletService.requestWithdraw(claims.userId(), claims.role(), request.amount()));
    }

    private void requireAuthenticated(AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }
}
