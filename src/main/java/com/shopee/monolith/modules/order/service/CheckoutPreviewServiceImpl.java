package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshot;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.inventory.dto.internal.InventoryStockSummary;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.request.CheckoutPreviewRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutPreviewItemResult;
import com.shopee.monolith.modules.order.dto.response.CheckoutPreviewResponse;
import com.shopee.monolith.modules.order.dto.response.CheckoutPreviewShopGroup;
import com.shopee.monolith.modules.order.model.InvalidReasonCode;
import com.shopee.monolith.modules.product.dto.internal.ProductLookupData;
import com.shopee.monolith.modules.product.dto.internal.VariantLookupData;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.dto.response.AddressResponse;
import com.shopee.monolith.modules.user.service.AddressService;
import com.shopee.monolith.modules.user.service.ShopService;
import com.shopee.monolith.modules.voucher.service.VoucherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CheckoutPreviewServiceImpl implements CheckoutPreviewService {

    private final CartService cartService;
    private final ProductService productService;
    private final InventoryService inventoryService;
    private final ShopService shopService;
    private final AddressService addressService;
    private final ShippingFeeEstimator shippingFeeEstimator;
    private final VoucherService voucherService;

    @Override
    public CheckoutPreviewResponse preview(UUID buyerId, CheckoutPreviewRequest request) {
        CartSnapshot snapshot = cartService.getSelectedSnapshot(buyerId);
        if (snapshot.items().isEmpty()) {
            throw new AppException(ErrorCode.CART_EMPTY);
        }

        AddressResponse address = resolveAddress(buyerId, request.addressId());

        List<UUID> variantIds = snapshot.items().stream().map(CartSnapshotItem::variantId).toList();
        Map<UUID, InventoryStockSummary> stockMap = inventoryService.getStockSummariesByVariantIds(variantIds);

        // Resolve items
        record ResolvedItem(CartSnapshotItem cart, VariantLookupData variant,
                            ProductLookupData product, InvalidReasonCode reason) {}

        List<ResolvedItem> resolved = snapshot.items().stream().map(item -> {
            var variantOpt = productService.findActiveVariantLookupDataById(item.variantId());
            if (variantOpt.isEmpty()) {
                return new ResolvedItem(item, null, null, InvalidReasonCode.VARIANT_INACTIVE);
            }
            VariantLookupData variant = variantOpt.get();
            var productOpt = productService.findActiveProductLookupDataById(variant.productId());
            if (productOpt.isEmpty()) {
                return new ResolvedItem(item, variant, null, InvalidReasonCode.PRODUCT_INACTIVE);
            }
            ProductLookupData product = productOpt.get();
            InventoryStockSummary stock = stockMap.get(item.variantId());
            if (stock == null || stock.availableStock() < item.quantity()) {
                return new ResolvedItem(item, variant, product, InvalidReasonCode.INSUFFICIENT_STOCK);
            }
            return new ResolvedItem(item, variant, product, null);
        }).collect(Collectors.toList());

        // Collect shop IDs for batch name lookup
        List<UUID> shopIds = resolved.stream()
                .filter(r -> r.product() != null)
                .map(r -> r.product().shopId())
                .distinct().toList();
        Map<UUID, ShopLookupData> shopMap = shopService.findShopLookupDataByIds(shopIds);

        // Group by shop
        Map<UUID, List<ResolvedItem>> byShop = resolved.stream()
                .filter(r -> r.product() != null)
                .collect(Collectors.groupingBy(r -> r.product().shopId()));

        // Items whose shop cannot be resolved (variant/product inactive) are surfaced
        // as top-level invalid items so the client can render per-item errors
        List<CheckoutPreviewItemResult> invalidItems = resolved.stream()
                .filter(r -> r.product() == null)
                .map(r -> buildItemResult(r.cart(), r.variant(), null, r.reason()))
                .toList();

        List<CheckoutPreviewShopGroup> shopGroups = new ArrayList<>();

        for (Map.Entry<UUID, List<ResolvedItem>> entry : byShop.entrySet()) {
            UUID shopId = entry.getKey();
            List<ResolvedItem> shopItems = entry.getValue();
            ShopLookupData shop = shopMap.get(shopId);
            String shopName = shop != null ? shop.name() : null;

            List<CheckoutPreviewItemResult> itemResults = shopItems.stream()
                    .map(r -> buildItemResult(r.cart(), r.variant(), r.product(), r.reason()))
                    .toList();

            BigDecimal subtotal = itemResults.stream()
                    .map(CheckoutPreviewItemResult::itemTotal)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<CartSnapshotItem> shopCartItems = shopItems.stream().map(ResolvedItem::cart).toList();
            BigDecimal fee = shippingFeeEstimator.estimateFee(shopId, shopCartItems, address);

            shopGroups.add(CheckoutPreviewShopGroup.builder()
                    .shopId(shopId)
                    .shopName(shopName)
                    .items(itemResults)
                    .itemsSubtotal(subtotal)
                    .shippingFee(fee)
                    .shopTotal(subtotal.add(fee))
                    .build());
        }

        BigDecimal totalSubtotal = shopGroups.stream()
                .map(CheckoutPreviewShopGroup::itemsSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalFee = shopGroups.stream()
                .map(CheckoutPreviewShopGroup::shippingFee)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean allValid = resolved.stream().allMatch(r -> r.reason() == null);

        BigDecimal grandTotal = totalSubtotal.add(totalFee);
        BigDecimal discountAmount = null;
        String voucherError = null;
        if (request.voucherCode() != null && !request.voucherCode().isBlank()) {
            try {
                discountAmount = voucherService.validateVoucher(request.voucherCode(), totalSubtotal).discountAmount();
                grandTotal = grandTotal.subtract(discountAmount);
            } catch (AppException e) {
                voucherError = e.getMessage();
            }
        }

        return CheckoutPreviewResponse.builder()
                .shops(shopGroups)
                .invalidItems(invalidItems)
                .totalItemsSubtotal(totalSubtotal)
                .totalShippingFee(totalFee)
                .grandTotal(grandTotal)
                .allItemsValid(allValid)
                .addressId(address != null ? address.id() : null)
                .cartVersion(snapshot.version())
                .discountAmount(discountAmount)
                .voucherError(voucherError)
                .build();
    }

    private AddressResponse resolveAddress(UUID buyerId, UUID addressId) {
        try {
            return addressService.resolveCheckoutAddress(buyerId, addressId);
        } catch (AppException e) {
            throw new AppException(ErrorCode.ADDRESS_INVALID);
        }
    }

    private CheckoutPreviewItemResult buildItemResult(CartSnapshotItem cart, VariantLookupData variant,
                                                      ProductLookupData product, InvalidReasonCode reason) {
        boolean valid = reason == null;
        BigDecimal price = variant != null ? variant.price() : BigDecimal.ZERO;
        BigDecimal itemTotal = price.multiply(BigDecimal.valueOf(cart.quantity()));
        return CheckoutPreviewItemResult.builder()
                .variantId(cart.variantId())
                .productId(variant != null ? variant.productId() : null)
                .productName(product != null ? product.name() : null)
                .variantName(variant != null ? variant.name() : null)
                .sku(variant != null ? variant.sku() : null)
                .quantity(cart.quantity())
                .unitPrice(price)
                .itemTotal(itemTotal)
                .valid(valid)
                .invalidReasonCode(reason)
                .build();
    }
}
