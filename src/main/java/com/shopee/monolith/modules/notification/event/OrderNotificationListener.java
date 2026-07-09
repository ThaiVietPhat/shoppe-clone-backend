package com.shopee.monolith.modules.notification.event;

import com.shopee.monolith.modules.notification.model.NotificationType;
import com.shopee.monolith.modules.notification.service.NotificationInboxService;
import com.shopee.monolith.modules.order.event.OrderConfirmedEvent;
import com.shopee.monolith.modules.order.event.OrderFulfillmentChangedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.UUID;

/**
 * Writes buyer inbox notifications for order lifecycle events.
 * Runs AFTER_COMMIT so a notification failure never rolls back the order transition.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderNotificationListener {

    private static final String REF_TYPE_ORDER = "ORDER";
    private static final String COD_METHOD = "COD";

    private final NotificationInboxService inboxService;

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleOrderConfirmed(OrderConfirmedEvent event) {
        // COD is not collected yet at confirm time (see Order#confirmCod) — don't tell the buyer
        // payment "succeeded" when the seller hasn't been paid until cash-on-delivery.
        String body = COD_METHOD.equals(event.paymentMethod())
                ? "Đơn hàng đã được xác nhận, thanh toán bằng tiền mặt khi nhận hàng. "
                        + "Người bán đang chuẩn bị đơn hàng của bạn."
                : "Thanh toán qua " + event.paymentMethod() + " thành công. Người bán đang chuẩn bị đơn hàng của bạn.";
        for (UUID orderId : event.orderIds()) {
            inboxService.createNotification(
                    event.buyerId(),
                    NotificationType.ORDER_CONFIRMED,
                    "Đơn hàng của bạn đã được xác nhận",
                    body,
                    REF_TYPE_ORDER,
                    orderId);
        }
    }

    @Async("eventTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handleFulfillmentChanged(OrderFulfillmentChangedEvent event) {
        switch (event.fulfillmentStatus()) {
            case "SHIPPED" -> inboxService.createNotification(
                    event.buyerId(),
                    NotificationType.ORDER_SHIPPED,
                    "Đơn hàng của bạn đang được giao",
                    "Người bán đã giao đơn hàng cho đơn vị vận chuyển.",
                    REF_TYPE_ORDER,
                    event.orderId());
            case "DELIVERED" -> {
                inboxService.createNotification(
                        event.buyerId(),
                        NotificationType.ORDER_DELIVERED,
                        "Đơn hàng của bạn đã được giao",
                        "Chúc bạn hài lòng với sản phẩm đã mua!",
                        REF_TYPE_ORDER,
                        event.orderId());
                inboxService.createNotification(
                        event.buyerId(),
                        NotificationType.REVIEW_REMINDER,
                        "Đơn hàng của bạn thế nào?",
                        "Hãy để lại đánh giá cho sản phẩm bạn vừa nhận được.",
                        REF_TYPE_ORDER,
                        event.orderId());
            }
            default -> log.debug("No notification for fulfillment status {}", event.fulfillmentStatus());
        }
    }
}
