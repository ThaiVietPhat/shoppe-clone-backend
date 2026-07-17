package com.shopee.monolith.modules.payment.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.order.dto.internal.CheckoutSessionPaymentInfo;
import com.shopee.monolith.modules.order.model.CheckoutSessionStatus;
import com.shopee.monolith.modules.order.service.CheckoutSettlementService;
import com.shopee.monolith.modules.payment.config.PaymentTimeoutProperties;
import com.shopee.monolith.modules.payment.dto.request.InitiatePaymentRequest;
import com.shopee.monolith.modules.payment.dto.response.PaymentStatusResponse;
import com.shopee.monolith.modules.payment.entity.PaymentAttempt;
import com.shopee.monolith.modules.payment.model.PaymentAttemptStatus;
import com.shopee.monolith.modules.payment.model.PaymentMethod;
import com.shopee.monolith.modules.payment.repository.PaymentAttemptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentServiceImplTest {

    @Mock
    private PaymentAttemptRepository paymentAttemptRepository;

    @Mock
    private CheckoutSettlementService checkoutSettlementService;

    @Mock
    private VNPayPaymentUrlBuilder paymentUrlBuilder;

    @Mock
    private PaymentTimeoutProperties timeoutProperties;

    @InjectMocks
    private PaymentServiceImpl paymentService;

    private final UUID buyerId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();
    private static final String CLIENT_IP = "127.0.0.1";

    private CheckoutSessionPaymentInfo pendingSession;

    @BeforeEach
    void setUp() {
        pendingSession = CheckoutSessionPaymentInfo.builder()
                .checkoutSessionId(sessionId)
                .buyerId(buyerId)
                .status(CheckoutSessionStatus.PENDING_PAYMENT)
                .totalAmount(new BigDecimal("150000.00"))
                .expiresAt(Instant.now().plusSeconds(900))
                .orderIds(List.of(orderId))
                .build();
    }

    private PaymentAttempt attempt(PaymentMethod method, PaymentAttemptStatus status) {
        return PaymentAttempt.builder()
                .id(UUID.randomUUID())
                .checkoutSessionId(sessionId)
                .method(method)
                .status(status)
                .amount(new BigDecimal("150000.00"))
                .currency("VND")
                .expiresAt(Instant.now().plusSeconds(900))
                .build();
    }

    @Test
    void initiatePaymentWhenCodShouldConfirmSessionInSameTransaction() {
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(pendingSession));
        when(paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(List.of());
        when(timeoutProperties.getAttemptTimeoutMinutes()).thenReturn(15);
        when(paymentAttemptRepository.saveAndFlush(any(PaymentAttempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(checkoutSettlementService.confirmCheckoutSession(sessionId, "COD")).thenReturn(true);

        PaymentStatusResponse response = paymentService.initiatePayment(
                buyerId, new InitiatePaymentRequest(sessionId, PaymentMethod.COD), CLIENT_IP);

        assertEquals(PaymentAttemptStatus.PENDING_COD.name(), response.status());
        assertEquals(List.of(orderId), response.orderIds());
        assertNull(response.nextAction());
        verify(checkoutSettlementService).confirmCheckoutSession(sessionId, "COD");
    }

    @Test
    void initiatePaymentWhenCodConfirmLosesRaceShouldThrowNotPayable() {
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(pendingSession));
        when(paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(List.of());
        when(timeoutProperties.getAttemptTimeoutMinutes()).thenReturn(15);
        when(paymentAttemptRepository.saveAndFlush(any(PaymentAttempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(checkoutSettlementService.confirmCheckoutSession(sessionId, "COD")).thenReturn(false);

        AppException exception = assertThrows(AppException.class, () ->
                paymentService.initiatePayment(buyerId, new InitiatePaymentRequest(sessionId, PaymentMethod.COD),
                        CLIENT_IP));
        assertEquals(ErrorCode.CHECKOUT_SESSION_NOT_PAYABLE, exception.getErrorCode());
    }

    @Test
    void initiatePaymentWhenVnpayShouldReturnPaymentUrl() {
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(pendingSession));
        when(paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(List.of());
        when(timeoutProperties.getAttemptTimeoutMinutes()).thenReturn(15);
        when(paymentAttemptRepository.saveAndFlush(any(PaymentAttempt.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(paymentUrlBuilder.buildPaymentUrl(any(PaymentAttempt.class), eq(CLIENT_IP)))
                .thenReturn("https://sandbox.vnpay/url");

        PaymentStatusResponse response = paymentService.initiatePayment(
                buyerId, new InitiatePaymentRequest(sessionId, PaymentMethod.VNPAY), CLIENT_IP);

        assertEquals(PaymentAttemptStatus.PENDING.name(), response.status());
        assertEquals("https://sandbox.vnpay/url", response.nextAction());
        verify(checkoutSettlementService, never()).confirmCheckoutSession(any(), any());
    }

    @Test
    void initiatePaymentWhenNonTerminalAttemptSameMethodShouldReturnExistingAttempt() {
        PaymentAttempt existing = attempt(PaymentMethod.VNPAY, PaymentAttemptStatus.PENDING);
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(pendingSession));
        when(paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of(existing));
        when(paymentUrlBuilder.buildPaymentUrl(existing, CLIENT_IP)).thenReturn("https://sandbox.vnpay/url");

        PaymentStatusResponse response = paymentService.initiatePayment(
                buyerId, new InitiatePaymentRequest(sessionId, PaymentMethod.VNPAY), CLIENT_IP);

        assertEquals(existing.getId(), response.paymentAttemptId());
        verify(paymentAttemptRepository, never()).saveAndFlush(any());
    }

    @Test
    void initiatePaymentWhenNonTerminalAttemptDifferentMethodShouldThrowConflict() {
        PaymentAttempt existing = attempt(PaymentMethod.VNPAY, PaymentAttemptStatus.PENDING);
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(pendingSession));
        when(paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of(existing));

        AppException exception = assertThrows(AppException.class, () ->
                paymentService.initiatePayment(buyerId, new InitiatePaymentRequest(sessionId, PaymentMethod.COD),
                        CLIENT_IP));
        assertEquals(ErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS, exception.getErrorCode());
    }

    @Test
    void initiatePaymentWhenSessionNotOwnedShouldThrowNotFound() {
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(pendingSession));

        AppException exception = assertThrows(AppException.class, () ->
                paymentService.initiatePayment(UUID.randomUUID(),
                        new InitiatePaymentRequest(sessionId, PaymentMethod.COD), CLIENT_IP));
        assertEquals(ErrorCode.CHECKOUT_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void initiatePaymentWhenSessionNotPendingPaymentShouldThrowNotPayable() {
        CheckoutSessionPaymentInfo completed = CheckoutSessionPaymentInfo.builder()
                .checkoutSessionId(sessionId)
                .buyerId(buyerId)
                .status(CheckoutSessionStatus.COMPLETED)
                .totalAmount(new BigDecimal("150000.00"))
                .expiresAt(Instant.now())
                .orderIds(List.of(orderId))
                .build();
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(completed));

        AppException exception = assertThrows(AppException.class, () ->
                paymentService.initiatePayment(buyerId, new InitiatePaymentRequest(sessionId, PaymentMethod.COD),
                        CLIENT_IP));
        assertEquals(ErrorCode.CHECKOUT_SESSION_NOT_PAYABLE, exception.getErrorCode());
    }

    @Test
    void getPaymentStatusWhenNoAttemptShouldReturnNoneStatus() {
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(pendingSession));
        when(paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId)).thenReturn(List.of());

        PaymentStatusResponse response = paymentService.getPaymentStatus(sessionId, buyerId, CLIENT_IP);

        assertEquals("NONE", response.status());
        assertNull(response.paymentAttemptId());
        assertEquals(List.of(orderId), response.orderIds());
    }

    @Test
    void getPaymentStatusWhenAttemptRequiresReconciliationShouldExposeReason() {
        PaymentAttempt attempt = attempt(PaymentMethod.VNPAY, PaymentAttemptStatus.PENDING);
        attempt.requireReconciliation("AMOUNT_MISMATCH");
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(pendingSession));
        when(paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of(attempt));

        PaymentStatusResponse response = paymentService.getPaymentStatus(sessionId, buyerId, CLIENT_IP);

        assertNotNull(response.paymentAttemptId());
        assertEquals(PaymentAttemptStatus.REQUIRES_RECONCILIATION.name(), response.status());
        assertEquals("AMOUNT_MISMATCH", response.reconciliationReason());
    }

    @Test
    void getPaymentStatusWhenAttemptPendingButSessionTerminalShouldNotReturnNextAction() {
        // Attempt is still PENDING (listener may not have fired yet) but session is already EXPIRED
        PaymentAttempt attempt = attempt(PaymentMethod.VNPAY, PaymentAttemptStatus.PENDING);
        CheckoutSessionPaymentInfo expiredSession = CheckoutSessionPaymentInfo.builder()
                .checkoutSessionId(sessionId)
                .buyerId(buyerId)
                .status(CheckoutSessionStatus.EXPIRED)
                .totalAmount(new BigDecimal("150000.00"))
                .expiresAt(Instant.now().minusSeconds(60))
                .orderIds(List.of(orderId))
                .build();
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(expiredSession));
        when(paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of(attempt));

        PaymentStatusResponse response = paymentService.getPaymentStatus(sessionId, buyerId, CLIENT_IP);

        assertEquals(PaymentAttemptStatus.PENDING.name(), response.status());
        assertNull(response.nextAction(), "nextAction must be null when session is no longer payable");
    }

    @Test
    void getPaymentStatusWhenAttemptPendingButSessionCancelledShouldNotReturnNextAction() {
        PaymentAttempt attempt = attempt(PaymentMethod.VNPAY, PaymentAttemptStatus.PENDING);
        CheckoutSessionPaymentInfo cancelledSession = CheckoutSessionPaymentInfo.builder()
                .checkoutSessionId(sessionId)
                .buyerId(buyerId)
                .status(CheckoutSessionStatus.CANCELLED)
                .totalAmount(new BigDecimal("150000.00"))
                .expiresAt(Instant.now().plusSeconds(900))
                .orderIds(List.of(orderId))
                .build();
        when(checkoutSettlementService.findSessionPaymentInfo(sessionId)).thenReturn(Optional.of(cancelledSession));
        when(paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId))
                .thenReturn(List.of(attempt));

        PaymentStatusResponse response = paymentService.getPaymentStatus(sessionId, buyerId, CLIENT_IP);

        assertNull(response.nextAction(), "nextAction must be null when session is cancelled");
    }
}
