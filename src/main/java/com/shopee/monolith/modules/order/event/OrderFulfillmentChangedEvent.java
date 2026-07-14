package com.shopee.monolith.modules.order.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Published after a seller fulfillment transition (SHIPPED / DELIVERED) commits.
 * NotificationModule uses it to write buyer inbox entries AFTER_COMMIT.
 * WalletModule uses shopId/itemsSubtotal on DELIVERED to credit the seller's earnings AFTER_COMMIT.
 */
public record OrderFulfillmentChangedEvent(
        UUID orderId,
        UUID buyerId,
        UUID shopId,
        BigDecimal itemsSubtotal,
        String fulfillmentStatus
) {
}
