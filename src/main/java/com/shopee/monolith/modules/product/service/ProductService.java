package com.shopee.monolith.modules.product.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.product.dto.internal.ProductLookupData;
import com.shopee.monolith.modules.product.dto.internal.VariantCartSummaryData;
import com.shopee.monolith.modules.product.dto.internal.VariantLookupData;
import com.shopee.monolith.modules.product.dto.request.CreateProductRequest;
import com.shopee.monolith.modules.product.dto.request.CreateProductVariantRequest;
import com.shopee.monolith.modules.product.dto.request.ProductSortOrder;
import com.shopee.monolith.modules.product.dto.request.UpdateProductRequest;
import com.shopee.monolith.modules.product.dto.request.UpdateProductVariantRequest;
import com.shopee.monolith.modules.product.dto.response.CategoryResponse;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.dto.response.ProductDetailResponse;
import com.shopee.monolith.modules.product.dto.response.ProductResponse;
import com.shopee.monolith.modules.product.dto.response.ProductVariantResponse;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface ProductService {

    // ===================== Category =====================

    List<CategoryResponse> listCategories();

    // ===================== Public read (status=ACTIVE only) =====================

    ProductDetailResponse getProductDetailById(UUID productId);

    PagedResponse<ProductCardResponse> listActiveProducts(int page, int size);

    PagedResponse<ProductCardResponse> listActiveProductsByShop(UUID shopId, int page, int size);

    /** Newest ACTIVE products — deterministic homepage feed from PostgreSQL. */
    PagedResponse<ProductCardResponse> listHomepageProducts(int page, int size);

    /** ACTIVE products under a category subtree, sorted by caller preference. */
    PagedResponse<ProductCardResponse> listActiveProductsByCategory(
            UUID categoryId, ProductSortOrder sort, int page, int size);

    // ===================== Legacy public read (kept for backward compat) =====================

    ProductResponse getProductById(UUID productId);

    PagedResponse<ProductResponse> listProducts(int page, int size);

    PagedResponse<ProductResponse> listProductsByShop(UUID shopId, int page, int size);

    // ===================== Seller write operations =====================

    ProductResponse createProduct(UUID ownerId, CreateProductRequest request);

    ProductResponse updateProduct(UUID ownerId, UUID productId, UpdateProductRequest request);

    ProductVariantResponse createVariant(UUID ownerId, UUID productId, CreateProductVariantRequest request);

    ProductVariantResponse updateVariant(UUID ownerId, UUID productId, UUID variantId, UpdateProductVariantRequest request);

    // ===================== Seller lifecycle =====================

    ProductDetailResponse publishProduct(UUID ownerId, UUID productId);

    ProductDetailResponse unpublishProduct(UUID ownerId, UUID productId);

    void deleteProduct(UUID ownerId, UUID productId);

    PagedResponse<ProductDetailResponse> listSellerProducts(UUID ownerId, int page, int size);

    ProductDetailResponse getProductDetailForSeller(UUID ownerId, UUID productId);

    // ===================== Cross-module internal lookups =====================

    Optional<ProductLookupData> findProductLookupDataById(UUID productId);

    Optional<VariantLookupData> findVariantLookupDataById(UUID variantId);

    Optional<ProductLookupData> findActiveProductLookupDataById(UUID productId);

    Optional<VariantLookupData> findActiveVariantLookupDataById(UUID variantId);

    Optional<ProductLookupData> findActiveProductLookupDataByIdForCheckout(UUID productId);

    Optional<VariantLookupData> findActiveVariantLookupDataByIdForCheckout(UUID variantId);

    /**
     * Batch-loads cart display data (product/variant name, option labels, cover image,
     * available stock, checkout eligibility) for a set of variant IDs.
     * Used by CartModule to enrich cart items; variants whose backing product no longer
     * exists are silently excluded from the result map.
     */
    Map<UUID, VariantCartSummaryData> findVariantCartSummariesByIds(List<UUID> variantIds);

    /**
     * Loads full ProductCardResponse for a given ordered list of product IDs.
     * Non-ACTIVE or non-existent products are silently excluded.
     * Used by SearchModule to revalidate and hydrate ES/pgvector search results.
     */
    List<ProductCardResponse> loadActiveProductCards(List<UUID> productIds);

    /**
     * Loads active product cards for a given ordered list of variant IDs.
     * Used by RecommendationModule to turn cart/order signals into public product cards.
     */
    List<ProductCardResponse> loadActiveProductCardsByVariantIds(List<UUID> variantIds);

    /**
     * Counts a shop's products grouped by listing status name.
     * Used by the seller dashboard read model; cross-module-safe (no entity types exposed).
     */
    Map<String, Long> countShopProductsByStatus(UUID shopId);

    /**
     * Refreshes the product's review rating counters (read model).
     * Called by ReviewModule's after-commit listener; idempotent.
     */
    void refreshRatingSummary(UUID productId, java.math.BigDecimal ratingAvg, long ratingCount);
}
