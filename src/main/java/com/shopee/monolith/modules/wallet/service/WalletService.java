package com.shopee.monolith.modules.wallet.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.wallet.dto.response.WalletResponse;
import com.shopee.monolith.modules.wallet.dto.response.WalletTransactionResponse;
import com.shopee.monolith.modules.wallet.model.WalletReferenceType;
import com.shopee.monolith.modules.wallet.model.WalletTransactionType;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Public cross-module port. Other modules (order/return) must call through this interface,
 * never reference wallet entities directly.
 */
public interface WalletService {

    WalletResponse getWallet(UUID userId);

    PagedResponse<WalletTransactionResponse> listTransactions(UUID userId, Pageable pageable);

    /**
     * Idempotent credit keyed by (referenceType, referenceId, type) — a retried call for the same
     * business event is a no-op, it never double-credits.
     */
    void credit(UUID userId, BigDecimal amount, WalletTransactionType type,
                WalletReferenceType referenceType, UUID referenceId);

    /**
     * Idempotent debit, same key as {@link #credit}. Unlike withdrawal this is allowed to push the
     * balance negative — a return clawback must always succeed even if the seller already
     * withdrew the earnings for that order.
     */
    void debit(UUID userId, BigDecimal amount, WalletTransactionType type,
               WalletReferenceType referenceType, UUID referenceId);

    /**
     * Mock instant payout: only sellers may withdraw, and only up to their current balance.
     */
    WalletResponse requestWithdraw(UUID sellerId, Role callerRole, BigDecimal amount);
}
