package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshot;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.order.dto.request.CheckoutRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.modules.order.entity.IdempotencyKey;
import com.shopee.monolith.modules.order.model.IdempotencyStatus;
import com.shopee.monolith.modules.order.repository.IdempotencyKeyRepository;
import com.shopee.monolith.modules.user.dto.response.AddressResponse;
import com.shopee.monolith.modules.user.service.AddressService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock
    private CheckoutProcessor checkoutProcessor;
    @Mock
    private CartService cartService;
    @Mock
    private IdempotencyKeyRepository idempotencyKeyRepository;
    @Mock
    private AddressService addressService;
    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private OrderServiceImpl orderService;

    private UUID buyerId;
    private CheckoutRequest request;
    private String idempotencyKey;
    private AddressResponse address;

    @BeforeEach
    void setUp() {
        buyerId = UUID.randomUUID();
        UUID addressId = UUID.randomUUID();
        request = CheckoutRequest.builder().addressId(addressId).build();
        idempotencyKey = UUID.randomUUID().toString();

        address = AddressResponse.builder()
                .id(addressId)
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

        lenient().when(idempotencyKeyRepository.findByActorIdAndOperationAndIdempotencyKey(any(), any(), any()))
                .thenReturn(Optional.empty());
    }

    @Test
    void checkoutWhenMissingIdempotencyKeyShouldThrowException() {
        AppException exception = assertThrows(AppException.class, () ->
                orderService.checkout(buyerId, request, "")
        );
        assertEquals(ErrorCode.IDEMPOTENCY_KEY_MISSING, exception.getErrorCode());
    }

    @Test
    void checkoutWhenCartIsEmptyShouldThrowException() {
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(new CartSnapshot(buyerId, Collections.emptyList(), 1L));

        AppException exception = assertThrows(AppException.class, () ->
                orderService.checkout(buyerId, request, idempotencyKey)
        );
        assertEquals(ErrorCode.CART_SELECTED_EMPTY, exception.getErrorCode());
    }

    @Test
    void checkoutWhenValidShouldCallProcessor() {
        when(addressService.resolveCheckoutAddress(buyerId, request.addressId())).thenReturn(address);
        
        UUID variantId = UUID.randomUUID();
        CartSnapshotItem item = new CartSnapshotItem(variantId, 2);
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(new CartSnapshot(buyerId, List.of(item), 1L));

        CheckoutResponse expectedResponse = CheckoutResponse.builder()
                .checkoutSessionId(UUID.randomUUID())
                .orderIds(List.of(UUID.randomUUID()))
                .status("PENDING_PAYMENT")
                .totalAmount(BigDecimal.TEN)
                .expiresAt(Instant.now())
                .build();

        when(checkoutProcessor.processCheckout(any(), any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(expectedResponse);

        CheckoutResponse response = orderService.checkout(buyerId, request, idempotencyKey);

        assertNotNull(response);
        assertEquals(expectedResponse.checkoutSessionId(), response.checkoutSessionId());
        verify(checkoutProcessor).processCheckout(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkoutWhenCompletedKeyButCurrentCartDriftedShouldThrowConflict() throws Exception {
        IdempotencyKey completedKey = IdempotencyKey.builder()
                .actorId(buyerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash("previous-cart-hash")
                .requestBodyHash("previous-body-hash")
                .status(IdempotencyStatus.COMPLETED)
                .responseBody("{}")
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        when(idempotencyKeyRepository.findByActorIdAndOperationAndIdempotencyKey(buyerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(completedKey));

        AppException exception = assertThrows(AppException.class, () ->
                orderService.checkout(buyerId, request, idempotencyKey)
        );

        assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.getErrorCode());
        verify(checkoutProcessor, never()).processCheckout(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void checkoutWhenLegacyCompletedKeyAndCartClearedShouldReturnCachedResponse() throws Exception {
        CheckoutResponse cachedResponse = CheckoutResponse.builder()
                .checkoutSessionId(UUID.randomUUID())
                .orderIds(List.of(UUID.randomUUID()))
                .status("PENDING_PAYMENT")
                .totalAmount(BigDecimal.TEN)
                .expiresAt(Instant.now())
                .build();
        String legacyFullRequestHash = "legacy-full-request-hash";
        IdempotencyKey completedKey = IdempotencyKey.builder()
                .actorId(buyerId)
                .operation("CHECKOUT")
                .idempotencyKey(idempotencyKey)
                .requestHash(legacyFullRequestHash)
                .requestBodyHash(legacyFullRequestHash)
                .status(IdempotencyStatus.COMPLETED)
                .responseBody("{}")
                .expiresAt(Instant.now().plusSeconds(60))
                .build();

        when(idempotencyKeyRepository.findByActorIdAndOperationAndIdempotencyKey(buyerId, "CHECKOUT", idempotencyKey))
                .thenReturn(Optional.of(completedKey));
        when(objectMapper.readValue("{}", CheckoutResponse.class)).thenReturn(cachedResponse);

        CheckoutResponse response = orderService.checkout(buyerId, request, idempotencyKey);

        assertEquals(cachedResponse, response);
        verify(checkoutProcessor, never()).processCheckout(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
