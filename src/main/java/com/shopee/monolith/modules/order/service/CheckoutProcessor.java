package com.shopee.monolith.modules.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.inventory.dto.command.ReserveInventoryCommand;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.shopee.monolith.modules.order.entity.CheckoutSession;
import com.shopee.monolith.modules.order.entity.IdempotencyKey;
import com.shopee.monolith.modules.order.entity.InventoryReservation;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.entity.OrderItem;
import com.shopee.monolith.modules.order.model.CheckoutSessionStatus;
import com.shopee.monolith.modules.order.model.IdempotencyStatus;
import com.shopee.monolith.modules.order.model.InventoryReservationStatus;
import com.shopee.monolith.modules.order.model.OrderStatus;
import com.shopee.monolith.modules.order.repository.CheckoutSessionRepository;
import com.shopee.monolith.modules.order.repository.IdempotencyKeyRepository;
import com.shopee.monolith.modules.order.repository.InventoryReservationRepository;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import com.shopee.monolith.modules.product.dto.internal.ProductLookupData;
import com.shopee.monolith.modules.product.dto.internal.VariantLookupData;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.user.dto.response.AddressResponse;
import com.shopee.monolith.modules.voucher.service.VoucherService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class CheckoutProcessor {

    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final CheckoutSessionRepository checkoutSessionRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final InventoryReservationRepository inventoryReservationRepository;
    private final InventoryService inventoryService;
    private final ProductService productService;
    private final ShippingFeeEstimator shippingFeeEstimator;
    private final ObjectMapper objectMapper;
    private final VoucherService voucherService;

    @Transactional
    public CheckoutResponse processCheckout(UUID buyerId, AddressResponse address, String idempotencyKey,
                                            String requestHash, String requestBodyHash, UUID keyId, Instant expiresAt,
                                            List<CartSnapshotItem> cartItems, String voucherCode,
                                            Runnable postCommitAction) {

        int inserted = idempotencyKeyRepository.tryInsert(
                keyId,
                buyerId,
                "CHECKOUT",
                idempotencyKey,
                requestHash,
                requestBodyHash,
                IdempotencyStatus.PROCESSING.name(),
                expiresAt
        );

        IdempotencyKey activeKey;
        if (inserted > 0) {
            activeKey = IdempotencyKey.builder()
                    .id(keyId)
                    .actorId(buyerId)
                    .operation("CHECKOUT")
                    .idempotencyKey(idempotencyKey)
                    .requestHash(requestHash)
                    .requestBodyHash(requestBodyHash)
                    .status(IdempotencyStatus.PROCESSING)
                    .expiresAt(expiresAt)
                    .build();
        } else {
            // Duplicate key: lock and inspect
            activeKey = idempotencyKeyRepository.findByKeysForUpdate(buyerId, "CHECKOUT", idempotencyKey)
                    .orElseThrow(() -> new AppException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT));

            if (activeKey.getExpiresAt().isBefore(Instant.now())) {
                // Key has expired: reset to PROCESSING and continue checkout normally
                activeKey.reset(requestHash, requestBodyHash, expiresAt);
                idempotencyKeyRepository.save(activeKey);
            } else {
                if (!requestBodyMatches(activeKey, requestBodyHash) && !legacyRequestMatches(activeKey, requestHash)) {
                    throw new AppException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                }
                if (!activeKey.getRequestHash().equals(requestHash)) {
                    throw new AppException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                }

                if (activeKey.getStatus() == IdempotencyStatus.PROCESSING) {
                    throw new AppException(ErrorCode.IDEMPOTENCY_REQUEST_PROCESSING);
                }

                // COMPLETED: deserialize cached response
                try {
                    return objectMapper.readValue(activeKey.getResponseBody(), CheckoutResponse.class);
                } catch (Exception e) {
                    log.error("Failed to deserialize cached checkout response", e);
                    throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
                }
            }
        }

        List<OrderServiceImpl.CartItemWithDetails> resolvedItems = resolveCheckoutItemsForUpdate(cartItems);

        BigDecimal totalAmount = BigDecimal.ZERO;
        List<OrderCreationInfo> orderCreationInfos = new ArrayList<>();
        List<ReserveInventoryCommand> reserveCommands = new ArrayList<>();

        for (OrderServiceImpl.CartItemWithDetails item : resolvedItems) {
            reserveCommands.add(new ReserveInventoryCommand(item.cartItem().variantId(), item.cartItem().quantity()));
        }

        Map<UUID, List<OrderServiceImpl.CartItemWithDetails>> itemsByShop = resolvedItems.stream()
                .collect(Collectors.groupingBy(item -> item.product().shopId()));

        CheckoutSession session = CheckoutSession.builder()
                .buyerId(buyerId)
                .status(CheckoutSessionStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.ZERO) // updated after per-shop loop
                .shippingRecipientName(address.recipientName())
                .shippingPhone(address.phone())
                .shippingAddressLine(address.addressLine())
                .shippingWardCode(address.wardCode())
                .shippingWardName(address.wardName())
                .shippingDistrictCode(address.districtCode())
                .shippingDistrictName(address.districtName())
                .shippingProvinceCode(address.provinceCode())
                .shippingProvinceName(address.provinceName())
                .expiresAt(Instant.now().plus(Duration.ofMinutes(15)))
                .build();
        session = checkoutSessionRepository.save(session);

        List<UUID> orderIds = new ArrayList<>();
        BigDecimal sessionItemsSubtotal = BigDecimal.ZERO;
        BigDecimal sessionShippingFee = BigDecimal.ZERO;

        for (Map.Entry<UUID, List<OrderServiceImpl.CartItemWithDetails>> entry : itemsByShop.entrySet()) {
            UUID shopId = entry.getKey();
            List<OrderServiceImpl.CartItemWithDetails> shopItems = entry.getValue();

            BigDecimal shopItemsSubtotal = shopItems.stream()
                    .map(item -> item.variant().price().multiply(BigDecimal.valueOf(item.cartItem().quantity())))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem> shopCartItems =
                    shopItems.stream().map(OrderServiceImpl.CartItemWithDetails::cartItem).toList();
            BigDecimal shopShippingFee = shippingFeeEstimator.estimateFee(shopId, shopCartItems, address);
            BigDecimal shopTotal = shopItemsSubtotal.add(shopShippingFee);

            sessionItemsSubtotal = sessionItemsSubtotal.add(shopItemsSubtotal);
            sessionShippingFee = sessionShippingFee.add(shopShippingFee);
            totalAmount = totalAmount.add(shopTotal);

            Order order = Order.builder()
                    .buyerId(buyerId)
                    .shopId(shopId)
                    .checkoutSessionId(session.getId())
                    .status(OrderStatus.PENDING_PAYMENT)
                    .itemsSubtotal(shopItemsSubtotal)
                    .shippingFee(shopShippingFee)
                    .totalAmount(shopTotal)
                    .shippingRecipientName(address.recipientName())
                    .shippingPhone(address.phone())
                    .shippingAddressLine(address.addressLine())
                    .shippingWardCode(address.wardCode())
                    .shippingWardName(address.wardName())
                    .shippingDistrictCode(address.districtCode())
                    .shippingDistrictName(address.districtName())
                    .shippingProvinceCode(address.provinceCode())
                    .shippingProvinceName(address.provinceName())
                    .build();
            order = orderRepository.save(order);
            orderIds.add(order.getId());

            List<OrderItem> orderItems = new ArrayList<>();
            for (OrderServiceImpl.CartItemWithDetails detail : shopItems) {
                BigDecimal subtotal = detail.variant().price().multiply(BigDecimal.valueOf(detail.cartItem().quantity()));
                OrderItem orderItem = OrderItem.builder()
                        .orderId(order.getId())
                        .variantId(detail.cartItem().variantId())
                        .productName(detail.product().name())
                        .variantName(detail.variant().name())
                        .sku(detail.variant().sku())
                        .price(detail.variant().price())
                        .quantity(detail.cartItem().quantity())
                        .subtotal(subtotal)
                        .build();
                orderItems.add(orderItem);
            }
            orderItemRepository.saveAll(orderItems);
            orderCreationInfos.add(new OrderCreationInfo(order, orderItems));
        }

        session.updateTotals(sessionItemsSubtotal, sessionShippingFee);

        BigDecimal discountAmount = null;
        if (voucherCode != null && !voucherCode.isBlank()) {
            discountAmount = voucherService.reserveVoucher(voucherCode, session.getId(), buyerId, sessionItemsSubtotal);
            session.applyDiscount(discountAmount);
            totalAmount = totalAmount.subtract(discountAmount);
            distributeDiscountAcrossOrders(orderCreationInfos, discountAmount, sessionItemsSubtotal);
        }

        checkoutSessionRepository.save(session);

        // Reserve inventory (this internally does SELECT FOR UPDATE on inventories table)
        inventoryService.reserve(reserveCommands);

        // Save reservations ledger
        for (OrderCreationInfo oInfo : orderCreationInfos) {
            List<InventoryReservation> reservations = new ArrayList<>();
            for (OrderItem item : oInfo.items) {
                reservations.add(InventoryReservation.builder()
                        .checkoutSessionId(session.getId())
                        .orderId(oInfo.order.getId())
                        .variantId(item.getVariantId())
                        .quantity(item.getQuantity())
                        .status(InventoryReservationStatus.RESERVED)
                        .expiresAt(session.getExpiresAt())
                        .build());
            }
            inventoryReservationRepository.saveAll(reservations);
        }

        CheckoutResponse response = CheckoutResponse.builder()
                .checkoutSessionId(session.getId())
                .orderIds(orderIds)
                .status(CheckoutSessionStatus.PENDING_PAYMENT.name())
                .itemsSubtotal(sessionItemsSubtotal)
                .shippingFee(sessionShippingFee)
                .totalAmount(totalAmount)
                .expiresAt(session.getExpiresAt())
                .discountAmount(discountAmount)
                .build();

        // Save Response and complete IdempotencyKey
        try {
            String responseBody = objectMapper.writeValueAsString(response);
            activeKey.complete(responseBody);
            idempotencyKeyRepository.save(activeKey);
        } catch (Exception e) {
            log.error("Failed to serialize checkout response for idempotency caching", e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    try {
                        postCommitAction.run();
                    } catch (Exception e) {
                        log.error("Failed to execute post-commit action", e);
                    }
                }
            });
        }

        return response;
    }

    /**
     * Splits a single checkout-level voucher discount across the per-shop orders proportionally
     * to each order's items subtotal. The last order absorbs the rounding remainder so the sum of
     * per-order discounts always equals the total discount exactly.
     */
    private void distributeDiscountAcrossOrders(List<OrderCreationInfo> orderInfos, BigDecimal totalDiscount,
                                                 BigDecimal totalSubtotal) {
        if (totalSubtotal.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < orderInfos.size(); i++) {
            Order order = orderInfos.get(i).order();
            BigDecimal share;
            if (i == orderInfos.size() - 1) {
                share = totalDiscount.subtract(allocated);
            } else {
                share = totalDiscount.multiply(order.getItemsSubtotal())
                        .divide(totalSubtotal, 2, RoundingMode.HALF_UP);
                allocated = allocated.add(share);
            }
            order.applyDiscount(share);
        }
        orderRepository.saveAll(orderInfos.stream().map(OrderCreationInfo::order).toList());
    }

    private boolean requestBodyMatches(IdempotencyKey key, String requestBodyHash) {
        return key.getRequestBodyHash().equals(requestBodyHash);
    }

    private boolean legacyRequestMatches(IdempotencyKey key, String requestHash) {
        return key.getRequestBodyHash().equals(key.getRequestHash())
                && key.getRequestHash().equals(requestHash);
    }

    private record OrderCreationInfo(
            Order order,
            List<OrderItem> items
    ) {}

    private List<OrderServiceImpl.CartItemWithDetails> resolveCheckoutItemsForUpdate(List<CartSnapshotItem> cartItems) {
        return cartItems.stream()
                .sorted(java.util.Comparator.comparing(CartSnapshotItem::variantId))
                .map(this::resolveCheckoutItemForUpdate)
                .toList();
    }

    private OrderServiceImpl.CartItemWithDetails resolveCheckoutItemForUpdate(CartSnapshotItem item) {
        VariantLookupData variant = productService.findActiveVariantLookupDataByIdForCheckout(item.variantId())
                .orElseThrow(() -> new AppException(ErrorCode.VARIANT_NOT_FOUND));
        ProductLookupData product = productService.findActiveProductLookupDataByIdForCheckout(variant.productId())
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));
        return new OrderServiceImpl.CartItemWithDetails(item, variant, product);
    }
}
