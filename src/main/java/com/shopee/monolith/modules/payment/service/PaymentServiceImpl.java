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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentServiceImpl implements PaymentService {

    private static final String CURRENCY_VND = "VND";

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final CheckoutSettlementService checkoutSettlementService;
    private final VNPayPaymentUrlBuilder paymentUrlBuilder;
    private final PaymentTimeoutProperties timeoutProperties;

    @Override
    @Transactional
    public PaymentStatusResponse initiatePayment(UUID buyerId, InitiatePaymentRequest request, String clientIp) {
        CheckoutSessionPaymentInfo session = loadOwnedSession(request.checkoutSessionId(), buyerId);
        if (session.status() != CheckoutSessionStatus.PENDING_PAYMENT) {
            throw new AppException(ErrorCode.CHECKOUT_SESSION_NOT_PAYABLE);
        }

        Optional<PaymentAttempt> nonTerminal = findNonTerminalAttempt(session.checkoutSessionId());
        if (nonTerminal.isPresent()) {
            PaymentAttempt existing = nonTerminal.get();
            if (existing.getMethod() != request.method()) {
                throw new AppException(ErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS);
            }
            return toResponse(session, existing, clientIp);
        }

        if (request.method() == PaymentMethod.COD) {
            return initiateCod(session);
        }
        return initiateVnpay(session, clientIp);
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentStatusResponse getPaymentStatus(UUID checkoutSessionId, UUID buyerId, String clientIp) {
        CheckoutSessionPaymentInfo session = loadOwnedSession(checkoutSessionId, buyerId);
        PaymentAttempt latest = paymentAttemptRepository
                .findAllByCheckoutSessionIdOrderByCreatedAtDesc(checkoutSessionId)
                .stream().findFirst().orElse(null);
        if (latest == null) {
            return PaymentStatusResponse.builder()
                    .checkoutSessionId(checkoutSessionId)
                    .status("NONE")
                    .orderIds(session.orderIds())
                    .build();
        }
        return toResponse(session, latest, clientIp);
    }

    private PaymentStatusResponse initiateCod(CheckoutSessionPaymentInfo session) {
        PaymentAttempt attempt = paymentAttemptRepository.saveAndFlush(
                newAttempt(session, PaymentMethod.COD, PaymentAttemptStatus.PENDING_COD));

        boolean confirmed = checkoutSettlementService
                .confirmCheckoutSession(session.checkoutSessionId(), PaymentMethod.COD.name());
        if (!confirmed) {
            throw new AppException(ErrorCode.CHECKOUT_SESSION_NOT_PAYABLE);
        }
        log.info("COD payment attempt {} confirmed checkout session {}", attempt.getId(), session.checkoutSessionId());
        return toResponse(session, attempt, null);
    }

    private PaymentStatusResponse initiateVnpay(CheckoutSessionPaymentInfo session, String clientIp) {
        try {
            PaymentAttempt attempt = paymentAttemptRepository.saveAndFlush(
                    newAttempt(session, PaymentMethod.VNPAY, PaymentAttemptStatus.PENDING));
            return toResponse(session, attempt, clientIp);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Partial unique index: another non-terminal attempt won the race
            throw new AppException(ErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS);
        }
    }

    private PaymentAttempt newAttempt(CheckoutSessionPaymentInfo session, PaymentMethod method,
                                      PaymentAttemptStatus status) {
        return PaymentAttempt.builder()
                .checkoutSessionId(session.checkoutSessionId())
                .method(method)
                .status(status)
                .amount(session.totalAmount())
                .currency(CURRENCY_VND)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(timeoutProperties.getAttemptTimeoutMinutes())))
                .build();
    }

    private Optional<PaymentAttempt> findNonTerminalAttempt(UUID checkoutSessionId) {
        return paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(checkoutSessionId)
                .stream()
                .filter(attempt -> !attempt.getStatus().isTerminal())
                .findFirst();
    }

    private CheckoutSessionPaymentInfo loadOwnedSession(UUID checkoutSessionId, UUID buyerId) {
        CheckoutSessionPaymentInfo session = checkoutSettlementService.findSessionPaymentInfo(checkoutSessionId)
                .orElseThrow(() -> new AppException(ErrorCode.CHECKOUT_NOT_FOUND));
        if (!session.buyerId().equals(buyerId)) {
            // Hide other buyers' sessions instead of leaking their existence
            throw new AppException(ErrorCode.CHECKOUT_NOT_FOUND);
        }
        return session;
    }

    private PaymentStatusResponse toResponse(CheckoutSessionPaymentInfo session, PaymentAttempt attempt,
                                              String clientIp) {
        return PaymentStatusResponse.builder()
                .checkoutSessionId(session.checkoutSessionId())
                .paymentAttemptId(attempt.getId())
                .status(attempt.getStatus().name())
                .orderIds(session.orderIds())
                .nextAction(resolveNextAction(session, attempt, clientIp))
                .expiresAt(attempt.getExpiresAt())
                .reconciliationReason(attempt.getReconciliationReason())
                .build();
    }

    private String resolveNextAction(CheckoutSessionPaymentInfo session, PaymentAttempt attempt, String clientIp) {
        if (attempt.getMethod() == PaymentMethod.VNPAY
                && attempt.getStatus() == PaymentAttemptStatus.PENDING
                && session.status() == CheckoutSessionStatus.PENDING_PAYMENT) {
            return paymentUrlBuilder.buildPaymentUrl(attempt, clientIp);
        }
        return null;
    }
}
