package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.modules.inventory.dto.command.ReleaseInventoryCommand;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.entity.CheckoutSession;
import com.shopee.monolith.modules.order.entity.InventoryReservation;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.event.CheckoutSessionCancelledEvent;
import com.shopee.monolith.modules.order.model.CheckoutSessionStatus;
import com.shopee.monolith.modules.order.model.InventoryReservationStatus;
import com.shopee.monolith.modules.order.repository.CheckoutSessionRepository;
import com.shopee.monolith.modules.order.repository.InventoryReservationRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import com.shopee.monolith.modules.voucher.service.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutTimeoutProcessor {

    private final CheckoutSessionRepository checkoutSessionRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher eventPublisher;
    private final VoucherService voucherService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processTimeout(UUID sessionId, Instant now) {
        log.info("Processing checkout timeout for session: {}", sessionId);

        // 1. Lock checkout session using SKIP LOCKED
        CheckoutSession session = checkoutSessionRepository.findByIdAndStatusForUpdateSkipLocked(
                sessionId,
                CheckoutSessionStatus.PENDING_PAYMENT.name()
        ).orElse(null);
        if (session == null) {
            log.info("Checkout session {} not found, already locked, or not in PENDING_PAYMENT status", sessionId);
            return;
        }

        if (session.getExpiresAt().isAfter(now)) {
            log.info("Checkout session {} has not expired yet", sessionId);
            return; // Not yet expired
        }

        // 2. Lock reservations & release stock
        List<InventoryReservation> reservations = inventoryReservationRepository.findAllByCheckoutSessionIdAndStatusForUpdate(sessionId, InventoryReservationStatus.RESERVED.name());
        if (!reservations.isEmpty()) {
            List<ReleaseInventoryCommand> releaseCommands = reservations.stream()
                    .collect(Collectors.groupingBy(InventoryReservation::getVariantId, Collectors.summingInt(InventoryReservation::getQuantity)))
                    .entrySet().stream()
                    .map(entry -> new ReleaseInventoryCommand(entry.getKey(), entry.getValue()))
                    .toList();

            // Perform inventory release cross-module call
            inventoryService.release(releaseCommands);

            // Update reservations status
            for (InventoryReservation res : reservations) {
                res.release();
            }
            inventoryReservationRepository.saveAll(reservations);
            log.info("Released {} inventory reservations for session: {}", reservations.size(), sessionId);
        }

        // 3. Lock & Cancel orders
        List<Order> orders = orderRepository.findAllByCheckoutSessionIdForUpdate(sessionId);
        for (Order order : orders) {
            order.cancel();
        }
        orderRepository.saveAll(orders);
        log.info("Cancelled {} orders for session: {}", orders.size(), sessionId);

        // 4. Update session
        session.expire();
        checkoutSessionRepository.save(session);
        voucherService.releaseVoucherUsage(sessionId);
        log.info("Checkout session {} is now EXPIRED", sessionId);

        // Expire any pending payment attempts after this REQUIRES_NEW transaction commits.
        // Same mechanism as buyer cancel — deferred so we don't hold session lock
        // while touching payment_attempt rows (avoids deadlock with webhook/timeout).
        eventPublisher.publishEvent(new CheckoutSessionCancelledEvent(sessionId));
    }
}
