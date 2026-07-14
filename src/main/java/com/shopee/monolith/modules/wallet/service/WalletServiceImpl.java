package com.shopee.monolith.modules.wallet.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.wallet.dto.response.WalletResponse;
import com.shopee.monolith.modules.wallet.dto.response.WalletTransactionResponse;
import com.shopee.monolith.modules.wallet.entity.PayoutRequest;
import com.shopee.monolith.modules.wallet.entity.Wallet;
import com.shopee.monolith.modules.wallet.entity.WalletTransaction;
import com.shopee.monolith.modules.wallet.mapper.WalletMapper;
import com.shopee.monolith.modules.wallet.model.WalletReferenceType;
import com.shopee.monolith.modules.wallet.model.WalletTransactionType;
import com.shopee.monolith.modules.wallet.repository.PayoutRequestRepository;
import com.shopee.monolith.modules.wallet.repository.WalletRepository;
import com.shopee.monolith.modules.wallet.repository.WalletTransactionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class WalletServiceImpl implements WalletService {

    private final WalletRepository walletRepository;
    private final WalletTransactionRepository walletTransactionRepository;
    private final PayoutRequestRepository payoutRequestRepository;
    private final WalletMapper walletMapper;

    @Override
    @Transactional
    public WalletResponse getWallet(UUID userId) {
        return walletMapper.toResponse(getOrCreateWallet(userId));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<WalletTransactionResponse> listTransactions(UUID userId, Pageable pageable) {
        Wallet wallet = walletRepository.findByUserId(userId).orElse(null);
        if (wallet == null) {
            return PagedResponse.<WalletTransactionResponse>builder()
                    .items(java.util.List.of())
                    .page(pageable.getPageNumber())
                    .size(pageable.getPageSize())
                    .totalElements(0)
                    .totalPages(0)
                    .last(true)
                    .build();
        }
        var page = walletTransactionRepository.findByWalletIdOrderByCreatedAtDesc(wallet.getId(), pageable)
                .map(walletMapper::toResponse);
        return PagedResponse.from(page);
    }

    @Override
    @Transactional
    public void credit(UUID userId, BigDecimal amount, WalletTransactionType type,
                        WalletReferenceType referenceType, UUID referenceId) {
        applyLedgerMutation(userId, amount, type, referenceType, referenceId);
    }

    @Override
    @Transactional
    public void debit(UUID userId, BigDecimal amount, WalletTransactionType type,
                       WalletReferenceType referenceType, UUID referenceId) {
        applyLedgerMutation(userId, amount.negate(), type, referenceType, referenceId);
    }

    @Override
    @Transactional
    public WalletResponse requestWithdraw(UUID sellerId, Role callerRole, BigDecimal amount) {
        if (callerRole != Role.SELLER) {
            throw new AppException(ErrorCode.SHOP_OWNER_REQUIRED);
        }
        Wallet wallet = walletRepository.findByUserIdForUpdate(sellerId).orElseGet(() -> getOrCreateWallet(sellerId));
        if (wallet.getBalance().compareTo(amount) < 0) {
            throw new AppException(ErrorCode.INSUFFICIENT_WALLET_BALANCE);
        }

        Instant now = Instant.now();
        PayoutRequest payoutRequest = PayoutRequest.builder()
                .walletId(wallet.getId())
                .sellerId(sellerId)
                .amount(amount)
                .build();
        payoutRequest.complete(now);
        payoutRequest = payoutRequestRepository.save(payoutRequest);

        walletTransactionRepository.save(WalletTransaction.builder()
                .walletId(wallet.getId())
                .type(WalletTransactionType.WITHDRAWAL)
                .amount(amount.negate())
                .referenceType(WalletReferenceType.PAYOUT)
                .referenceId(payoutRequest.getId())
                .build());
        wallet.applyDelta(amount.negate());
        wallet = walletRepository.save(wallet);

        return walletMapper.toResponse(wallet);
    }

    /**
     * Claim the ledger row first (unique constraint on reference_type/reference_id/type catches
     * duplicates), then mutate the balance — same claim-then-mutate order as idempotency_keys.
     * Both happen in one transaction so a crash between them rolls back atomically.
     */
    private void applyLedgerMutation(UUID userId, BigDecimal signedAmount, WalletTransactionType type,
                                      WalletReferenceType referenceType, UUID referenceId) {
        Wallet wallet = walletRepository.findByUserIdForUpdate(userId).orElseGet(() -> getOrCreateWallet(userId));

        WalletTransaction transaction = WalletTransaction.builder()
                .walletId(wallet.getId())
                .type(type)
                .amount(signedAmount)
                .referenceType(referenceType)
                .referenceId(referenceId)
                .build();
        try {
            walletTransactionRepository.saveAndFlush(transaction);
        } catch (DataIntegrityViolationException e) {
            log.info("Wallet ledger entry already applied for {}/{}/{} — skipping duplicate mutation",
                    referenceType, referenceId, type);
            return;
        }

        wallet.applyDelta(signedAmount);
        walletRepository.save(wallet);
    }

    private Wallet getOrCreateWallet(UUID userId) {
        return walletRepository.findByUserId(userId)
                .orElseGet(() -> {
                    try {
                        return walletRepository.saveAndFlush(Wallet.builder().userId(userId).build());
                    } catch (DataIntegrityViolationException e) {
                        return walletRepository.findByUserId(userId)
                                .orElseThrow(() -> new AppException(ErrorCode.INTERNAL_SERVER_ERROR));
                    }
                });
    }
}
