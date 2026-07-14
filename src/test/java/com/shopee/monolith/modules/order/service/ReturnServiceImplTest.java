package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.inventory.dto.command.RestockInventoryCommand;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.config.ReturnProperties;
import com.shopee.monolith.modules.order.dto.request.RequestReturnRequest;
import com.shopee.monolith.modules.order.dto.request.ResolveReturnRequest;
import com.shopee.monolith.modules.order.dto.response.ReturnResponse;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.entity.OrderItem;
import com.shopee.monolith.modules.order.entity.Return;
import com.shopee.monolith.modules.order.model.OrderStatus;
import com.shopee.monolith.modules.order.model.ReturnReasonCategory;
import com.shopee.monolith.modules.order.model.ReturnStatus;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import com.shopee.monolith.modules.order.repository.ReturnEvidenceRepository;
import com.shopee.monolith.modules.order.repository.ReturnRepository;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.service.ShopService;
import com.shopee.monolith.modules.wallet.model.WalletReferenceType;
import com.shopee.monolith.modules.wallet.model.WalletTransactionType;
import com.shopee.monolith.modules.wallet.service.WalletService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReturnServiceImplTest {

    @Mock
    private ReturnRepository returnRepository;
    @Mock
    private ReturnEvidenceRepository returnEvidenceRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private WalletService walletService;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private ShopService shopService;

    private ReturnServiceImpl returnService;

    private final Instant now = Instant.parse("2026-06-20T12:00:00Z");
    private final UUID buyerId = UUID.randomUUID();
    private final UUID sellerId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();
    private final UUID orderId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        ReturnProperties properties = new ReturnProperties();
        properties.setWindowDays(7);
        Clock clock = Clock.fixed(now, ZoneOffset.UTC);
        returnService = new ReturnServiceImpl(returnRepository, returnEvidenceRepository, orderRepository,
                orderItemRepository, walletService, inventoryService, shopService, properties, clock);
    }

    private Order deliveredOrder(Instant deliveredAt) {
        return Order.builder()
                .buyerId(buyerId)
                .shopId(shopId)
                .checkoutSessionId(UUID.randomUUID())
                .status(OrderStatus.DELIVERED)
                .totalAmount(new BigDecimal("100000"))
                .itemsSubtotal(new BigDecimal("90000"))
                .shippingFee(new BigDecimal("10000"))
                .shippingRecipientName("A")
                .shippingPhone("0900000000")
                .shippingAddressLine("addr")
                .shippingWardCode("w").shippingWardName("w")
                .shippingDistrictCode("d").shippingDistrictName("d")
                .shippingProvinceCode("p").shippingProvinceName("p")
                .build();
    }

    private RequestReturnRequest requestReturnRequest() {
        return new RequestReturnRequest(ReturnReasonCategory.DEFECTIVE, "broken", List.of());
    }

    @Test
    void requestReturnWhenOrderNotDeliveredShouldThrowNotEligible() {
        Order order = Order.builder()
                .buyerId(buyerId).shopId(shopId).checkoutSessionId(UUID.randomUUID())
                .status(OrderStatus.CONFIRMED)
                .totalAmount(BigDecimal.TEN).itemsSubtotal(BigDecimal.TEN).shippingFee(BigDecimal.ZERO)
                .shippingRecipientName("A").shippingPhone("p").shippingAddressLine("a")
                .shippingWardCode("w").shippingWardName("w")
                .shippingDistrictCode("d").shippingDistrictName("d")
                .shippingProvinceCode("p").shippingProvinceName("p")
                .build();
        when(orderRepository.findByIdAndBuyerId(orderId, buyerId)).thenReturn(Optional.of(order));

        AppException ex = assertThrows(AppException.class,
                () -> returnService.requestReturn(buyerId, orderId, requestReturnRequest()));
        assertEquals(ErrorCode.RETURN_NOT_ELIGIBLE, ex.getErrorCode());
    }

    @Test
    void requestReturnWhenWindowExpiredShouldThrowWindowExpired() {
        Order order = deliveredOrder(now.minus(Duration.ofDays(10)));
        setDeliveredAt(order, now.minus(Duration.ofDays(10)));
        when(orderRepository.findByIdAndBuyerId(orderId, buyerId)).thenReturn(Optional.of(order));

        AppException ex = assertThrows(AppException.class,
                () -> returnService.requestReturn(buyerId, orderId, requestReturnRequest()));
        assertEquals(ErrorCode.RETURN_WINDOW_EXPIRED, ex.getErrorCode());
    }

    @Test
    void requestReturnWhenEligibleShouldPersistReturn() {
        Order order = deliveredOrder(now.minus(Duration.ofDays(1)));
        setDeliveredAt(order, now.minus(Duration.ofDays(1)));
        when(orderRepository.findByIdAndBuyerId(orderId, buyerId)).thenReturn(Optional.of(order));
        when(returnRepository.saveAndFlush(any(Return.class))).thenAnswer(inv -> inv.getArgument(0));

        ReturnResponse response = returnService.requestReturn(buyerId, orderId, requestReturnRequest());

        assertEquals(ReturnStatus.REQUESTED, response.status());
        assertEquals(ReturnReasonCategory.DEFECTIVE, response.reasonCategory());
    }

    @Test
    void requestReturnWhenAlreadyExistsShouldThrowAlreadyExists() {
        Order order = deliveredOrder(now.minus(Duration.ofDays(1)));
        setDeliveredAt(order, now.minus(Duration.ofDays(1)));
        when(orderRepository.findByIdAndBuyerId(orderId, buyerId)).thenReturn(Optional.of(order));
        when(returnRepository.saveAndFlush(any(Return.class))).thenThrow(new DataIntegrityViolationException("dup"));

        AppException ex = assertThrows(AppException.class,
                () -> returnService.requestReturn(buyerId, orderId, requestReturnRequest()));
        assertEquals(ErrorCode.RETURN_ALREADY_EXISTS, ex.getErrorCode());
    }

    @Test
    void approveReturnShouldCreditBuyerDebitSellerAndRestockInventory() {
        UUID returnId = UUID.randomUUID();
        Return existing = Return.builder()
                .orderId(orderId).buyerId(buyerId).shopId(shopId)
                .reasonCategory(ReturnReasonCategory.DEFECTIVE)
                .status(ReturnStatus.REQUESTED)
                .build();
        setId(existing, returnId);
        Order order = deliveredOrder(now.minus(Duration.ofDays(1)));
        setId(order, orderId);

        when(shopService.findShopLookupDataByOwnerId(sellerId))
                .thenReturn(Optional.of(ShopLookupData.builder().id(shopId).ownerId(sellerId).build()));
        when(returnRepository.findByIdForUpdate(returnId)).thenReturn(Optional.of(existing));
        when(orderRepository.findById(orderId)).thenReturn(Optional.of(order));
        when(returnRepository.save(any(Return.class))).thenAnswer(inv -> inv.getArgument(0));
        OrderItem item = OrderItem.builder()
                .orderId(orderId).variantId(UUID.randomUUID())
                .productName("p").variantName("v").price(BigDecimal.TEN).quantity(2).subtotal(BigDecimal.TEN)
                .build();
        when(orderItemRepository.findAllByOrderId(orderId)).thenReturn(List.of(item));
        when(returnEvidenceRepository.findAllByReturnId(returnId)).thenReturn(List.of());

        ReturnResponse response = returnService.approveReturn(sellerId, returnId, new ResolveReturnRequest("ok"));

        assertEquals(ReturnStatus.APPROVED, response.status());
        assertEquals(new BigDecimal("100000"), response.refundAmount());
        verify(walletService).credit(buyerId, new BigDecimal("100000"),
                WalletTransactionType.RETURN_REFUND, WalletReferenceType.RETURN, returnId);
        verify(walletService).debit(sellerId, new BigDecimal("90000"),
                WalletTransactionType.RETURN_CLAWBACK, WalletReferenceType.RETURN, returnId);
        verify(inventoryService).restock(List.of(new RestockInventoryCommand(item.getVariantId(), 2)));
    }

    @Test
    void approveReturnWhenForeignShopShouldThrowNotFound() {
        UUID returnId = UUID.randomUUID();
        UUID otherShopId = UUID.randomUUID();
        Return existing = Return.builder()
                .orderId(orderId).buyerId(buyerId).shopId(otherShopId)
                .reasonCategory(ReturnReasonCategory.DEFECTIVE)
                .status(ReturnStatus.REQUESTED)
                .build();
        when(shopService.findShopLookupDataByOwnerId(sellerId))
                .thenReturn(Optional.of(ShopLookupData.builder().id(shopId).ownerId(sellerId).build()));
        when(returnRepository.findByIdForUpdate(returnId)).thenReturn(Optional.of(existing));

        AppException ex = assertThrows(AppException.class,
                () -> returnService.approveReturn(sellerId, returnId, new ResolveReturnRequest("ok")));
        assertEquals(ErrorCode.RETURN_NOT_FOUND, ex.getErrorCode());
        verify(walletService, never()).credit(any(), any(), any(), any(), any());
    }

    @Test
    void approveReturnWhenNotRequestedShouldThrowInvalidState() {
        UUID returnId = UUID.randomUUID();
        Return existing = Return.builder()
                .orderId(orderId).buyerId(buyerId).shopId(shopId)
                .reasonCategory(ReturnReasonCategory.DEFECTIVE)
                .status(ReturnStatus.REJECTED)
                .build();
        when(shopService.findShopLookupDataByOwnerId(sellerId))
                .thenReturn(Optional.of(ShopLookupData.builder().id(shopId).ownerId(sellerId).build()));
        when(returnRepository.findByIdForUpdate(returnId)).thenReturn(Optional.of(existing));

        AppException ex = assertThrows(AppException.class,
                () -> returnService.approveReturn(sellerId, returnId, new ResolveReturnRequest("ok")));
        assertEquals(ErrorCode.RETURN_INVALID_STATE, ex.getErrorCode());
    }

    @Test
    void rejectReturnShouldNotTouchWalletOrInventory() {
        UUID returnId = UUID.randomUUID();
        Return existing = Return.builder()
                .orderId(orderId).buyerId(buyerId).shopId(shopId)
                .reasonCategory(ReturnReasonCategory.DEFECTIVE)
                .status(ReturnStatus.REQUESTED)
                .build();
        setId(existing, returnId);
        when(shopService.findShopLookupDataByOwnerId(sellerId))
                .thenReturn(Optional.of(ShopLookupData.builder().id(shopId).ownerId(sellerId).build()));
        when(returnRepository.findByIdForUpdate(returnId)).thenReturn(Optional.of(existing));
        when(returnRepository.save(any(Return.class))).thenAnswer(inv -> inv.getArgument(0));
        when(returnEvidenceRepository.findAllByReturnId(returnId)).thenReturn(List.of());

        ReturnResponse response = returnService.rejectReturn(sellerId, returnId, new ResolveReturnRequest("no"));

        assertEquals(ReturnStatus.REJECTED, response.status());
        verify(walletService, never()).credit(any(), any(), any(), any(), any());
        verify(walletService, never()).debit(any(), any(), any(), any(), any());
        verify(inventoryService, never()).restock(any());
    }

    private void setDeliveredAt(Order order, Instant deliveredAt) {
        try {
            var field = Order.class.getDeclaredField("deliveredAt");
            field.setAccessible(true);
            field.set(order, deliveredAt);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private void setId(com.shopee.monolith.common.entity.BaseEntity entity, UUID id) {
        try {
            var field = com.shopee.monolith.common.entity.BaseEntity.class.getDeclaredField("id");
            field.setAccessible(true);
            field.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
