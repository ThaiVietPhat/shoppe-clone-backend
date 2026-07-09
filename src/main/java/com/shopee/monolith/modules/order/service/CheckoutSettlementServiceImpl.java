package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.modules.inventory.dto.command.ConfirmInventoryCommand;
import com.shopee.monolith.modules.inventory.dto.command.ReleaseInventoryCommand;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.internal.CheckoutSessionPaymentInfo;
import com.shopee.monolith.modules.order.entity.CheckoutSession;
import com.shopee.monolith.modules.order.entity.InventoryReservation;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.event.OrderConfirmedEvent;
import com.shopee.monolith.modules.order.model.CheckoutSessionStatus;
import com.shopee.monolith.modules.order.model.InventoryReservationStatus;
import com.shopee.monolith.modules.order.model.OrderStatus;
import com.shopee.monolith.modules.order.repository.CheckoutSessionRepository;
import com.shopee.monolith.modules.order.repository.InventoryReservationRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutSettlementServiceImpl implements CheckoutSettlementService {

    /** Matches payment.model.PaymentMethod.COD#name() — order module stays decoupled from payment's enum. */
    private static final String COD_METHOD = "COD";

    private final CheckoutSessionRepository checkoutSessionRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final OrderRepository orderRepository;
    private final InventoryService inventoryService;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public Optional<CheckoutSessionPaymentInfo> findSessionPaymentInfo(UUID checkoutSessionId) {
        return checkoutSessionRepository.findById(checkoutSessionId).map(session -> {
            List<UUID> orderIds = orderRepository.findAllByCheckoutSessionIdOrderByIdAsc(checkoutSessionId)
                    .stream().map(Order::getId).toList();
            return CheckoutSessionPaymentInfo.builder()
                    .checkoutSessionId(session.getId())
                    .buyerId(session.getBuyerId())
                    .status(session.getStatus())
                    .totalAmount(session.getTotalAmount())
                    .expiresAt(session.getExpiresAt())
                    .orderIds(orderIds)
                    .build();
        });
    }

    @Override
    @Transactional
    public boolean confirmCheckoutSession(UUID checkoutSessionId, String paymentMethod) {
        CheckoutSession session = lockPendingSession(checkoutSessionId);
        if (session == null) {
            return false;
        }

        List<InventoryReservation> reservations = inventoryReservationRepository
                .findAllByCheckoutSessionIdAndStatusForUpdate(checkoutSessionId, InventoryReservationStatus.RESERVED.name());
        if (!reservations.isEmpty()) {
            List<ConfirmInventoryCommand> commands = groupQuantities(reservations).entrySet().stream()
                    .map(entry -> new ConfirmInventoryCommand(entry.getKey(), entry.getValue()))
                    .toList();
            inventoryService.confirm(commands);
            reservations.forEach(InventoryReservation::confirm);
            inventoryReservationRepository.saveAll(reservations);
        }

        List<Order> orders = orderRepository.findAllByCheckoutSessionIdForUpdate(checkoutSessionId);
        List<Order> payableOrders = orders.stream()
                .filter(o -> o.getStatus() == OrderStatus.PENDING_PAYMENT)
                .toList();

        if (payableOrders.isEmpty()) {
            log.info("Checkout session {} has no payable orders — cancelling session", checkoutSessionId);
            session.cancel();
            checkoutSessionRepository.save(session);
            return false;
        }

        payableOrders.forEach(order -> {
            if (COD_METHOD.equals(paymentMethod)) {
                order.confirmCod(paymentMethod);
            } else {
                order.markPaid(paymentMethod);
            }
        });
        orderRepository.saveAll(payableOrders);

        session.complete();
        checkoutSessionRepository.save(session);

        List<UUID> orderIds = payableOrders.stream().map(Order::getId).toList();
        eventPublisher.publishEvent(new OrderConfirmedEvent(
                checkoutSessionId, payableOrders.get(0).getBuyerId(), orderIds, paymentMethod));
        log.info("Checkout session {} confirmed with {} orders via {}", checkoutSessionId, payableOrders.size(), paymentMethod);
        return true;
    }

    @Override
    @Transactional
    public boolean markCheckoutPaymentExpired(UUID checkoutSessionId) {
        return releaseAndClose(checkoutSessionId, CheckoutSessionStatus.PAYMENT_EXPIRED);
    }

    @Override
    @Transactional
    public boolean markCheckoutPaymentFailed(UUID checkoutSessionId) {
        return releaseAndClose(checkoutSessionId, CheckoutSessionStatus.CANCELLED);
    }

    private boolean releaseAndClose(UUID checkoutSessionId, CheckoutSessionStatus targetStatus) {
        CheckoutSession session = lockPendingSession(checkoutSessionId);
        if (session == null) {
            return false;
        }

        List<InventoryReservation> reservations = inventoryReservationRepository
                .findAllByCheckoutSessionIdAndStatusForUpdate(checkoutSessionId, InventoryReservationStatus.RESERVED.name());
        if (!reservations.isEmpty()) {
            List<ReleaseInventoryCommand> commands = groupQuantities(reservations).entrySet().stream()
                    .map(entry -> new ReleaseInventoryCommand(entry.getKey(), entry.getValue()))
                    .toList();
            inventoryService.release(commands);
            reservations.forEach(InventoryReservation::release);
            inventoryReservationRepository.saveAll(reservations);
        }

        List<Order> orders = orderRepository.findAllByCheckoutSessionIdForUpdate(checkoutSessionId);
        for (Order order : orders) {
            if (targetStatus == CheckoutSessionStatus.PAYMENT_EXPIRED) {
                order.markPaymentExpired();
            } else {
                order.markPaymentFailed();
            }
        }
        orderRepository.saveAll(orders);

        if (targetStatus == CheckoutSessionStatus.PAYMENT_EXPIRED) {
            session.markPaymentExpired();
        } else {
            session.cancel();
        }
        checkoutSessionRepository.save(session);
        log.info("Checkout session {} closed with status {}", checkoutSessionId, targetStatus);
        return true;
    }

    private CheckoutSession lockPendingSession(UUID checkoutSessionId) {
        CheckoutSession session = checkoutSessionRepository.findByIdForUpdate(checkoutSessionId).orElse(null);
        if (session == null || session.getStatus() != CheckoutSessionStatus.PENDING_PAYMENT) {
            log.info("Checkout session {} not in PENDING_PAYMENT — settlement no-op", checkoutSessionId);
            return null;
        }
        return session;
    }

    private java.util.Map<UUID, Integer> groupQuantities(List<InventoryReservation> reservations) {
        return reservations.stream().collect(Collectors.groupingBy(
                InventoryReservation::getVariantId,
                Collectors.summingInt(InventoryReservation::getQuantity)));
    }
}
