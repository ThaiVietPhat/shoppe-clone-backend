package com.shopee.monolith.modules.product.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.auth.security.JwtTokenProvider;
import com.shopee.monolith.modules.media.entity.MediaAsset;
import com.shopee.monolith.modules.media.entity.MediaOwnerType;
import com.shopee.monolith.modules.media.entity.MediaPurpose;
import com.shopee.monolith.modules.media.entity.MediaStatus;
import com.shopee.monolith.modules.media.entity.ProductMedia;
import com.shopee.monolith.modules.media.entity.ProductMediaId;
import com.shopee.monolith.modules.media.repository.MediaAssetRepository;
import com.shopee.monolith.modules.media.repository.ProductMediaRepository;
import com.shopee.monolith.modules.product.dto.request.CreateProductRequest;
import com.shopee.monolith.modules.product.dto.request.CreateProductVariantRequest;
import com.shopee.monolith.modules.product.dto.request.UpdateProductRequest;
import com.shopee.monolith.modules.product.dto.request.UpdateProductVariantRequest;
import com.shopee.monolith.modules.product.entity.Category;
import com.shopee.monolith.modules.product.entity.Product;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.entity.ProductVariant;
import com.shopee.monolith.modules.product.repository.CategoryRepository;
import com.shopee.monolith.modules.product.repository.ProductRepository;
import com.shopee.monolith.modules.product.repository.ProductVariantRepository;
import com.shopee.monolith.modules.user.entity.Shop;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.ShopRepository;
import com.shopee.monolith.modules.user.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ProductControllerIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductVariantRepository productVariantRepository;

    @Autowired
    private MediaAssetRepository mediaAssetRepository;

    @Autowired
    private ProductMediaRepository productMediaRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User ownerUser;
    private User otherUser;
    private String ownerToken;
    private String otherToken;
    private Shop shop;
    private Category category;
    private Product product;
    private ProductVariant variant;

    @BeforeEach
    void setUp() {
        tearDown();
        ownerUser = User.builder()
                .email("owner@shoppe.local")
                .normalizedEmail("owner@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        ownerUser = userRepository.save(ownerUser);
        ownerToken = jwtTokenProvider.generateAccessToken(ownerUser.getId(), ownerUser.getRole());

        otherUser = User.builder()
                .email("other@shoppe.local")
                .normalizedEmail("other@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        otherUser = userRepository.save(otherUser);
        otherToken = jwtTokenProvider.generateAccessToken(otherUser.getId(), otherUser.getRole());

        shop = Shop.builder()
                .ownerId(ownerUser.getId())
                .name("Owner Shop")
                .build();
        shop = shopRepository.save(shop);

        category = Category.builder()
                .name("Electronics")
                .build();
        category = categoryRepository.save(category);

        product = Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("iPhone 15 Pro")
                .description("Titanium body")
                .status(ProductStatus.ACTIVE)
                .minPrice(BigDecimal.valueOf(999.00))
                .maxPrice(BigDecimal.valueOf(999.00))
                .build();
        product = productRepository.save(product);

        variant = ProductVariant.builder()
                .productId(product.getId())
                .sku("IPHONE15PRO-128")
                .name("128GB Black")
                .price(BigDecimal.valueOf(999.00))
                .build();
        variant = productVariantRepository.save(variant);
    }

    @AfterEach
    void tearDown() {
        productMediaRepository.deleteAll();
        mediaAssetRepository.deleteAll();
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        if (category != null) {
            categoryRepository.delete(category);
        }
        shopRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void listCategoriesWhenPublicShouldSucceed() throws Exception {
        mockMvc.perform(get("/api/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].name").value("Electronics"));
    }

    @Test
    void listProductsWhenPublicShouldSucceed() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].name").value("iPhone 15 Pro"))
                .andExpect(jsonPath("$.data.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void getProductByIdWhenPublicShouldSucceed() throws Exception {
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("iPhone 15 Pro"))
                .andExpect(jsonPath("$.data.variants[0].sku").value("IPHONE15PRO-128"));
    }

    @Test
    void getProductByIdWhenNotFoundShouldReturn404() throws Exception {
        mockMvc.perform(get("/api/products/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_NOT_FOUND.getHttpStatus()));
    }

    @Test
    void listProductsByShopWhenPublicShouldSucceed() throws Exception {
        mockMvc.perform(get("/api/shops/" + shop.getId() + "/products")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].name").value("iPhone 15 Pro"))
                .andExpect(jsonPath("$.data.page").value(0));
    }

    @Test
    void createProductWhenAnonymousShouldReturn401() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("New Product")
                .description("Desc")
                .build();

        mockMvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getHttpStatus()));
    }

    @Test
    void createProductWhenNotShopOwnerShouldReturn403() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("New Product")
                .description("Desc")
                .build();

        mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_OWNER_REQUIRED.getHttpStatus()));
    }

    @Test
    void createProductWhenShopOwnerShouldSucceed() throws Exception {
        CreateProductRequest request = CreateProductRequest.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("New Product")
                .description("Desc")
                .build();

        mockMvc.perform(post("/api/products")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("New Product"));
    }

    @Test
    void updateProductWhenNotShopOwnerShouldReturn403() throws Exception {
        UpdateProductRequest request = UpdateProductRequest.builder()
                .categoryId(category.getId())
                .name("Updated Product")
                .description("Updated Desc")
                .build();

        mockMvc.perform(patch("/api/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_OWNER_REQUIRED.getHttpStatus()));
    }

    @Test
    void updateProductWhenShopOwnerShouldSucceed() throws Exception {
        UpdateProductRequest request = UpdateProductRequest.builder()
                .categoryId(category.getId())
                .name("Updated Product")
                .description("Updated Desc")
                .build();

        mockMvc.perform(patch("/api/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("Updated Product"))
                .andExpect(jsonPath("$.data.description").value("Updated Desc"));
    }

    @Test
    void createVariantWhenNotShopOwnerShouldReturn403() throws Exception {
        CreateProductVariantRequest request = CreateProductVariantRequest.builder()
                .sku("IPHONE15PRO-256")
                .name("256GB Black")
                .price(BigDecimal.valueOf(1099.00))
                .build();

        mockMvc.perform(post("/api/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_OWNER_REQUIRED.getHttpStatus()));
    }

    @Test
    void createVariantWhenShopOwnerShouldSucceed() throws Exception {
        CreateProductVariantRequest request = CreateProductVariantRequest.builder()
                .sku("IPHONE15PRO-256")
                .name("256GB Black")
                .price(BigDecimal.valueOf(1099.00))
                .build();

        mockMvc.perform(post("/api/products/" + product.getId() + "/variants")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sku").value("IPHONE15PRO-256"));
    }

    @Test
    void updateVariantWhenNotShopOwnerShouldReturn403() throws Exception {
        UpdateProductVariantRequest request = UpdateProductVariantRequest.builder()
                .sku("IPHONE15PRO-128-UPD")
                .name("128GB Titanium")
                .price(BigDecimal.valueOf(949.00))
                .build();

        mockMvc.perform(patch("/api/products/" + product.getId() + "/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_OWNER_REQUIRED.getHttpStatus()));
    }

    @Test
    void updateVariantWhenShopOwnerShouldSucceed() throws Exception {
        UpdateProductVariantRequest request = UpdateProductVariantRequest.builder()
                .sku("IPHONE15PRO-128-UPD")
                .name("128GB Titanium")
                .price(BigDecimal.valueOf(949.00))
                .build();

        mockMvc.perform(patch("/api/products/" + product.getId() + "/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.sku").value("IPHONE15PRO-128-UPD"))
                .andExpect(jsonPath("$.data.name").value("128GB Titanium"))
                .andExpect(jsonPath("$.data.price").value(949.00));
    }

    @Test
    void publishProductWhenDraftHasActivePricedVariantShouldSucceed() throws Exception {
        Product draft = productRepository.save(Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Draft Phone")
                .description("Draft description")
                .status(ProductStatus.DRAFT)
                .build());
        productVariantRepository.save(ProductVariant.builder()
                .productId(draft.getId())
                .sku("DRAFT-PHONE-128")
                .name("128GB")
                .price(BigDecimal.valueOf(499.00))
                .build());

        mockMvc.perform(post("/api/products/" + draft.getId() + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.minPrice").value(499.00))
                .andExpect(jsonPath("$.data.maxPrice").value(499.00));
    }

    @Test
    void publishProductWhenNoActiveVariantShouldReturn422() throws Exception {
        Product draft = productRepository.save(Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Draft Without Variant")
                .description("Draft description")
                .status(ProductStatus.DRAFT)
                .build());

        mockMvc.perform(post("/api/products/" + draft.getId() + "/publish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value(ErrorCode.PRODUCT_HAS_NO_ACTIVE_VARIANT.getHttpStatus()));
    }

    @Test
    void unpublishProductWhenActiveShouldHideFromPublicCatalog() throws Exception {
        mockMvc.perform(post("/api/products/" + product.getId() + "/unpublish")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("INACTIVE"));

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void deleteProductWhenShopOwnerShouldSoftDeleteAndHideFromSeller() throws Exception {
        mockMvc.perform(delete("/api/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/api/seller/products/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void attachProductMediaWhenTwoCoverRowsExistShouldBeRejectedByDatabase() {
        MediaAsset first = mediaAssetRepository.save(buildMediaAsset("first.png"));
        MediaAsset second = mediaAssetRepository.save(buildMediaAsset("second.png"));
        productMediaRepository.saveAndFlush(ProductMedia.builder()
                .id(new ProductMediaId(product.getId(), first.getId()))
                .sortOrder(0)
                .cover(true)
                .build());

        ProductMedia duplicateCover = ProductMedia.builder()
                .id(new ProductMediaId(product.getId(), second.getId()))
                .sortOrder(1)
                .cover(true)
                .build();

        assertThrows(DataIntegrityViolationException.class, () -> productMediaRepository.saveAndFlush(duplicateCover));
    }

    private MediaAsset buildMediaAsset(String objectKey) {
        return MediaAsset.builder()
                .ownerId(shop.getId())
                .ownerType(MediaOwnerType.SHOP)
                .purpose(MediaPurpose.PRODUCT_IMAGE)
                .objectKey(objectKey)
                .originalFilename(objectKey)
                .contentType("image/png")
                .sizeBytes(12)
                .status(MediaStatus.READY)
                .build();
    }

    @Test
    void listProductsWhenInvalidPageShouldReturn400() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listProductsWhenInvalidSizeShouldReturn400() throws Exception {
        mockMvc.perform(get("/api/products")
                        .param("page", "0")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listProductsByShopWhenInvalidPageShouldReturn400() throws Exception {
        mockMvc.perform(get("/api/shops/" + shop.getId() + "/products")
                        .param("page", "-1")
                        .param("size", "20"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void listProductsByShopWhenInvalidSizeShouldReturn400() throws Exception {
        mockMvc.perform(get("/api/shops/" + shop.getId() + "/products")
                        .param("page", "0")
                        .param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }
}
