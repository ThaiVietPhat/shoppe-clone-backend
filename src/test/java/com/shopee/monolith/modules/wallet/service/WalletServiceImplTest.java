package com.shopee.monolith.modules.wallet.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.wallet.dto.response.WalletResponse;
import com.shopee.monolith.modules.wallet.entity.PayoutRequest;
import com.shopee.monolith.modules.wallet.entity.Wallet;
import com.shopee.monolith.modules.wallet.entity.WalletTransaction;
import com.shopee.monolith.modules.wallet.mapper.WalletMapper;
import com.shopee.monolith.modules.wallet.model.WalletReferenceType;
import com.shopee.monolith.modules.wallet.model.WalletTransactionType;
import com.shopee.monolith.modules.wallet.repository.PayoutRequestRepository;
import com.shopee.monolith.modules.wallet.repository.WalletRepository;
import com.shopee.monolith.modules.wallet.repository.WalletTransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WalletServiceImplTest {

    @Mock
    private WalletRepository walletRepository;
    @Mock
    private WalletTransactionRepository walletTransactionRepository;
    @Mock
    private PayoutRequestRepository payoutRequestRepository;
    @Mock
    private WalletMapper walletMapper;

    private WalletServiceImpl walletService;

    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        walletService = new WalletServiceImpl(
                walletRepository, walletTransactionRepository, payoutRequestRepository, walletMapper);
        lenient().when(walletMapper.toResponse(any(Wallet.class)))
                .thenAnswer(inv -> {
                    Wallet w = inv.getArgument(0);
                    return WalletResponse.builder().walletId(w.getId()).balance(w.getBalance()).build();
                });
    }

    private Wallet existingWallet(BigDecimal balance) {
        Wallet wallet = Wallet.builder().userId(userId).balance(balance).build();
        return wallet;
    }

    @Test
    void creditWhenFirstTimeShouldInsertLedgerAndIncreaseBalance() {
        Wallet wallet = existingWallet(BigDecimal.ZERO);
        UUID referenceId = UUID.randomUUID();
        when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.saveAndFlush(any(WalletTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.credit(userId, new BigDecimal("50000"), WalletTransactionType.SELLER_EARNING,
                WalletReferenceType.ORDER, referenceId);

        assertEquals(new BigDecimal("50000"), wallet.getBalance());
        verify(walletRepository).save(wallet);
    }

    @Test
    void creditWhenDuplicateReferenceShouldNoOpAndNotMutateBalance() {
        Wallet wallet = existingWallet(BigDecimal.ZERO);
        UUID referenceId = UUID.randomUUID();
        when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.saveAndFlush(any(WalletTransaction.class)))
                .thenThrow(new DataIntegrityViolationException("dup"));

        walletService.credit(userId, new BigDecimal("50000"), WalletTransactionType.SELLER_EARNING,
                WalletReferenceType.ORDER, referenceId);

        assertEquals(BigDecimal.ZERO, wallet.getBalance());
        verify(walletRepository, never()).save(any(Wallet.class));
    }

    @Test
    void debitShouldSubtractFromBalanceAndAllowGoingNegative() {
        Wallet wallet = existingWallet(new BigDecimal("100"));
        UUID referenceId = UUID.randomUUID();
        when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));
        when(walletTransactionRepository.saveAndFlush(any(WalletTransaction.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.debit(userId, new BigDecimal("300"), WalletTransactionType.RETURN_CLAWBACK,
                WalletReferenceType.RETURN, referenceId);

        assertEquals(new BigDecimal("-200"), wallet.getBalance());
    }

    @Test
    void requestWithdrawWhenNotSellerShouldThrowShopOwnerRequired() {
        AppException ex = assertThrows(AppException.class,
                () -> walletService.requestWithdraw(userId, Role.BUYER, BigDecimal.TEN));
        assertEquals(ErrorCode.SHOP_OWNER_REQUIRED, ex.getErrorCode());
    }

    @Test
    void requestWithdrawWhenAmountExceedsBalanceShouldThrowInsufficientBalance() {
        Wallet wallet = existingWallet(new BigDecimal("50"));
        when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));

        AppException ex = assertThrows(AppException.class,
                () -> walletService.requestWithdraw(userId, Role.SELLER, new BigDecimal("100")));
        assertEquals(ErrorCode.INSUFFICIENT_WALLET_BALANCE, ex.getErrorCode());
    }

    @Test
    void requestWithdrawWhenSufficientBalanceShouldCompleteInstantlyAndDebit() {
        Wallet wallet = existingWallet(new BigDecimal("100"));
        when(walletRepository.findByUserIdForUpdate(userId)).thenReturn(Optional.of(wallet));
        when(payoutRequestRepository.save(any(PayoutRequest.class))).thenAnswer(inv -> {
            PayoutRequest req = inv.getArgument(0);
            return PayoutRequest.builder()
                    .walletId(req.getWalletId())
                    .sellerId(req.getSellerId())
                    .amount(req.getAmount())
                    .build();
        });
        when(walletRepository.save(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        walletService.requestWithdraw(userId, Role.SELLER, new BigDecimal("40"));

        assertEquals(new BigDecimal("60"), wallet.getBalance());
        ArgumentCaptor<WalletTransaction> captor = ArgumentCaptor.forClass(WalletTransaction.class);
        verify(walletTransactionRepository).save(captor.capture());
        assertEquals(WalletTransactionType.WITHDRAWAL, captor.getValue().getType());
        assertEquals(new BigDecimal("-40"), captor.getValue().getAmount());
    }

    @Test
    void getWalletWhenNoneExistsShouldCreateOne() {
        when(walletRepository.findByUserId(userId)).thenReturn(Optional.empty());
        when(walletRepository.saveAndFlush(any(Wallet.class))).thenAnswer(inv -> inv.getArgument(0));

        WalletResponse response = walletService.getWallet(userId);

        assertEquals(BigDecimal.ZERO, response.balance());
    }
}
