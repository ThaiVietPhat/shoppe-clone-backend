package com.shopee.monolith.modules.product.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.media.dto.response.ProductMediaSummary;
import com.shopee.monolith.modules.media.service.MediaService;
import com.shopee.monolith.modules.product.dto.internal.ProductLookupData;
import com.shopee.monolith.modules.product.dto.internal.ProductStockSummaryDto;
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
import com.shopee.monolith.modules.product.dto.response.ProductEligibilityIssue;
import com.shopee.monolith.modules.product.dto.response.ProductResponse;
import com.shopee.monolith.modules.product.dto.response.ProductVariantDetailResponse;
import com.shopee.monolith.modules.product.dto.response.ProductVariantResponse;
import com.shopee.monolith.modules.product.dto.response.ShopSummaryDto;
import com.shopee.monolith.modules.product.entity.Category;
import com.shopee.monolith.modules.product.entity.Product;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.entity.ProductVariant;
import com.shopee.monolith.modules.product.event.ProductCatalogSnapshotEvent;
import com.shopee.monolith.modules.product.event.ProductCreatedEvent;
import com.shopee.monolith.modules.product.event.ProductListingStatusChangedEvent;
import com.shopee.monolith.modules.product.event.ProductUpdatedEvent;
import com.shopee.monolith.modules.product.mapper.ProductMapper;
import com.shopee.monolith.modules.product.repository.CategoryRepository;
import com.shopee.monolith.modules.product.repository.ProductRepository;
import com.shopee.monolith.modules.product.repository.ProductVariantRepository;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.service.ShopService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductServiceImpl implements ProductService {

    private final ProductRepository productRepository;
    private final ProductVariantRepository productVariantRepository;
    private final CategoryRepository categoryRepository;
    private final ProductMapper productMapper;
    private final ShopService shopService;
    private final ProductStockSummaryProvider stockSummaryProvider;
    private final MediaService mediaService;
    private final ApplicationEventPublisher eventPublisher;

    // ===================== Validators =====================

    private ShopLookupData validateShopOwner(UUID ownerId, UUID shopId) {
        ShopLookupData shop = shopService.findShopLookupDataById(shopId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
        if (!shop.ownerId().equals(ownerId)) {
            throw new AppException(ErrorCode.SHOP_OWNER_REQUIRED);
        }
        return shop;
    }

    private void validateCategory(UUID categoryId) {
        if (categoryId != null && !categoryRepository.existsById(categoryId)) {
            throw new AppException(ErrorCode.CATEGORY_NOT_FOUND);
        }
    }

    private String resolveCategory(UUID categoryId) {
        if (categoryId == null) {
            return null;
        }
        return categoryRepository.findById(categoryId)
                .map(c -> c.getPath() != null ? c.getPath() : c.getName())
                .orElse(null);
    }

    private static Pageable buildPageable(int page, int size) {
        return PageRequest.of(page, Math.min(size, 100), Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private static Pageable buildPageableForSort(int page, int size, ProductSortOrder sort) {
        Sort springSort = switch (sort) {
            case PRICE_ASC -> Sort.by(Sort.Direction.ASC, "minPrice");
            case PRICE_DESC -> Sort.by(Sort.Direction.DESC, "minPrice");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        return PageRequest.of(page, Math.min(size, 100), springSort);
    }

    // ===================== Category =====================

    @Override
    public List<CategoryResponse> listCategories() {
        return categoryRepository.findAll().stream()
                .map(productMapper::toResponse)
                .toList();
    }

    // ===================== Public read (ACTIVE only) =====================

    @Override
    public ProductDetailResponse getProductDetailById(UUID productId) {
        Product product = productRepository.findByIdAndStatus(productId, ProductStatus.ACTIVE)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));
        return buildProductDetail(product, false);
    }

    @Override
    public PagedResponse<ProductCardResponse> listActiveProducts(int page, int size) {
        if (page < 0 || size < 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Page<Product> productPage = productRepository.findAllByStatus(ProductStatus.ACTIVE, buildPageable(page, size));
        return toCardPagedResponse(productPage);
    }

    @Override
    public PagedResponse<ProductCardResponse> listActiveProductsByShop(UUID shopId, int page, int size) {
        if (page < 0 || size < 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Page<Product> productPage = productRepository.findAllByShopIdAndStatus(shopId, ProductStatus.ACTIVE, buildPageable(page, size));
        return toCardPagedResponse(productPage);
    }

    @Override
    public PagedResponse<ProductCardResponse> listHomepageProducts(int page, int size) {
        if (page < 0 || size < 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Page<Product> productPage = productRepository.findAllByStatus(
                ProductStatus.ACTIVE, buildPageable(page, size));
        return toCardPagedResponse(productPage);
    }

    @Override
    public PagedResponse<ProductCardResponse> listActiveProductsByCategory(
            UUID categoryId, ProductSortOrder sort, int page, int size) {
        if (page < 0 || size < 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Category root = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new AppException(ErrorCode.CATEGORY_NOT_FOUND));

        // Collect root + all descendants via materialized path prefix
        String pathPrefix = root.getPath() + "/";
        List<UUID> subtreeCategoryIds = categoryRepository.findAllByPathStartingWith(pathPrefix)
                .stream().map(Category::getId).collect(Collectors.toList());
        subtreeCategoryIds.add(root.getId());

        Pageable pageable = buildPageableForSort(page, size, sort != null ? sort : ProductSortOrder.NEWEST);
        Page<Product> productPage = productRepository.findAllByStatusAndCategoryIdIn(
                ProductStatus.ACTIVE, subtreeCategoryIds, pageable);
        return toCardPagedResponse(productPage);
    }

    // ===================== Legacy public read (backward compat) =====================

    @Override
    public ProductResponse getProductById(UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));
        List<ProductVariantResponse> variants = productVariantRepository.findAllByProductId(productId).stream()
                .map(productMapper::toResponse)
                .toList();
        return productMapper.toResponse(product, variants);
    }

    @Override
    public PagedResponse<ProductResponse> listProducts(int page, int size) {
        if (page < 0 || size < 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Page<Product> productPage = productRepository.findAll(buildPageable(page, size));
        return toPagedResponse(productPage);
    }

    @Override
    public PagedResponse<ProductResponse> listProductsByShop(UUID shopId, int page, int size) {
        if (page < 0 || size < 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        Page<Product> productPage = productRepository.findAllByShopId(shopId, buildPageable(page, size));
        return toPagedResponse(productPage);
    }

    // ===================== Seller create/update =====================

    @Override
    @Transactional
    public ProductResponse createProduct(UUID ownerId, CreateProductRequest request) {
        validateShopOwner(ownerId, request.shopId());
        validateCategory(request.categoryId());

        Product product = Product.builder()
                .shopId(request.shopId())
                .categoryId(request.categoryId())
                .name(request.name())
                .description(request.description())
                .status(ProductStatus.DRAFT)
                .brand(request.brand())
                .sellerSku(request.sellerSku())
                .attributes(request.attributes())
                .build();

        product = productRepository.save(product);

        // Attach media if provided
        if (request.mediaIds() != null) {
            replaceProductMedia(ownerId, request.shopId(), product.getId(), request.mediaIds());
        }

        eventPublisher.publishEvent(new ProductCreatedEvent(product.getId(), product.getShopId()));
        publishCatalogSnapshot(product, List.of());

        return productMapper.toResponse(product, List.of());
    }

    @Override
    @Transactional
    public ProductResponse updateProduct(UUID ownerId, UUID productId, UpdateProductRequest request) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getStatus() == ProductStatus.DELETED) {
            throw new AppException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        validateShopOwner(ownerId, product.getShopId());
        validateCategory(request.categoryId());

        product.update(request.categoryId(), request.name(), request.description(),
                request.brand(), request.sellerSku(), request.attributes());
        product = productRepository.save(product);

        // Reattach media if provided
        if (request.mediaIds() != null) {
            replaceProductMedia(ownerId, product.getShopId(), product.getId(), request.mediaIds());
        }

        List<ProductVariant> variants = productVariantRepository.findAllByProductId(productId);
        eventPublisher.publishEvent(new ProductUpdatedEvent(product.getId(), product.getShopId()));
        publishCatalogSnapshot(product, variants);

        List<ProductVariantResponse> variantResponses = variants.stream()
                .map(productMapper::toResponse)
                .toList();
        return productMapper.toResponse(product, variantResponses);
    }

    // ===================== Seller variant =====================

    @Override
    @Transactional
    public ProductVariantResponse createVariant(UUID ownerId, UUID productId, CreateProductVariantRequest request) {
        if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(ErrorCode.INVALID_PRODUCT_PRICE);
        }

        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getStatus() == ProductStatus.DELETED) {
            throw new AppException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        validateShopOwner(ownerId, product.getShopId());

        if (productVariantRepository.existsBySku(request.sku())) {
            throw new AppException(ErrorCode.SKU_ALREADY_EXISTS);
        }

        ProductVariant variant = ProductVariant.builder()
                .productId(productId)
                .sku(request.sku())
                .name(request.name())
                .price(request.price())
                .optionLabels(request.optionLabels())
                .active(request.isActiveOrDefault())
                .build();

        try {
            variant = productVariantRepository.saveAndFlush(variant);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.SKU_ALREADY_EXISTS);
        }
        refreshProductAfterVariantMutation(product);
        return productMapper.toResponse(variant);
    }

    @Override
    @Transactional
    public ProductVariantResponse updateVariant(UUID ownerId, UUID productId, UUID variantId, UpdateProductVariantRequest request) {
        if (request.price() == null || request.price().compareTo(BigDecimal.ZERO) < 0) {
            throw new AppException(ErrorCode.INVALID_PRODUCT_PRICE);
        }

        ProductVariant variant = productVariantRepository.findById(variantId)
                .orElseThrow(() -> new AppException(ErrorCode.VARIANT_NOT_FOUND));

        if (!variant.getProductId().equals(productId)) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getStatus() == ProductStatus.DELETED) {
            throw new AppException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        validateShopOwner(ownerId, product.getShopId());

        Optional<ProductVariant> existingSku = productVariantRepository.findBySku(request.sku());
        if (existingSku.isPresent() && !existingSku.get().getId().equals(variantId)) {
            throw new AppException(ErrorCode.SKU_ALREADY_EXISTS);
        }

        boolean activeFlag = request.active() == null ? variant.isActive() : request.active();
        variant.update(request.sku(), request.name(), request.price(), request.optionLabels(), activeFlag);

        try {
            variant = productVariantRepository.saveAndFlush(variant);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.SKU_ALREADY_EXISTS);
        }
        refreshProductAfterVariantMutation(product);
        return productMapper.toResponse(variant);
    }

    // ===================== Seller lifecycle =====================

    @Override
    @Transactional
    public ProductDetailResponse publishProduct(UUID ownerId, UUID productId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        validateShopOwner(ownerId, product.getShopId());

        if (!product.isPublishable()) {
            throw new AppException(ErrorCode.PRODUCT_CANNOT_BE_PUBLISHED);
        }

        // Must have at least one active variant with price > 0
        long eligibleVariants = productVariantRepository.countActiveVariantsWithPriceAbove(productId, BigDecimal.ZERO);
        if (eligibleVariants == 0) {
            throw new AppException(ErrorCode.PRODUCT_HAS_NO_ACTIVE_VARIANT);
        }

        // Recompute price range from active variants
        recomputeProductPriceRange(product);
        product.publish();
        product = productRepository.save(product);

        List<ProductVariant> variants = productVariantRepository.findAllByProductId(productId);

        eventPublisher.publishEvent(new ProductListingStatusChangedEvent(
                product.getId(), product.getShopId(), ProductStatus.ACTIVE));
        publishCatalogSnapshot(product, variants);

        return buildProductDetail(product, true);
    }

    @Override
    @Transactional
    public ProductDetailResponse unpublishProduct(UUID ownerId, UUID productId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        validateShopOwner(ownerId, product.getShopId());
        product.unpublish();
        product = productRepository.save(product);

        List<ProductVariant> variants = productVariantRepository.findAllByProductId(productId);

        eventPublisher.publishEvent(new ProductListingStatusChangedEvent(
                product.getId(), product.getShopId(), ProductStatus.INACTIVE));
        publishCatalogSnapshot(product, variants);

        return buildProductDetail(product, true);
    }

    @Override
    @Transactional
    public void deleteProduct(UUID ownerId, UUID productId) {
        Product product = productRepository.findByIdForUpdate(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        validateShopOwner(ownerId, product.getShopId());

        if (product.getStatus() == ProductStatus.DELETED) {
            return; // Idempotent
        }

        product.softDelete();
        productRepository.save(product);

        eventPublisher.publishEvent(new ProductListingStatusChangedEvent(
                product.getId(), product.getShopId(), ProductStatus.DELETED));
        List<ProductVariant> variants = productVariantRepository.findAllByProductId(productId);
        publishCatalogSnapshot(product, variants);
    }

    @Override
    public PagedResponse<ProductDetailResponse> listSellerProducts(UUID ownerId, ProductStatus status, int page, int size) {
        if (page < 0 || size < 1) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        ShopLookupData shop = shopService.findShopLookupDataByOwnerId(ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));

        Page<Product> productPage = status != null
                ? productRepository.findAllByShopIdAndStatus(shop.id(), status, buildPageable(page, size))
                : productRepository.findAllByShopIdAndStatusNot(shop.id(), ProductStatus.DELETED, buildPageable(page, size));

        List<ProductDetailResponse> content = buildProductDetails(productPage.getContent());

        return PagedResponse.from(productPage, content);
    }

    @Override
    public ProductDetailResponse getProductDetailForSeller(UUID ownerId, UUID productId) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        if (product.getStatus() == ProductStatus.DELETED) {
            throw new AppException(ErrorCode.PRODUCT_NOT_FOUND);
        }

        validateShopOwner(ownerId, product.getShopId());
        return buildProductDetail(product, true);
    }

    // ===================== Cross-module internal lookups =====================

    @Override
    public Optional<ProductLookupData> findProductLookupDataById(UUID productId) {
        return productRepository.findById(productId)
                .map(productMapper::toLookupData);
    }

    @Override
    public Optional<VariantLookupData> findVariantLookupDataById(UUID variantId) {
        return productVariantRepository.findById(variantId)
                .map(productMapper::toLookupData);
    }

    @Override
    public Optional<ProductLookupData> findActiveProductLookupDataById(UUID productId) {
        return productRepository.findByIdAndStatus(productId, ProductStatus.ACTIVE)
                .map(productMapper::toLookupData);
    }

    @Override
    public Optional<VariantLookupData> findActiveVariantLookupDataById(UUID variantId) {
        return productVariantRepository.findActiveByIdAndProductStatus(variantId, ProductStatus.ACTIVE)
                .map(productMapper::toLookupData);
    }

    @Override
    public Optional<ProductLookupData> findActiveProductLookupDataByIdForCheckout(UUID productId) {
        return productRepository.findByIdAndStatusForUpdate(productId, ProductStatus.ACTIVE)
                .map(productMapper::toLookupData);
    }

    @Override
    public Optional<VariantLookupData> findActiveVariantLookupDataByIdForCheckout(UUID variantId) {
        return productVariantRepository.findActiveByIdAndProductStatusForUpdate(variantId, ProductStatus.ACTIVE)
                .map(productMapper::toLookupData);
    }

    @Override
    public Map<UUID, VariantCartSummaryData> findVariantCartSummariesByIds(List<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        List<ProductVariant> variants = productVariantRepository.findAllByIdIn(variantIds);
        if (variants.isEmpty()) {
            return Map.of();
        }

        List<UUID> productIds = variants.stream().map(ProductVariant::getProductId).distinct().toList();
        Map<UUID, Product> productMap = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, p -> p));

        Map<UUID, ProductStockSummaryDto> stockMap = stockSummaryProvider.getStockSummariesByVariantIds(
                variants.stream().map(ProductVariant::getId).toList());
        Map<UUID, List<ProductMediaSummary>> mediaMap = mediaService.listProductMediaByProductIds(productIds);
        Map<UUID, ShopLookupData> shopMap = shopService.findShopLookupDataByIds(productMap.values().stream()
                .map(Product::getShopId)
                .distinct()
                .toList());

        Map<UUID, VariantCartSummaryData> result = new java.util.LinkedHashMap<>();
        for (ProductVariant variant : variants) {
            Product product = productMap.get(variant.getProductId());
            if (product == null) {
                continue;
            }
            ProductStockSummaryDto stock = stockMap.getOrDefault(variant.getId(),
                    ProductStockSummaryDto.empty(variant.getId()));
            boolean checkoutEligible = product.getStatus() == ProductStatus.ACTIVE
                    && variant.isActive()
                    && variant.getPrice().compareTo(BigDecimal.ZERO) > 0
                    && stock.availableStock() > 0;
            String coverImageUrl = mediaMap.getOrDefault(product.getId(), List.of()).stream()
                    .filter(ProductMediaSummary::cover)
                    .findFirst()
                    .map(ProductMediaSummary::publicUrl)
                    .orElse(null);
            ShopLookupData shop = shopMap.get(product.getShopId());

            result.put(variant.getId(), VariantCartSummaryData.builder()
                    .variantId(variant.getId())
                    .productId(product.getId())
                    .shopId(product.getShopId())
                    .shopName(shop != null ? shop.name() : null)
                    .productName(product.getName())
                    .variantName(variant.getName())
                    .optionLabels(variant.getOptionLabels())
                    .sku(variant.getSku())
                    .price(variant.getPrice())
                    .coverImageUrl(coverImageUrl)
                    .availableStock(stock.availableStock())
                    .checkoutEligible(checkoutEligible)
                    .build());
        }
        return result;
    }

    // ===================== Private builders =====================

    private ProductDetailResponse buildProductDetail(Product product, boolean includeInactiveVariants) {
        List<ProductVariant> variants = includeInactiveVariants
                ? productVariantRepository.findAllByProductId(product.getId())
                : productVariantRepository.findAllByProductIdAndActive(product.getId(), true);

        List<UUID> variantIds = variants.stream().map(ProductVariant::getId).toList();
        Map<UUID, ProductStockSummaryDto> stockMap = stockSummaryProvider.getStockSummariesByVariantIds(variantIds);

        List<ProductMediaSummary> media = mediaService.listProductMedia(product.getId());
        ShopLookupData shop = shopService.findShopLookupDataById(product.getShopId()).orElse(null);
        String categoryPath = resolveCategory(product.getCategoryId());

        return buildProductDetail(product, variants, media, stockMap, shop, categoryPath);
    }

    private List<ProductDetailResponse> buildProductDetails(List<Product> products) {
        if (products.isEmpty()) {
            return List.of();
        }
        List<UUID> productIds = products.stream().map(Product::getId).toList();
        Map<UUID, List<ProductVariant>> variantsByProductId = productVariantRepository.findAllByProductIdIn(productIds)
                .stream()
                .collect(Collectors.groupingBy(ProductVariant::getProductId));
        List<UUID> variantIds = variantsByProductId.values().stream()
                .flatMap(List::stream)
                .map(ProductVariant::getId)
                .toList();
        Map<UUID, ProductStockSummaryDto> stockMap = stockSummaryProvider.getStockSummariesByVariantIds(variantIds);
        Map<UUID, List<ProductMediaSummary>> mediaMap = mediaService.listProductMediaByProductIds(productIds);
        Map<UUID, ShopLookupData> shopMap = shopService.findShopLookupDataByIds(products.stream()
                .map(Product::getShopId)
                .distinct()
                .toList());
        Map<UUID, String> categoryPathMap = resolveCategoryPaths(products.stream()
                .map(Product::getCategoryId)
                .distinct()
                .toList());

        return products.stream()
                .map(product -> buildProductDetail(
                        product,
                        variantsByProductId.getOrDefault(product.getId(), List.of()),
                        mediaMap.getOrDefault(product.getId(), List.of()),
                        stockMap,
                        shopMap.get(product.getShopId()),
                        categoryPathMap.get(product.getCategoryId())))
                .toList();
    }

    private ProductDetailResponse buildProductDetail(
            Product product,
            List<ProductVariant> variants,
            List<ProductMediaSummary> media,
            Map<UUID, ProductStockSummaryDto> stockMap,
            ShopLookupData shop,
            String categoryPath) {
        boolean hasCover = media.stream().anyMatch(ProductMediaSummary::cover);
        ProductMediaSummary coverMedia = media.stream().filter(ProductMediaSummary::cover).findFirst().orElse(null);

        int totalStock = variants.stream()
                .filter(ProductVariant::isActive)
                .filter(v -> v.getPrice().compareTo(BigDecimal.ZERO) > 0)
                .map(v -> stockMap.getOrDefault(v.getId(), ProductStockSummaryDto.empty(v.getId())))
                .mapToInt(ProductStockSummaryDto::availableStock)
                .sum();

        List<ProductVariantDetailResponse> variantDetails = variants.stream()
                .map(v -> {
                    ProductStockSummaryDto stock = stockMap.getOrDefault(v.getId(),
                            ProductStockSummaryDto.empty(v.getId()));
                    boolean checkoutEligible = product.getStatus() == ProductStatus.ACTIVE
                            && v.isActive()
                            && v.getPrice().compareTo(BigDecimal.ZERO) > 0
                            && stock.availableStock() > 0;
                    return ProductVariantDetailResponse.builder()
                            .id(v.getId())
                            .productId(v.getProductId())
                            .sku(v.getSku())
                            .name(v.getName())
                            .price(v.getPrice())
                            .optionLabels(v.getOptionLabels())
                            .active(v.isActive())
                            .availableStock(stock.availableStock())
                            .checkoutEligible(checkoutEligible)
                            .coverMedia(coverMedia)
                            .createdAt(v.getCreatedAt())
                            .updatedAt(v.getUpdatedAt())
                            .build();
                })
                .toList();

        List<ProductEligibilityIssue> eligibilityIssues = buildEligibilityIssues(product, variants, stockMap);

        ShopSummaryDto shopSummary = shop != null
                ? ShopSummaryDto.builder().id(shop.id()).name(shop.name()).rating(shop.rating()).build()
                : null;

        return ProductDetailResponse.builder()
                .id(product.getId())
                .shopId(product.getShopId())
                .status(product.getStatus())
                .name(product.getName())
                .description(product.getDescription())
                .brand(product.getBrand())
                .sellerSku(product.getSellerSku())
                .categoryId(product.getCategoryId())
                .categoryPath(categoryPath)
                .attributes(product.getAttributes())
                .minPrice(product.getMinPrice())
                .maxPrice(product.getMaxPrice())
                .hasCover(hasCover)
                .media(media)
                .variants(variantDetails)
                .eligibilityIssues(eligibilityIssues)
                .shop(shopSummary)
                .totalAvailableStock(totalStock)
                .createdAt(product.getCreatedAt())
                .updatedAt(product.getUpdatedAt())
                .build();
    }

    private List<ProductEligibilityIssue> buildEligibilityIssues(
            Product product,
            List<ProductVariant> variants,
            Map<UUID, ProductStockSummaryDto> stockMap) {
        List<ProductEligibilityIssue> issues = new java.util.ArrayList<>();
        if (product.getStatus() != ProductStatus.ACTIVE) {
            issues.add(ProductEligibilityIssue.PRODUCT_NOT_ACTIVE);
        }
        List<ProductVariant> activeVariants = variants.stream()
                .filter(ProductVariant::isActive)
                .toList();
        if (activeVariants.isEmpty()) {
            issues.add(ProductEligibilityIssue.NO_ACTIVE_VARIANT);
            issues.add(ProductEligibilityIssue.NO_POSITIVE_PRICE);
            issues.add(ProductEligibilityIssue.NO_STOCK);
            return issues;
        }
        List<ProductVariant> positivePriceVariants = activeVariants.stream()
                .filter(v -> v.getPrice().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (positivePriceVariants.isEmpty()) {
            issues.add(ProductEligibilityIssue.NO_POSITIVE_PRICE);
            issues.add(ProductEligibilityIssue.NO_STOCK);
            return issues;
        }
        boolean hasStock = positivePriceVariants.stream()
                .anyMatch(v -> stockMap.getOrDefault(v.getId(),
                        ProductStockSummaryDto.empty(v.getId())).availableStock() > 0);
        if (!hasStock) {
            issues.add(ProductEligibilityIssue.NO_STOCK);
        }
        return issues;
    }

    private void recomputeProductPriceRange(Product product) {
        BigDecimal min = productVariantRepository.findMinPriceByProductId(product.getId()).orElse(null);
        BigDecimal max = productVariantRepository.findMaxPriceByProductId(product.getId()).orElse(null);
        product.recomputePriceRange(min, max);
    }

    private void refreshProductAfterVariantMutation(Product product) {
        recomputeProductPriceRange(product);
        boolean unpublished = false;
        if (product.getStatus() == ProductStatus.ACTIVE
                && productVariantRepository.countActiveVariantsWithPriceAbove(product.getId(), BigDecimal.ZERO) == 0) {
            product.unpublish();
            unpublished = true;
        }
        Product savedProduct = productRepository.save(product);
        List<ProductVariant> variants = productVariantRepository.findAllByProductId(savedProduct.getId());
        if (unpublished) {
            eventPublisher.publishEvent(new ProductListingStatusChangedEvent(
                    savedProduct.getId(), savedProduct.getShopId(), ProductStatus.INACTIVE));
        } else {
            eventPublisher.publishEvent(new ProductUpdatedEvent(savedProduct.getId(), savedProduct.getShopId()));
        }
        publishCatalogSnapshot(savedProduct, variants);
    }

    private void replaceProductMedia(UUID ownerId, UUID shopId, UUID productId, List<UUID> mediaIds) {
        mediaService.replaceProductMedia(ownerId, shopId, productId, mediaIds);
    }

    private void publishCatalogSnapshot(Product product, List<ProductVariant> variants) {
        List<ProductMediaSummary> media = mediaService.listProductMedia(product.getId());
        ProductMediaSummary cover = media.stream().filter(ProductMediaSummary::cover).findFirst().orElse(null);
        List<UUID> variantIds = variants.stream().map(ProductVariant::getId).toList();
        Map<UUID, ProductStockSummaryDto> stockMap = stockSummaryProvider.getStockSummariesByVariantIds(variantIds);
        List<ProductEligibilityIssue> eligibilityIssues = buildEligibilityIssues(product, variants, stockMap);
        ShopLookupData shop = shopService.findShopLookupDataById(product.getShopId()).orElse(null);

        List<ProductCatalogSnapshotEvent.VariantSnapshot> variantSnapshots = variants.stream()
                .map(v -> new ProductCatalogSnapshotEvent.VariantSnapshot(
                        v.getId(), v.getSku(), v.getPrice(), v.getOptionLabels(), v.isActive()))
                .toList();

        publishAfterCommit(new ProductCatalogSnapshotEvent(
                product.getId(),
                product.getShopId(),
                product.getStatus(),
                product.getName(),
                product.getDescription(),
                resolveCategory(product.getCategoryId()),
                product.getBrand(),
                product.getSellerSku(),
                product.getAttributes(),
                product.getMinPrice(),
                product.getMaxPrice(),
                cover != null ? cover.publicUrl() : null,
                cover != null ? cover.mediaId() : null,
                cover != null ? cover.objectKey() : null,
                cover != null ? cover.contentType() : null,
                shop != null ? shop.name() : null,
                shop != null ? shop.rating() : null,
                eligibilityIssues.isEmpty(),
                eligibilityIssues,
                variantSnapshots
        ));
    }

    private void publishAfterCommit(Object event) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            eventPublisher.publishEvent(event);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                eventPublisher.publishEvent(event);
            }
        });
    }

    private PagedResponse<ProductResponse> toPagedResponse(Page<Product> productPage) {
        List<Product> products = productPage.getContent();
        if (products.isEmpty()) {
            return PagedResponse.from(productPage, Collections.emptyList());
        }

        List<UUID> productIds = products.stream().map(Product::getId).toList();
        List<ProductVariant> allVariants = productVariantRepository.findAllByProductIdIn(productIds);
        Map<UUID, List<ProductVariantResponse>> variantsByProductId = allVariants.stream()
                .map(productMapper::toResponse)
                .collect(Collectors.groupingBy(ProductVariantResponse::productId));

        List<ProductResponse> productResponses = products.stream()
                .map(p -> productMapper.toResponse(p,
                        variantsByProductId.getOrDefault(p.getId(), Collections.emptyList())))
                .toList();

        return PagedResponse.from(productPage, productResponses);
    }

    private PagedResponse<ProductCardResponse> toCardPagedResponse(Page<Product> productPage) {
        List<Product> products = productPage.getContent();
        if (products.isEmpty()) {
            return PagedResponse.from(productPage, Collections.emptyList());
        }

        List<UUID> productIds = products.stream().map(Product::getId).toList();
        Map<UUID, List<ProductMediaSummary>> mediaMap = mediaService.listProductMediaByProductIds(productIds);
        List<ProductVariant> allVariants = productVariantRepository.findAllByProductIdIn(productIds);
        Map<UUID, List<ProductVariant>> variantsByProductId = allVariants.stream()
                .collect(Collectors.groupingBy(ProductVariant::getProductId));
        List<UUID> variantIds = allVariants.stream().map(ProductVariant::getId).toList();
        Map<UUID, ProductStockSummaryDto> stockMap = stockSummaryProvider.getStockSummariesByVariantIds(variantIds);
        Map<UUID, ShopLookupData> shopMap = shopService.findShopLookupDataByIds(products.stream()
                .map(Product::getShopId)
                .distinct()
                .toList());
        Map<UUID, String> categoryPathMap = resolveCategoryPaths(products.stream()
                .map(Product::getCategoryId)
                .distinct()
                .toList());

        List<ProductCardResponse> cards = products.stream().map(p -> {
            List<ProductMediaSummary> media = mediaMap.getOrDefault(p.getId(), List.of());
            ProductMediaSummary cover = media.stream().filter(ProductMediaSummary::cover).findFirst().orElse(null);
            ShopLookupData shop = shopMap.get(p.getShopId());
            List<ProductVariant> variants = variantsByProductId.getOrDefault(p.getId(), List.of());
            List<ProductEligibilityIssue> eligibilityIssues = buildEligibilityIssues(p, variants, stockMap);
            String categoryPath = categoryPathMap.get(p.getCategoryId());
            return buildProductCard(p, cover, shop, categoryPath, eligibilityIssues);
        }).toList();

        return PagedResponse.from(productPage, cards);
    }

    /**
     * Loads a batch of active product cards for the given IDs.
     * Preserves caller ordering; silently drops IDs that are no longer ACTIVE.
     * Used by SearchModule to revalidate and hydrate ES / pgvector candidates.
     */
    @Override
    public List<ProductCardResponse> loadActiveProductCards(List<UUID> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        List<Product> products = productRepository.findAllByIdInAndStatus(productIds, ProductStatus.ACTIVE);
        if (products.isEmpty()) {
            return List.of();
        }

        // Preserve caller ordering (pgvector returns by relevance score)
        Map<UUID, Product> byId = products.stream().collect(Collectors.toMap(Product::getId, p -> p));
        List<Product> ordered = productIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();

        List<UUID> ids = ordered.stream().map(Product::getId).toList();
        Map<UUID, List<ProductMediaSummary>> mediaMap = mediaService.listProductMediaByProductIds(ids);
        List<ProductVariant> allVariants = productVariantRepository.findAllByProductIdIn(ids);
        Map<UUID, List<ProductVariant>> variantsByProductId = allVariants.stream()
                .collect(Collectors.groupingBy(ProductVariant::getProductId));
        List<UUID> variantIds = allVariants.stream().map(ProductVariant::getId).toList();
        Map<UUID, ProductStockSummaryDto> stockMap = stockSummaryProvider.getStockSummariesByVariantIds(variantIds);
        Map<UUID, ShopLookupData> shopMap = shopService.findShopLookupDataByIds(
                ordered.stream().map(Product::getShopId).distinct().toList());
        Map<UUID, String> categoryPathMap = resolveCategoryPaths(
                ordered.stream().map(Product::getCategoryId).distinct().toList());

        return ordered.stream().map(p -> {
            List<ProductMediaSummary> media = mediaMap.getOrDefault(p.getId(), List.of());
            ProductMediaSummary cover = media.stream().filter(ProductMediaSummary::cover).findFirst().orElse(null);
            ShopLookupData shop = shopMap.get(p.getShopId());
            List<ProductVariant> variants = variantsByProductId.getOrDefault(p.getId(), List.of());
            List<ProductEligibilityIssue> eligibilityIssues = buildEligibilityIssues(p, variants, stockMap);
            String categoryPath = categoryPathMap.get(p.getCategoryId());
            return buildProductCard(p, cover, shop, categoryPath, eligibilityIssues);
        }).toList();
    }

    @Override
    public List<ProductCardResponse> loadActiveProductCardsByVariantIds(List<UUID> variantIds) {
        if (variantIds == null || variantIds.isEmpty()) {
            return List.of();
        }

        List<ProductVariant> variants = productVariantRepository.findAllByIdIn(variantIds);
        if (variants.isEmpty()) {
            return List.of();
        }

        Map<UUID, ProductVariant> byId = variants.stream()
                .collect(Collectors.toMap(ProductVariant::getId, v -> v));
        List<UUID> orderedProductIds = variantIds.stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .map(ProductVariant::getProductId)
                .collect(Collectors.collectingAndThen(
                        Collectors.toCollection(LinkedHashSet::new),
                        List::copyOf));
        return loadActiveProductCards(orderedProductIds);
    }

    private ProductCardResponse buildProductCard(
            Product p,
            ProductMediaSummary cover,
            ShopLookupData shop,
            String categoryPath,
            List<ProductEligibilityIssue> eligibilityIssues) {
        return ProductCardResponse.builder()
                .id(p.getId())
                .name(p.getName())
                .brand(p.getBrand())
                .sellerSku(p.getSellerSku())
                .coverImageUrl(cover != null ? cover.publicUrl() : null)
                .coverMediaId(cover != null ? cover.mediaId() : null)
                .coverObjectKey(cover != null ? cover.objectKey() : null)
                .coverContentType(cover != null ? cover.contentType() : null)
                .minPrice(p.getMinPrice())
                .maxPrice(p.getMaxPrice())
                .status(p.getStatus())
                .shopId(p.getShopId())
                .shopName(shop != null ? shop.name() : null)
                .shopRating(shop != null ? shop.rating() : null)
                .categoryPath(categoryPath)
                .checkoutEligible(eligibilityIssues.isEmpty())
                .eligibilityIssues(eligibilityIssues)
                .createdAt(p.getCreatedAt())
                .build();
    }

    @Override
    public Map<String, Long> countShopProductsByStatus(UUID shopId) {
        return productRepository.countByShopIdGroupByStatus(shopId).stream()
                .collect(Collectors.toMap(
                        row -> ((ProductStatus) row[0]).name(),
                        row -> (Long) row[1]));
    }

    @Override
    @Transactional
    public void refreshRatingSummary(UUID productId, BigDecimal ratingAvg, long ratingCount) {
        productRepository.findById(productId).ifPresent(product -> {
            product.refreshRatingSummary(ratingAvg, ratingCount);
            productRepository.save(product);
        });
    }

    /**
     * Batch-loads category paths for a list of category IDs.
     * Returns a map of categoryId → materialized path string.
     */
    private Map<UUID, String> resolveCategoryPaths(List<UUID> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryRepository.findAllById(categoryIds).stream()
                .collect(Collectors.toMap(Category::getId, c -> c.getPath() != null ? c.getPath() : c.getName()));
    }
}
