package com.shopee.monolith.modules.product.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.common.response.SwaggerResponses;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.product.dto.request.CreateProductRequest;
import com.shopee.monolith.modules.product.dto.request.CreateProductVariantRequest;
import com.shopee.monolith.modules.product.dto.request.ProductSortOrder;
import com.shopee.monolith.modules.product.dto.request.UpdateProductRequest;
import com.shopee.monolith.modules.product.dto.request.UpdateProductVariantRequest;
import com.shopee.monolith.modules.product.dto.response.CategoryResponse;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.dto.response.ProductDetailResponse;
import com.shopee.monolith.modules.product.dto.response.ProductResponse;
import com.shopee.monolith.modules.product.dto.response.ProductSwaggerResponses;
import com.shopee.monolith.modules.product.dto.response.ProductVariantResponse;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@Validated
@Tag(name = "Products", description = "Product categories, catalog, and seller product variant management APIs")
public class ProductController {

    private final ProductService productService;

    @Operation(summary = "List all categories", description = "Retrieves a list of all active categories.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Categories list retrieved successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponseCategoryList.class))
    )
    @GetMapping("/api/categories")
    public ApiResponse<List<CategoryResponse>> listCategories() {
        return ApiResponse.success(productService.listCategories());
    }

    @Operation(
            summary = "Homepage product feed",
            description = "Returns newest ACTIVE products for homepage display. "
                    + "Deterministic — reads directly from PostgreSQL, never fails due to search or AI availability.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Homepage feed retrieved successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponsePagedProductCardResponse.class)))
    @GetMapping("/api/products/homepage")
    public ApiResponse<PagedResponse<ProductCardResponse>> listHomepageProducts(
            @Parameter(description = "Page index (0-indexed)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(productService.listHomepageProducts(page, size));
    }

    @Operation(
            summary = "Browse products by category",
            description = "Returns ACTIVE products in a category and all its subcategories. "
                    + "Sort options: NEWEST (default), PRICE_ASC, PRICE_DESC. "
                    + "Deterministic — reads from PostgreSQL, no ES or AI dependency.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Category products retrieved successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponsePagedProductCardResponse.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "Category not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @GetMapping("/api/categories/{categoryId}/products")
    public ApiResponse<PagedResponse<ProductCardResponse>> listProductsByCategory(
            @Parameter(description = "Category unique ID") @PathVariable UUID categoryId,
            @Parameter(description = "Sort order: NEWEST, PRICE_ASC, PRICE_DESC") @RequestParam(defaultValue = "NEWEST") ProductSortOrder sort,
            @Parameter(description = "Page index (0-indexed)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(productService.listActiveProductsByCategory(categoryId, sort, page, size));
    }

    @Operation(summary = "List active product cards", description = "Retrieves ACTIVE products for public catalog browsing.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Product cards retrieved successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponsePagedProductCardResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid page or size parameters.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @GetMapping("/api/products")
    public ApiResponse<PagedResponse<ProductCardResponse>> listProducts(
            @Parameter(description = "Page index (0-indexed)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(productService.listActiveProducts(page, size));
    }

    @Operation(summary = "Get active product detail", description = "Retrieves public product detail for an ACTIVE product.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Product details retrieved successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponseProductDetailResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Product not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @GetMapping("/api/products/{productId}")
    public ApiResponse<ProductDetailResponse> getProductById(
            @Parameter(description = "Product unique ID") @PathVariable UUID productId) {
        return ApiResponse.success(productService.getProductDetailById(productId));
    }

    @Operation(summary = "List active shop product cards", description = "Retrieves ACTIVE product cards owned by a seller shop.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Shop products list retrieved successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponsePagedProductCardResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid page or size parameters.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @GetMapping("/api/shops/{shopId}/products")
    public ApiResponse<PagedResponse<ProductCardResponse>> listProductsByShop(
            @Parameter(description = "Shop unique ID") @PathVariable UUID shopId,
            @Parameter(description = "Page index (0-indexed)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return ApiResponse.success(productService.listActiveProductsByShop(shopId, page, size));
    }

    @Operation(
            summary = "List current seller products",
            description = "Retrieves non-deleted seller products with status, media, variants and stock summary.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Seller product list retrieved successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponsePagedProductDetailResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Current user does not own a shop.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @GetMapping("/api/seller/products")
    public ApiResponse<PagedResponse<ProductDetailResponse>> listSellerProducts(
            @Parameter(description = "Filter by product status; omit for all non-deleted products")
            @RequestParam(required = false) ProductStatus status,
            @Parameter(description = "Page index (0-indexed)") @RequestParam(defaultValue = "0") @Min(0) int page,
            @Parameter(description = "Page size (max 100)") @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(productService.listSellerProducts(claims.userId(), status, page, size));
    }

    @Operation(
            summary = "Get current seller product detail",
            description = "Retrieves a non-deleted product owned by the authenticated seller.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Seller product detail retrieved successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponseProductDetailResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Shop owner permission required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Product not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @GetMapping("/api/seller/products/{productId}")
    public ApiResponse<ProductDetailResponse> getSellerProduct(
            @Parameter(description = "Product unique ID") @PathVariable UUID productId,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(productService.getProductDetailForSeller(claims.userId(), productId));
    }

    @Operation(
            summary = "Create product",
            description = "Allows a seller to register a new product under their shop.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Product created successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponseProductResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Shop owner permission required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Shop or Category not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/api/products")
    public ApiResponse<ProductResponse> createProduct(
            @Valid @RequestBody CreateProductRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(productService.createProduct(claims.userId(), request));
    }

    @Operation(
            summary = "Update product details",
            description = "Allows a seller to modify basic description or category details of their product.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Product updated successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponseProductResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Shop owner permission required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Product or Category not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PatchMapping("/api/products/{productId}")
    public ApiResponse<ProductResponse> updateProduct(
            @Parameter(description = "Product unique ID") @PathVariable UUID productId,
            @Valid @RequestBody UpdateProductRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(productService.updateProduct(claims.userId(), productId, request));
    }

    @Operation(
            summary = "Create product variant",
            description = "Adds a new unique SKU variant to an existing product.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Product variant created successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponseProductVariantResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid request details or price format.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Shop owner permission required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Product not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "SKU already exists.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/api/products/{productId}/variants")
    public ApiResponse<ProductVariantResponse> createVariant(
            @Parameter(description = "Product unique ID") @PathVariable UUID productId,
            @Valid @RequestBody CreateProductVariantRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(productService.createVariant(claims.userId(), productId, request));
    }

    @Operation(
            summary = "Update product variant details",
            description = "Updates pricing or details of an existing SKU variant.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Product variant updated successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponseProductVariantResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid request details or price format.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Shop owner permission required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Product or Variant not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "SKU already exists.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PatchMapping("/api/products/{productId}/variants/{variantId}")
    public ApiResponse<ProductVariantResponse> updateVariant(
            @Parameter(description = "Product unique ID") @PathVariable UUID productId,
            @Parameter(description = "Product variant unique ID") @PathVariable UUID variantId,
            @Valid @RequestBody UpdateProductVariantRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(productService.updateVariant(claims.userId(), productId, variantId, request));
    }

    @Operation(
            summary = "Publish product",
            description = "Transitions a seller product from DRAFT or INACTIVE to ACTIVE when it has an active priced variant.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Product published successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponseProductDetailResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Product cannot be published in its current state.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Shop owner permission required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Product not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "422",
            description = "Product has no active priced variant.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/api/products/{productId}/publish")
    public ApiResponse<ProductDetailResponse> publishProduct(
            @Parameter(description = "Product unique ID") @PathVariable UUID productId,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(productService.publishProduct(claims.userId(), productId));
    }

    @Operation(
            summary = "Unpublish product",
            description = "Transitions an ACTIVE seller product to INACTIVE.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Product unpublished successfully.",
            content = @Content(schema = @Schema(implementation = ProductSwaggerResponses.ApiResponseProductDetailResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Shop owner permission required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Product not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/api/products/{productId}/unpublish")
    public ApiResponse<ProductDetailResponse> unpublishProduct(
            @Parameter(description = "Product unique ID") @PathVariable UUID productId,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.success(productService.unpublishProduct(claims.userId(), productId));
    }

    @Operation(
            summary = "Delete product",
            description = "Soft-deletes a seller product by setting status to DELETED.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Product deleted successfully.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "Shop owner permission required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404",
            description = "Product not found.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @DeleteMapping("/api/products/{productId}")
    public ApiResponse<Void> deleteProduct(
            @Parameter(description = "Product unique ID") @PathVariable UUID productId,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        productService.deleteProduct(claims.userId(), productId);
        return ApiResponse.success(null);
    }
}
