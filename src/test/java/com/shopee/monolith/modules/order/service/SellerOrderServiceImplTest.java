package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.order.dto.response.SellerDashboardResponse;
import com.shopee.monolith.modules.order.dto.response.SellerOrderDetailResponse;
import com.shopee.monolith.modules.order.dto.response.SellerOrderSummaryResponse;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.mapper.BuyerOrderMapper;
import com.shopee.monolith.modules.order.model.FulfillmentStatus;
import com.shopee.monolith.modules.order.model.OrderPaymentStatus;
import com.shopee.monolith.modules.order.model.OrderStatus;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.service.ShopService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SellerOrderServiceImplTest {

    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private ShopService shopService;
    @Mock
    private ProductService productService;
    @Mock
    private BuyerOrderMapper buyerOrderMapper;

    @Mock
    private com.shopee.monolith.common.observability.DemoMetrics demoMetrics;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private SellerOrderServiceImpl sellerOrderService;

    private final UUID sellerId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    private ShopLookupData shopLookup;

    @BeforeEach
    void setUp() {
        shopLookup = ShopLookupData.builder()
                .id(shopId)
                .ownerId(sellerId)
                .name("Seller Shop")
                .build();
    }

    private void stubOwnShop() {
        when(shopService.findShopLookupDataByOwnerId(sellerId)).thenReturn(Optional.of(shopLookup));
    }

    private Order paidOrder() {
        Order order = Order.builder()
                .id(orderId)
                .buyerId(UUID.randomUUID())
                .shopId(shopId)
                .checkoutSessionId(UUID.randomUUID())
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(100))
                .itemsSubtotal(BigDecimal.valueOf(100))
                .shippingFee(BigDecimal.ZERO)
                .shippingRecipientName("Buyer")
                .shippingPhone("0900000000")
                .shippingAddressLine("1 Street")
                .shippingWardCode("W").shippingWardName("Ward")
                .shippingDistrictCode("D").shippingDistrictName("District")
                .shippingProvinceCode("P").shippingProvinceName("Province")
                .build();
        order.markPaid("COD");
        return order;
    }

    @Test
    void listOrdersWhenNoShopShouldThrowShopNotFound() {
        when(shopService.findShopLookupDataByOwnerId(sellerId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class,
                () -> sellerOrderService.listOrders(sellerId, null, PageRequest.of(0, 20)));
        assertEquals(ErrorCode.SHOP_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void listOrdersWhenNoFilterShouldReturnAllShopOrders() {
        stubOwnShop();
        Order order = paidOrder();
        when(orderRepository.findAllByShopIdOrderByCreatedAtDesc(any(UUID.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(order)));
        when(orderItemRepository.findAllByOrderIdIn(List.of(orderId))).thenReturn(List.of());

        PagedResponse<SellerOrderSummaryResponse> response =
                sellerOrderService.listOrders(sellerId, null, PageRequest.of(0, 20));

        assertEquals(1, response.totalElements());
        SellerOrderSummaryResponse summary = response.items().get(0);
        assertEquals(orderId, summary.orderId());
        assertEquals(FulfillmentStatus.READY_TO_SHIP.name(), summary.fulfillmentStatus());
        verify(orderRepository, never())
                .findAllByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(any(), any(), any());
    }

    @Test
    void listOrdersWhenFulfillmentFilterShouldUseFilteredQuery() {
        stubOwnShop();
        when(orderRepository.findAllByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(
                any(UUID.class), any(FulfillmentStatus.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        sellerOrderService.listOrders(sellerId, FulfillmentStatus.READY_TO_SHIP, PageRequest.of(0, 20));

        verify(orderRepository).findAllByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(
                shopId, FulfillmentStatus.READY_TO_SHIP, PageRequest.of(0, 20));
    }

    @Test
    void getOrderDetailWhenOrderNotInOwnShopShouldThrowOrderNotFound() {
        stubOwnShop();
        when(orderRepository.findByIdAndShopId(orderId, shopId)).thenReturn(Optional.empty());

        AppException ex = assertThrows(AppException.class,
                () -> sellerOrderService.getOrderDetail(sellerId, orderId));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void shipOrderWhenReadyToShipShouldTransitionToShipped() {
        stubOwnShop();
        Order order = paidOrder();
        when(orderRepository.findByIdAndShopIdForUpdate(orderId, shopId)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(orderItemRepository.findAllByOrderId(orderId)).thenReturn(List.of());
        when(buyerOrderMapper.toItemResponses(List.of())).thenReturn(List.of());

        SellerOrderDetailResponse detail = sellerOrderService.shipOrder(sellerId, orderId);

        assertEquals(FulfillmentStatus.SHIPPED.name(), detail.fulfillmentStatus());
        assertEquals(OrderStatus.FULFILLED.name(), detail.status());
    }

    @Test
    void shipOrderWhenNotPaidShouldThrowInvalidState() {
        stubOwnShop();
        Order order = Order.builder()
                .id(orderId)
                .shopId(shopId)
                .status(OrderStatus.PENDING_PAYMENT)
                .paymentStatus(OrderPaymentStatus.UNPAID)
                .build();
        when(orderRepository.findByIdAndShopIdForUpdate(orderId, shopId)).thenReturn(Optional.of(order));

        AppException ex = assertThrows(AppException.class,
                () -> sellerOrderService.shipOrder(sellerId, orderId));
        assertEquals(ErrorCode.ORDER_FULFILLMENT_INVALID_STATE, ex.getErrorCode());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void deliverOrderWhenShippedShouldTransitionToDelivered() {
        stubOwnShop();
        Order order = paidOrder();
        order.ship();
        when(orderRepository.findByIdAndShopIdForUpdate(orderId, shopId)).thenReturn(Optional.of(order));
        when(orderRepository.save(order)).thenReturn(order);
        when(orderItemRepository.findAllByOrderId(orderId)).thenReturn(List.of());
        when(buyerOrderMapper.toItemResponses(List.of())).thenReturn(List.of());

        SellerOrderDetailResponse detail = sellerOrderService.deliverOrder(sellerId, orderId);

        assertEquals(FulfillmentStatus.DELIVERED.name(), detail.fulfillmentStatus());
        assertEquals(OrderStatus.DELIVERED.name(), detail.status());
    }

    @Test
    void deliverOrderWhenNotShippedShouldThrowInvalidState() {
        stubOwnShop();
        Order order = paidOrder();
        when(orderRepository.findByIdAndShopIdForUpdate(orderId, shopId)).thenReturn(Optional.of(order));

        AppException ex = assertThrows(AppException.class,
                () -> sellerOrderService.deliverOrder(sellerId, orderId));
        assertEquals(ErrorCode.ORDER_FULFILLMENT_INVALID_STATE, ex.getErrorCode());
        verify(orderRepository, never()).save(any());
    }

    @Test
    void getDashboardShouldAggregateProductAndOrderCounts() {
        stubOwnShop();
        when(productService.countShopProductsByStatus(shopId))
                .thenReturn(Map.of("ACTIVE", 3L, "DRAFT", 2L));
        when(orderRepository.countByShopIdGroupByFulfillmentStatus(shopId)).thenReturn(List.of(
                new Object[]{FulfillmentStatus.READY_TO_SHIP, 2L},
                new Object[]{null, 1L}));
        when(orderRepository.countByShopIdGroupByPaymentStatus(shopId)).thenReturn(List.of(
                new Object[]{OrderPaymentStatus.PAID, 2L},
                new Object[]{OrderPaymentStatus.UNPAID, 1L}));
        Order actionable = paidOrder();
        when(orderRepository.findTop5ByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(
                shopId, FulfillmentStatus.READY_TO_SHIP)).thenReturn(List.of(actionable));
        when(orderItemRepository.findAllByOrderIdIn(List.of(orderId))).thenReturn(List.of());

        SellerDashboardResponse dashboard = sellerOrderService.getDashboard(sellerId);

        assertEquals(shopId, dashboard.shopId());
        assertEquals(5, dashboard.totalProducts());
        assertEquals(3, dashboard.activeProducts());
        assertEquals(2L, dashboard.orderCountsByFulfillmentStatus().get("READY_TO_SHIP"));
        assertEquals(1L, dashboard.orderCountsByFulfillmentStatus().get("UNFULFILLED"));
        assertEquals(2L, dashboard.orderCountsByPaymentStatus().get("PAID"));
        assertEquals(1, dashboard.latestActionableOrders().size());
        assertEquals(orderId, dashboard.latestActionableOrders().get(0).orderId());
    }

    @Test
    void getDashboardShouldExcludeDeletedProductsFromTotal() {
        stubOwnShop();
        when(productService.countShopProductsByStatus(shopId))
                .thenReturn(Map.of("ACTIVE", 3L, "DRAFT", 2L, "DELETED", 4L));
        when(orderRepository.countByShopIdGroupByFulfillmentStatus(shopId)).thenReturn(List.of());
        when(orderRepository.countByShopIdGroupByPaymentStatus(shopId)).thenReturn(List.of());
        when(orderRepository.findTop5ByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(
                shopId, FulfillmentStatus.READY_TO_SHIP)).thenReturn(List.of());

        SellerDashboardResponse dashboard = sellerOrderService.getDashboard(sellerId);

        assertEquals(5, dashboard.totalProducts());
        assertEquals(3, dashboard.activeProducts());
    }

    @Test
    void getDashboardWhenShopEmptyShouldReturnZeroCounts() {
        stubOwnShop();
        when(productService.countShopProductsByStatus(shopId)).thenReturn(Map.of());
        when(orderRepository.countByShopIdGroupByFulfillmentStatus(shopId)).thenReturn(List.of());
        when(orderRepository.countByShopIdGroupByPaymentStatus(shopId)).thenReturn(List.of());
        when(orderRepository.findTop5ByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(
                shopId, FulfillmentStatus.READY_TO_SHIP)).thenReturn(List.of());

        SellerDashboardResponse dashboard = sellerOrderService.getDashboard(sellerId);

        assertEquals(0, dashboard.totalProducts());
        assertEquals(0, dashboard.activeProducts());
        assertTrue(dashboard.orderCountsByFulfillmentStatus().isEmpty());
        assertTrue(dashboard.latestActionableOrders().isEmpty());
        assertNull(dashboard.orderCountsByPaymentStatus().get("PAID"));
    }
}
