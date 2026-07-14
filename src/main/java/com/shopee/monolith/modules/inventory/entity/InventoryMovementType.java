package com.shopee.monolith.modules.inventory.entity;

/**
 * Type of an inventory stock movement recorded in the audit ledger.
 */
public enum InventoryMovementType {
    /** Initial stock when inventory record is created. */
    INITIAL,
    /** Direct stock adjustment by seller/admin; quantity is the signed delta. */
    STOCK_UPDATE,
    /** Stock moved from available to reserved during checkout. */
    RESERVE,
    /** Reserved stock consumed after payment success. */
    CONFIRM,
    /** Reserved stock returned to available after cancel/timeout/failure. */
    RELEASE,
    /** Already-sold stock returned to available after an approved return/dispute. */
    RETURN_RESTOCK
}
