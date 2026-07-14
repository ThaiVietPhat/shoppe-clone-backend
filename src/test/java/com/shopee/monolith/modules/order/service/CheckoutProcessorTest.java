package com.shopee.monolith.modules.order.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.shopee.monolith.modules.order.entity.CheckoutSession;
import com.shopee.monolith.modules.order.entity.IdempotencyKey;
import com.shopee.monolith.modules.order.model.CheckoutSessionStatus;
import com.shopee.monolith.modules.order.model.IdempotencyStatus;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutProcessorTest {

    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock
    private CheckoutSessionRepository checkoutSessionRepository;
    @Mock
    private OrderRepository orderRepository;
    @Mock
    private OrderItemRepository orderItemRepository;
    @Mock
    private InventoryReservationRepository inventoryReservationRepository;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private ProductService productService;
    @Mock
    private ShippingFeeEstimator shippingFeeEstimator;
    @Mock
    private VoucherService voucherService;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private CheckoutProcessor checkoutProcessor;

    private UUID buyerId;
    private AddressResponse address;
    private String idempotencyKey;
    private String requestHash;
    private String requestBodyHash;
    private UUID keyId;
    private Instant expiresAt;

    @BeforeEach
    void setUp() {
        buyerId = UUID.randomUUID();
        address = AddressResponse.builder()
                .id(UUID.randomUUID())
                .userId(buyerId)
                .recipientName("John Doe")
                .phone("0987654321")
                .addressLine("123 Street")
                .wardCode("W1")
                .wardName("Ward 1")
                .districtCode("D1")
                .districtName("District 1")
                .provinceCode("P1")
                .provinceName("Province 1")
                .isDefault(true)
                .build();
        idempotencyKey = UUID.randomUUID().toString();
        requestHash = "hash123";
        requestBodyHash = "bodyHash123";
        keyId = UUID.randomUUID();
        expiresAt = Instant.now();
    }

    @Test
    void processCheckoutWhenDuplicateSameRequestAndCompletedShouldReturnCachedResponse() throws Exception {
        CheckoutResponse expectedResponse = CheckoutResponse.builder()
                .checkoutSessionId(UUID.randomUUID())
                .orderIds(List.of(UUID.randomUUID()))
                .status("PENDING_PAYMENT")
                .totalAmount(BigDecimal.TEN)
                .expiresAt(Instant.now())
                .build();

        String responseBody = objectMapper.writeValueAsString(expectedResponse);

        IdempotencyKey completedKey = IdempotencyKey.builder()
                .actorId(buyerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .requestBodyHash(requestBodyHash)
                .status(IdempotencyStatus.COMPLETED)
                .responseBody(responseBody)
                .expiresAt(Instant.now().plus(java.time.Duration.ofDays(1)))
                .build();

        when(idempotencyKeyRepository.tryInsert(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(idempotencyKeyRepository.findByKeysForUpdate(buyerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(completedKey));

        CheckoutResponse actualResponse = checkoutProcessor.processCheckout(
                buyerId, address, idempotencyKey, requestHash, requestBodyHash, keyId, expiresAt, List.of(), null, () -> {}
        );

        assertNotNull(actualResponse);
        assertEquals(expectedResponse.checkoutSessionId(), actualResponse.checkoutSessionId());
        assertEquals(expectedResponse.totalAmount(), actualResponse.totalAmount());
    }

    @Test
    void processCheckoutWhenDuplicateLegacyCompletedKeySameRequestShouldReturnCachedResponse() throws Exception {
        CheckoutResponse expectedResponse = CheckoutResponse.builder()
                .checkoutSessionId(UUID.randomUUID())
                .orderIds(List.of(UUID.randomUUID()))
                .status("PENDING_PAYMENT")
                .totalAmount(BigDecimal.TEN)
                .expiresAt(Instant.now())
                .build();

        String responseBody = objectMapper.writeValueAsString(expectedResponse);

        IdempotencyKey completedKey = IdempotencyKey.builder()
                .actorId(buyerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .requestBodyHash(requestHash)
                .status(IdempotencyStatus.COMPLETED)
                .responseBody(responseBody)
                .expiresAt(Instant.now().plus(java.time.Duration.ofDays(1)))
                .build();

        when(idempotencyKeyRepository.tryInsert(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(idempotencyKeyRepository.findByKeysForUpdate(buyerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(completedKey));

        CheckoutResponse actualResponse = checkoutProcessor.processCheckout(
                buyerId, address, idempotencyKey, requestHash, requestBodyHash, keyId, expiresAt, List.of(), null, () -> {}
        );

        assertNotNull(actualResponse);
        assertEquals(expectedResponse.checkoutSessionId(), actualResponse.checkoutSessionId());
        assertEquals(expectedResponse.totalAmount(), actualResponse.totalAmount());
    }

    @Test
    void processCheckoutWhenDuplicateDifferentRequestShouldThrowConflict() {
        IdempotencyKey existingKey = IdempotencyKey.builder()
                .actorId(buyerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash("different_hash")
                .requestBodyHash(requestBodyHash)
                .status(IdempotencyStatus.COMPLETED)
                .expiresAt(Instant.now().plus(java.time.Duration.ofDays(1)))
                .build();

        when(idempotencyKeyRepository.tryInsert(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(idempotencyKeyRepository.findByKeysForUpdate(buyerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(existingKey));

        AppException exception = assertThrows(AppException.class, () ->
                checkoutProcessor.processCheckout(
                        buyerId, address, idempotencyKey, requestHash, requestBodyHash, keyId, expiresAt, List.of(), null, () -> {}
                )
        );
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.getErrorCode());
    }

    @Test
    void processCheckoutWhenDuplicateSameRequestAndProcessingShouldThrowRetryableConflict() {
        IdempotencyKey existingKey = IdempotencyKey.builder()
                .actorId(buyerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash(requestHash)
                .requestBodyHash(requestBodyHash)
                .status(IdempotencyStatus.PROCESSING)
                .expiresAt(Instant.now().plus(java.time.Duration.ofDays(1)))
                .build();

        when(idempotencyKeyRepository.tryInsert(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(idempotencyKeyRepository.findByKeysForUpdate(buyerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(existingKey));

        AppException exception = assertThrows(AppException.class, () ->
                checkoutProcessor.processCheckout(buyerId, address, idempotencyKey, requestHash, requestBodyHash, keyId, expiresAt, List.of(), null, () -> {})
        );
        assertEquals(ErrorCode.IDEMPOTENCY_REQUEST_PROCESSING, exception.getErrorCode());
    }

    @Test
    void processCheckoutWhenValidShouldCreateEntities() {
        UUID variantId = UUID.randomUUID();
        CartSnapshotItem cartItem = new CartSnapshotItem(variantId, 2);
        VariantLookupData variant = VariantLookupData.builder()
                .id(variantId)
                .productId(UUID.randomUUID())
                .sku("SKU-1")
                .name("Var 1")
                .price(BigDecimal.TEN)
                .build();
        ProductLookupData product = ProductLookupData.builder()
                .id(variant.productId())
                .shopId(UUID.randomUUID())
                .name("Prod 1")
                .build();

        when(idempotencyKeyRepository.tryInsert(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(productService.findActiveVariantLookupDataByIdForCheckout(variantId)).thenReturn(Optional.of(variant));
        when(productService.findActiveProductLookupDataByIdForCheckout(variant.productId())).thenReturn(Optional.of(product));
        when(shippingFeeEstimator.estimateFee(any(), any(), any())).thenReturn(BigDecimal.ZERO);

        CheckoutSession session = CheckoutSession.builder()
                .id(UUID.randomUUID())
                .buyerId(buyerId)
                .status(CheckoutSessionStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.TEN)
                .shippingRecipientName(address.recipientName())
                .shippingPhone(address.phone())
                .shippingAddressLine(address.addressLine())
                .shippingWardCode(address.wardCode())
                .shippingWardName(address.wardName())
                .shippingDistrictCode(address.districtCode())
                .shippingDistrictName(address.districtName())
                .shippingProvinceCode(address.provinceCode())
                .shippingProvinceName(address.provinceName())
                .expiresAt(Instant.now())
                .build();
        when(checkoutSessionRepository.save(any())).thenReturn(session);
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CheckoutResponse response = checkoutProcessor.processCheckout(
                buyerId, address, idempotencyKey, requestHash, requestBodyHash, keyId, expiresAt, List.of(cartItem), null, () -> {}
        );

        assertNotNull(response);
        assertEquals(session.getId(), response.checkoutSessionId());
        verify(inventoryService).reserve(any());
        verify(inventoryReservationRepository).saveAll(any());
    }

    @Test
    void processCheckoutWhenVoucherCodePresentShouldReserveAndApplyDiscount() {
        UUID variantId = UUID.randomUUID();
        CartSnapshotItem cartItem = new CartSnapshotItem(variantId, 2);
        VariantLookupData variant = VariantLookupData.builder()
                .id(variantId)
                .productId(UUID.randomUUID())
                .sku("SKU-1")
                .name("Var 1")
                .price(BigDecimal.TEN)
                .build();
        ProductLookupData product = ProductLookupData.builder()
                .id(variant.productId())
                .shopId(UUID.randomUUID())
                .name("Prod 1")
                .build();

        when(idempotencyKeyRepository.tryInsert(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(productService.findActiveVariantLookupDataByIdForCheckout(variantId)).thenReturn(Optional.of(variant));
        when(productService.findActiveProductLookupDataByIdForCheckout(variant.productId())).thenReturn(Optional.of(product));
        when(shippingFeeEstimator.estimateFee(any(), any(), any())).thenReturn(BigDecimal.ZERO);
        when(voucherService.reserveVoucher(eq("WELCOME10"), any(), eq(buyerId), eq(BigDecimal.valueOf(20))))
                .thenReturn(BigDecimal.valueOf(5));

        CheckoutSession session = CheckoutSession.builder()
                .id(UUID.randomUUID())
                .buyerId(buyerId)
                .status(CheckoutSessionStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.TEN)
                .shippingRecipientName(address.recipientName())
                .shippingPhone(address.phone())
                .shippingAddressLine(address.addressLine())
                .shippingWardCode(address.wardCode())
                .shippingWardName(address.wardName())
                .shippingDistrictCode(address.districtCode())
                .shippingDistrictName(address.districtName())
                .shippingProvinceCode(address.provinceCode())
                .shippingProvinceName(address.provinceName())
                .expiresAt(Instant.now())
                .build();
        when(checkoutSessionRepository.save(any())).thenReturn(session);
        when(orderRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        CheckoutResponse response = checkoutProcessor.processCheckout(
                buyerId, address, idempotencyKey, requestHash, requestBodyHash, keyId, expiresAt,
                List.of(cartItem), "WELCOME10", () -> {}
        );

        assertEquals(BigDecimal.valueOf(5), response.discountAmount());
        assertEquals(BigDecimal.valueOf(15), response.totalAmount());
        verify(voucherService).reserveVoucher(eq("WELCOME10"), any(), eq(buyerId), eq(BigDecimal.valueOf(20)));
    }

    @Test
    void processCheckoutWhenVariantBecomesInactiveInsideTransactionShouldThrowVariantNotFound() {
        UUID variantId = UUID.randomUUID();
        CartSnapshotItem cartItem = new CartSnapshotItem(variantId, 1);

        when(idempotencyKeyRepository.tryInsert(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(1);
        when(productService.findActiveVariantLookupDataByIdForCheckout(variantId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class, () -> checkoutProcessor.processCheckout(
                buyerId, address, idempotencyKey, requestHash, requestBodyHash, keyId, expiresAt, List.of(cartItem), null, () -> {}
        ));

        assertEquals(ErrorCode.VARIANT_NOT_FOUND, exception.getErrorCode());
    }
}
