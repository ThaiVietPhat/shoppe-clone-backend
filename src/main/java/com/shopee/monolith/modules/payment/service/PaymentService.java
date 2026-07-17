package com.shopee.monolith.modules.payment.service;

import com.shopee.monolith.modules.payment.dto.request.InitiatePaymentRequest;
import com.shopee.monolith.modules.payment.dto.response.PaymentStatusResponse;

import java.util.UUID;

public interface PaymentService {

    /**
     * Creates a payment attempt for the buyer's PENDING_PAYMENT checkout session.
     * Only one non-terminal attempt may exist per session; retrying with the same
     * method returns the existing attempt. COD attempts settle inventory and orders
     * in the same transaction.
     */
    PaymentStatusResponse initiatePayment(UUID buyerId, InitiatePaymentRequest request, String clientIp);

    PaymentStatusResponse getPaymentStatus(UUID checkoutSessionId, UUID buyerId, String clientIp);
}
