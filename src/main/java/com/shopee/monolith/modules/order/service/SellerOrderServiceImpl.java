package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.order.dto.response.SellerDashboardResponse;
import com.shopee.monolith.modules.order.dto.response.SellerOrderDetailResponse;
import com.shopee.monolith.modules.order.dto.response.SellerOrderSummaryResponse;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.entity.OrderItem;
import com.shopee.monolith.modules.order.event.OrderFulfillmentChangedEvent;
import com.shopee.monolith.modules.order.mapper.BuyerOrderMapper;
import com.shopee.monolith.modules.order.model.FulfillmentStatus;
import com.shopee.monolith.modules.order.model.OrderPaymentStatus;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.service.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SellerOrderServiceImpl implements SellerOrderService {

    /** Dashboard key for orders that have no fulfillment state yet (not paid). */
    private static final String UNFULFILLED_KEY = "UNFULFILLED";

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final ShopService shopService;
    private final ProductService productService;
    private final BuyerOrderMapper buyerOrderMapper;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<SellerOrderSummaryResponse> listOrders(
            UUID sellerId, FulfillmentStatus fulfillmentStatus, Pageable pageable) {
        UUID shopId = resolveOwnShopId(sellerId);

        Page<Order> page = fulfillmentStatus == null
                ? orderRepository.findAllByShopIdOrderByCreatedAtDesc(shopId, pageable)
                : orderRepository.findAllByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(
                        shopId, fulfillmentStatus, pageable);

        Map<UUID, Long> itemCounts = countItems(page.getContent());
        List<SellerOrderSummaryResponse> summaries = page.getContent().stream()
                .map(order -> toSummary(order, itemCounts))
                .toList();
        return PagedResponse.from(page, summaries);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerOrderDetailResponse getOrderDetail(UUID sellerId, UUID orderId) {
        UUID shopId = resolveOwnShopId(sellerId);
        Order order = orderRepository.findByIdAndShopId(orderId, shopId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        return toDetail(order);
    }

    @Override
    @Transactional
    public SellerOrderDetailResponse shipOrder(UUID sellerId, UUID orderId) {
        Order order = lockOwnOrder(sellerId, orderId);
        order.ship();
        order = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderFulfillmentChangedEvent(
                order.getId(), order.getBuyerId(), order.getShopId(), order.getItemsSubtotal(),
                order.getFulfillmentStatus().name()));
        log.info("Seller {} shipped order {}", sellerId, orderId);
        return toDetail(order);
    }

    @Override
    @Transactional
    public SellerOrderDetailResponse deliverOrder(UUID sellerId, UUID orderId) {
        Order order = lockOwnOrder(sellerId, orderId);
        order.deliver();
        order = orderRepository.save(order);
        eventPublisher.publishEvent(new OrderFulfillmentChangedEvent(
                order.getId(), order.getBuyerId(), order.getShopId(), order.getItemsSubtotal(),
                order.getFulfillmentStatus().name()));
        log.info("Seller {} marked order {} delivered", sellerId, orderId);
        return toDetail(order);
    }

    @Override
    @Transactional(readOnly = true)
    public SellerDashboardResponse getDashboard(UUID sellerId) {
        UUID shopId = resolveOwnShopId(sellerId);

        Map<String, Long> productCounts = productService.countShopProductsByStatus(shopId);
        Map<String, Long> fulfillmentCounts = groupCounts(
                orderRepository.countByShopIdGroupByFulfillmentStatus(shopId), UNFULFILLED_KEY);
        Map<String, Long> paymentCounts = groupCounts(
                orderRepository.countByShopIdGroupByPaymentStatus(shopId), OrderPaymentStatus.UNPAID.name());

        List<Order> actionable = orderRepository
                .findTop5ByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(shopId, FulfillmentStatus.READY_TO_SHIP);
        Map<UUID, Long> itemCounts = countItems(actionable);

        return SellerDashboardResponse.builder()
                .shopId(shopId)
                .totalProducts(productCounts.entrySet().stream()
                        .filter(entry -> !ProductStatus.DELETED.name().equals(entry.getKey()))
                        .mapToLong(Map.Entry::getValue)
                        .sum())
                .activeProducts(productCounts.getOrDefault("ACTIVE", 0L))
                .productCountsByStatus(productCounts)
                .orderCountsByFulfillmentStatus(fulfillmentCounts)
                .orderCountsByPaymentStatus(paymentCounts)
                .latestActionableOrders(actionable.stream()
                        .map(order -> toSummary(order, itemCounts))
                        .toList())
                .build();
    }

    private UUID resolveOwnShopId(UUID sellerId) {
        return shopService.findShopLookupDataByOwnerId(sellerId)
                .map(ShopLookupData::id)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
    }

    private Order lockOwnOrder(UUID sellerId, UUID orderId) {
        UUID shopId = resolveOwnShopId(sellerId);
        return orderRepository.findByIdAndShopIdForUpdate(orderId, shopId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
    }

    private Map<UUID, Long> countItems(List<Order> orders) {
        List<UUID> orderIds = orders.stream().map(Order::getId).toList();
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        return orderItemRepository.findAllByOrderIdIn(orderIds).stream()
                .collect(Collectors.groupingBy(OrderItem::getOrderId, Collectors.counting()));
    }

    private SellerOrderSummaryResponse toSummary(Order order, Map<UUID, Long> itemCounts) {
        return SellerOrderSummaryResponse.builder()
                .orderId(order.getId())
                .status(order.getStatus().name())
                .paymentStatus(order.getPaymentStatus().name())
                .paymentMethod(order.getPaymentMethod())
                .fulfillmentStatus(order.getFulfillmentStatus() != null ? order.getFulfillmentStatus().name() : null)
                .totalAmount(order.getTotalAmount())
                .itemCount(itemCounts.getOrDefault(order.getId(), 0L).intValue())
                .shippingRecipientName(order.getShippingRecipientName())
                .createdAt(order.getCreatedAt())
                .build();
    }

    private SellerOrderDetailResponse toDetail(Order order) {
        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        return SellerOrderDetailResponse.builder()
                .orderId(order.getId())
                .checkoutSessionId(order.getCheckoutSessionId())
                .status(order.getStatus().name())
                .paymentStatus(order.getPaymentStatus().name())
                .paymentMethod(order.getPaymentMethod())
                .fulfillmentStatus(order.getFulfillmentStatus() != null ? order.getFulfillmentStatus().name() : null)
                .itemsSubtotal(order.getItemsSubtotal())
                .shippingFee(order.getShippingFee())
                .totalAmount(order.getTotalAmount())
                .discountAmount(order.getDiscountAmount())
                .shippingRecipientName(order.getShippingRecipientName())
                .shippingPhone(order.getShippingPhone())
                .shippingAddressLine(order.getShippingAddressLine())
                .shippingWardName(order.getShippingWardName())
                .shippingDistrictName(order.getShippingDistrictName())
                .shippingProvinceName(order.getShippingProvinceName())
                .items(buyerOrderMapper.toItemResponses(items))
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }

    /**
     * Folds repository group-by rows into a name → count map.
     * Null group keys (orders without fulfillment state) are bucketed under {@code nullKey}.
     */
    private Map<String, Long> groupCounts(List<Object[]> rows, String nullKey) {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (Object[] row : rows) {
            String key = row[0] != null ? ((Enum<?>) row[0]).name() : nullKey;
            counts.merge(key, (Long) row[1], Long::sum);
        }
        return counts;
    }
}
