package com.shopee.monolith.modules.notification.event;

import com.shopee.monolith.modules.notification.model.NotificationType;
import com.shopee.monolith.modules.notification.service.NotificationInboxService;
import com.shopee.monolith.modules.order.event.OrderConfirmedEvent;
import com.shopee.monolith.modules.order.event.OrderFulfillmentChangedEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OrderNotificationListenerTest {

    @Mock
    private NotificationInboxService inboxService;

    @InjectMocks
    private OrderNotificationListener listener;

    @Test
    void handleOrderConfirmedShouldCreateOneNotificationPerOrder() {
        UUID buyerId = UUID.randomUUID();
        UUID order1 = UUID.randomUUID();
        UUID order2 = UUID.randomUUID();

        listener.handleOrderConfirmed(new OrderConfirmedEvent(
                UUID.randomUUID(), buyerId, List.of(order1, order2), "COD"));

        verify(inboxService).createNotification(eq(buyerId), eq(NotificationType.ORDER_CONFIRMED),
                anyString(), anyString(), eq("ORDER"), eq(order1));
        verify(inboxService).createNotification(eq(buyerId), eq(NotificationType.ORDER_CONFIRMED),
                anyString(), anyString(), eq("ORDER"), eq(order2));
    }

    @Test
    void handleFulfillmentChangedWhenShippedShouldCreateShippedNotification() {
        UUID buyerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        listener.handleFulfillmentChanged(new OrderFulfillmentChangedEvent(
                orderId, buyerId, UUID.randomUUID(), BigDecimal.TEN, "SHIPPED"));

        verify(inboxService).createNotification(eq(buyerId), eq(NotificationType.ORDER_SHIPPED),
                anyString(), anyString(), eq("ORDER"), eq(orderId));
        verify(inboxService, never()).createNotification(any(), eq(NotificationType.REVIEW_REMINDER),
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void handleFulfillmentChangedWhenDeliveredShouldCreateDeliveredAndReviewReminder() {
        UUID buyerId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();

        listener.handleFulfillmentChanged(new OrderFulfillmentChangedEvent(
                orderId, buyerId, UUID.randomUUID(), BigDecimal.TEN, "DELIVERED"));

        verify(inboxService).createNotification(eq(buyerId), eq(NotificationType.ORDER_DELIVERED),
                anyString(), anyString(), eq("ORDER"), eq(orderId));
        verify(inboxService).createNotification(eq(buyerId), eq(NotificationType.REVIEW_REMINDER),
                anyString(), anyString(), eq("ORDER"), eq(orderId));
    }

    @Test
    void handleFulfillmentChangedWhenUnknownStatusShouldCreateNothing() {
        listener.handleFulfillmentChanged(new OrderFulfillmentChangedEvent(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), BigDecimal.TEN, "READY_TO_SHIP"));

        verify(inboxService, never()).createNotification(any(), any(), anyString(), anyString(), anyString(), any());
    }
}
