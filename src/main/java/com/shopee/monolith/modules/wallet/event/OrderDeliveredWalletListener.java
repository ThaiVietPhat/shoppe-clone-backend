package com.shopee.monolith.modules.wallet.event;

import com.shopee.monolith.modules.order.event.OrderFulfillmentChangedEvent;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.service.ShopService;
import com.shopee.monolith.modules.wallet.model.WalletReferenceType;
import com.shopee.monolith.modules.wallet.model.WalletTransactionType;
import com.shopee.monolith.modules.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Credits the shop owner's wallet with the order's item subtotal once fulfillment reaches
 * DELIVERED. Runs AFTER_COMMIT so a wallet-crediting failure never rolls back the delivery
 * transition; {@link WalletService#credit} is idempotent via the (referenceType, referenceId,
 * type) unique constraint, so a retried/duplicate event never double-credits.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderDeliveredWalletListener {

    private static final String DELIVERED = "DELIVERED";

    private final WalletService walletService;
    private final ShopService shopService;

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleFulfillmentChanged(OrderFulfillmentChangedEvent event) {
        if (!DELIVERED.equals(event.fulfillmentStatus())) {
            return;
        }
        ShopLookupData shop = shopService.findShopLookupDataById(event.shopId()).orElse(null);
        if (shop == null) {
            log.warn("Cannot credit seller earnings for order {} — shop {} not found", event.orderId(), event.shopId());
            return;
        }
        walletService.credit(
                shop.ownerId(),
                event.itemsSubtotal(),
                WalletTransactionType.SELLER_EARNING,
                WalletReferenceType.ORDER,
                event.orderId());
    }
}
