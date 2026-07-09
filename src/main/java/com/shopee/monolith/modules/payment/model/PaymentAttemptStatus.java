package com.shopee.monolith.modules.payment.model;

import java.util.Set;

/**
 * Monotonic payment attempt state machine:
 * CREATED → INITIATING → PENDING → SUCCEEDED | FAILED | EXPIRED | REQUIRES_RECONCILIATION.
 * COD attempts are created directly as PENDING_COD and immediately confirm inventory/fulfillment
 * in the same transaction — but unlike an online gateway attempt, no cash changes hands yet.
 * The order stays UNPAID (see Order#confirmCod) until the buyer pays the carrier on delivery
 * (Order#deliver); the attempt row itself is not revisited at that point.
 * Terminal states must never be rolled back by duplicate or out-of-order webhooks.
 */
public enum PaymentAttemptStatus {
    CREATED,
    INITIATING,
    PENDING,
    PENDING_COD,
    SUCCEEDED,
    FAILED,
    EXPIRED,
    REQUIRES_RECONCILIATION;

    private static final Set<PaymentAttemptStatus> NON_TERMINAL = Set.of(CREATED, INITIATING, PENDING);

    public boolean isTerminal() {
        return !NON_TERMINAL.contains(this);
    }
}
