package com.shopee.monolith.modules.order.model;

/**
 * 3 states only — approve performs the wallet refund/clawback and inventory restock atomically
 * in the same transaction as the APPROVED transition, so there's no separate terminal
 * "REFUNDED" state. Single-level seller resolution: no buyer counter-appeal/admin escalation.
 */
public enum ReturnStatus {
    REQUESTED,
    APPROVED,
    REJECTED
}
