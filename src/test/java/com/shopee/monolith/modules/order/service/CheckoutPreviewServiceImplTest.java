package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshot;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.inventory.dto.internal.InventoryStockSummary;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.request.CheckoutPreviewRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutPreviewResponse;
import com.shopee.monolith.modules.order.model.InvalidReasonCode;
import com.shopee.monolith.modules.product.dto.internal.ProductLookupData;
import com.shopee.monolith.modules.product.dto.internal.VariantLookupData;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.dto.response.AddressResponse;
import com.shopee.monolith.modules.user.service.AddressService;
import com.shopee.monolith.modules.user.service.ShopService;
import com.shopee.monolith.modules.voucher.service.VoucherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CheckoutPreviewServiceImplTest {

    @Mock
    private CartService cartService;
    @Mock
    private ProductService productService;
    @Mock
    private InventoryService inventoryService;
    @Mock
    private ShopService shopService;
    @Mock
    private AddressService addressService;
    @Mock
    private ShippingFeeEstimator shippingFeeEstimator;
    @Mock
    private VoucherService voucherService;

    @InjectMocks
    private CheckoutPreviewServiceImpl previewService;

    private final UUID buyerId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();
    private final UUID productId = UUID.randomUUID();
    private final UUID variantId = UUID.randomUUID();
    private final UUID addressId = UUID.randomUUID();

    private AddressResponse address;
    private VariantLookupData variant;
    private ProductLookupData product;
    private ShopLookupData shop;

    @BeforeEach
    void setUp() {
        address = AddressResponse.builder()
                .id(addressId)
                .userId(buyerId)
                .recipientName("Buyer")
                .phone("0901234567")
                .addressLine("123 Main St")
                .wardCode("00001")
                .wardName("Ward 1")
                .districtCode("001")
                .districtName("District 1")
                .provinceCode("01")
                .provinceName("Province 1")
                .isDefault(true)
                .build();

        variant = VariantLookupData.builder()
                .id(variantId).productId(productId).sku("SKU-01").name("Red").price(BigDecimal.valueOf(100)).build();

        product = ProductLookupData.builder()
                .id(productId).shopId(shopId).categoryId(UUID.randomUUID()).name("Shirt").build();

        shop = ShopLookupData.builder().id(shopId).ownerId(UUID.randomUUID()).name("Test Shop").build();
    }

    @Test
    void previewWhenAllValidShouldReturnFullBreakdown() {
        CartSnapshot snapshot = CartSnapshot.builder()
                .userId(buyerId)
                .items(List.of(new CartSnapshotItem(variantId, 2)))
                .version(3L)
                .build();
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(snapshot);
        when(addressService.resolveCheckoutAddress(eq(buyerId), any())).thenReturn(address);
        when(inventoryService.getStockSummariesByVariantIds(List.of(variantId)))
                .thenReturn(Map.of(variantId, new InventoryStockSummary(variantId, 10, 0)));
        when(productService.findActiveVariantLookupDataById(variantId)).thenReturn(Optional.of(variant));
        when(productService.findActiveProductLookupDataById(productId)).thenReturn(Optional.of(product));
        when(shopService.findShopLookupDataByIds(List.of(shopId))).thenReturn(Map.of(shopId, shop));
        when(shippingFeeEstimator.estimateFee(eq(shopId), any(), eq(address))).thenReturn(BigDecimal.valueOf(30000));

        CheckoutPreviewResponse response = previewService.preview(buyerId, new CheckoutPreviewRequest(addressId, null));

        assertTrue(response.allItemsValid());
        assertEquals(1, response.shops().size());
        assertEquals(BigDecimal.valueOf(200), response.totalItemsSubtotal());
        assertEquals(BigDecimal.valueOf(30000), response.totalShippingFee());
        assertEquals(BigDecimal.valueOf(30200), response.grandTotal());
        assertEquals(3L, response.cartVersion());
        assertEquals(addressId, response.addressId());

        var item = response.shops().get(0).items().get(0);
        assertTrue(item.valid());
        assertEquals(BigDecimal.valueOf(200), item.itemTotal());
    }

    @Test
    void previewWhenVariantInactiveShouldMarkItemInvalid() {
        CartSnapshot snapshot = CartSnapshot.builder()
                .userId(buyerId).items(List.of(new CartSnapshotItem(variantId, 1))).version(1L).build();
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(snapshot);
        when(addressService.resolveCheckoutAddress(eq(buyerId), any())).thenReturn(address);
        when(inventoryService.getStockSummariesByVariantIds(any())).thenReturn(Map.of());
        when(productService.findActiveVariantLookupDataById(variantId)).thenReturn(Optional.empty());
        when(shopService.findShopLookupDataByIds(any())).thenReturn(Map.of());

        CheckoutPreviewResponse response = previewService.preview(buyerId, new CheckoutPreviewRequest(null, null));

        assertFalse(response.allItemsValid());
        // Unresolvable items are excluded from shop groups but surfaced as invalidItems
        assertTrue(response.shops().isEmpty());
        assertEquals(1, response.invalidItems().size());
        var invalidItem = response.invalidItems().get(0);
        assertFalse(invalidItem.valid());
        assertEquals(variantId, invalidItem.variantId());
        assertEquals(InvalidReasonCode.VARIANT_INACTIVE, invalidItem.invalidReasonCode());
    }

    @Test
    void previewWhenProductInactiveShouldMarkItemInvalid() {
        CartSnapshot snapshot = CartSnapshot.builder()
                .userId(buyerId).items(List.of(new CartSnapshotItem(variantId, 1))).version(1L).build();
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(snapshot);
        when(addressService.resolveCheckoutAddress(eq(buyerId), any())).thenReturn(address);
        when(inventoryService.getStockSummariesByVariantIds(any())).thenReturn(Map.of());
        when(productService.findActiveVariantLookupDataById(variantId)).thenReturn(Optional.of(variant));
        when(productService.findActiveProductLookupDataById(productId)).thenReturn(Optional.empty());
        when(shopService.findShopLookupDataByIds(any())).thenReturn(Map.of());

        CheckoutPreviewResponse response = previewService.preview(buyerId, new CheckoutPreviewRequest(null, null));

        assertFalse(response.allItemsValid());
        assertEquals(1, response.invalidItems().size());
        var invalidItem = response.invalidItems().get(0);
        assertFalse(invalidItem.valid());
        assertEquals(InvalidReasonCode.PRODUCT_INACTIVE, invalidItem.invalidReasonCode());
        // variant resolved before product check, so client still sees variant details
        assertEquals(variantId, invalidItem.variantId());
        assertEquals(productId, invalidItem.productId());
    }

    @Test
    void previewWhenInsufficientStockShouldMarkItemInvalid() {
        CartSnapshot snapshot = CartSnapshot.builder()
                .userId(buyerId).items(List.of(new CartSnapshotItem(variantId, 5))).version(1L).build();
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(snapshot);
        when(addressService.resolveCheckoutAddress(eq(buyerId), any())).thenReturn(address);
        when(inventoryService.getStockSummariesByVariantIds(List.of(variantId)))
                .thenReturn(Map.of(variantId, new InventoryStockSummary(variantId, 2, 0)));
        when(productService.findActiveVariantLookupDataById(variantId)).thenReturn(Optional.of(variant));
        when(productService.findActiveProductLookupDataById(productId)).thenReturn(Optional.of(product));
        when(shopService.findShopLookupDataByIds(List.of(shopId))).thenReturn(Map.of(shopId, shop));
        when(shippingFeeEstimator.estimateFee(eq(shopId), any(), eq(address))).thenReturn(BigDecimal.valueOf(30000));

        CheckoutPreviewResponse response = previewService.preview(buyerId, new CheckoutPreviewRequest(null, null));

        assertFalse(response.allItemsValid());
        assertEquals(InvalidReasonCode.INSUFFICIENT_STOCK,
                response.shops().get(0).items().get(0).invalidReasonCode());
    }

    @Test
    void previewWhenNoItemsSelectedShouldThrowCartEmpty() {
        CartSnapshot empty = CartSnapshot.builder().userId(buyerId).items(List.of()).version(0L).build();
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(empty);

        AppException ex = assertThrows(AppException.class,
                () -> previewService.preview(buyerId, new CheckoutPreviewRequest(null, null)));
        assertEquals(ErrorCode.CART_EMPTY, ex.getErrorCode());
    }

    @Test
    void previewWhenAddressResolutionFailsShouldThrowAddressInvalid() {
        CartSnapshot snapshot = CartSnapshot.builder()
                .userId(buyerId).items(List.of(new CartSnapshotItem(variantId, 1))).version(1L).build();
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(snapshot);
        when(addressService.resolveCheckoutAddress(eq(buyerId), any()))
                .thenThrow(new AppException(ErrorCode.ADDRESS_NOT_FOUND));

        AppException ex = assertThrows(AppException.class,
                () -> previewService.preview(buyerId, new CheckoutPreviewRequest(null, null)));
        assertEquals(ErrorCode.ADDRESS_INVALID, ex.getErrorCode());
    }

    @Test
    void previewShippingFeePerShopIsFromEstimator() {
        BigDecimal expectedFee = BigDecimal.valueOf(50000);
        CartSnapshot snapshot = CartSnapshot.builder()
                .userId(buyerId).items(List.of(new CartSnapshotItem(variantId, 1))).version(1L).build();
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(snapshot);
        when(addressService.resolveCheckoutAddress(eq(buyerId), any())).thenReturn(address);
        when(inventoryService.getStockSummariesByVariantIds(any()))
                .thenReturn(Map.of(variantId, new InventoryStockSummary(variantId, 10, 0)));
        when(productService.findActiveVariantLookupDataById(variantId)).thenReturn(Optional.of(variant));
        when(productService.findActiveProductLookupDataById(productId)).thenReturn(Optional.of(product));
        when(shopService.findShopLookupDataByIds(any())).thenReturn(Map.of(shopId, shop));
        when(shippingFeeEstimator.estimateFee(any(), any(), any())).thenReturn(expectedFee);

        CheckoutPreviewResponse response = previewService.preview(buyerId, new CheckoutPreviewRequest(null, null));

        assertEquals(expectedFee, response.shops().get(0).shippingFee());
        assertEquals(expectedFee, response.totalShippingFee());
    }

    @Test
    void previewWhenVoucherValidShouldSubtractDiscountFromGrandTotal() {
        CartSnapshot snapshot = CartSnapshot.builder()
                .userId(buyerId).items(List.of(new CartSnapshotItem(variantId, 2))).version(1L).build();
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(snapshot);
        when(addressService.resolveCheckoutAddress(eq(buyerId), any())).thenReturn(address);
        when(inventoryService.getStockSummariesByVariantIds(List.of(variantId)))
                .thenReturn(Map.of(variantId, new InventoryStockSummary(variantId, 10, 0)));
        when(productService.findActiveVariantLookupDataById(variantId)).thenReturn(Optional.of(variant));
        when(productService.findActiveProductLookupDataById(productId)).thenReturn(Optional.of(product));
        when(shopService.findShopLookupDataByIds(List.of(shopId))).thenReturn(Map.of(shopId, shop));
        when(shippingFeeEstimator.estimateFee(eq(shopId), any(), eq(address))).thenReturn(BigDecimal.valueOf(30000));
        when(voucherService.validateVoucher("WELCOME10", BigDecimal.valueOf(200)))
                .thenReturn(com.shopee.monolith.modules.voucher.dto.response.ValidateVoucherResponse.builder()
                        .code("WELCOME10").discountAmount(BigDecimal.valueOf(20)).build());

        CheckoutPreviewResponse response = previewService.preview(buyerId, new CheckoutPreviewRequest(addressId, "WELCOME10"));

        assertEquals(BigDecimal.valueOf(20), response.discountAmount());
        assertEquals(BigDecimal.valueOf(30180), response.grandTotal());
        assertEquals(null, response.voucherError());
    }

    @Test
    void previewWhenVoucherInvalidShouldSurfaceErrorWithoutFailingPreview() {
        CartSnapshot snapshot = CartSnapshot.builder()
                .userId(buyerId).items(List.of(new CartSnapshotItem(variantId, 2))).version(1L).build();
        when(cartService.getSelectedSnapshot(buyerId)).thenReturn(snapshot);
        when(addressService.resolveCheckoutAddress(eq(buyerId), any())).thenReturn(address);
        when(inventoryService.getStockSummariesByVariantIds(List.of(variantId)))
                .thenReturn(Map.of(variantId, new InventoryStockSummary(variantId, 10, 0)));
        when(productService.findActiveVariantLookupDataById(variantId)).thenReturn(Optional.of(variant));
        when(productService.findActiveProductLookupDataById(productId)).thenReturn(Optional.of(product));
        when(shopService.findShopLookupDataByIds(List.of(shopId))).thenReturn(Map.of(shopId, shop));
        when(shippingFeeEstimator.estimateFee(eq(shopId), any(), eq(address))).thenReturn(BigDecimal.valueOf(30000));
        when(voucherService.validateVoucher("BADCODE", BigDecimal.valueOf(200)))
                .thenThrow(new AppException(ErrorCode.VOUCHER_NOT_FOUND));

        CheckoutPreviewResponse response = previewService.preview(buyerId, new CheckoutPreviewRequest(addressId, "BADCODE"));

        assertTrue(response.allItemsValid());
        assertEquals(null, response.discountAmount());
        assertEquals(ErrorCode.VOUCHER_NOT_FOUND.getMessage(), response.voucherError());
        assertEquals(BigDecimal.valueOf(30200), response.grandTotal());
    }
}
