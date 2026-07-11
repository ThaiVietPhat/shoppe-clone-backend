package com.shopee.monolith.modules.inventory.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.auth.security.JwtTokenProvider;
import com.shopee.monolith.modules.inventory.dto.request.CreateInventoryRequest;
import com.shopee.monolith.modules.inventory.dto.request.UpdateStockRequest;
import com.shopee.monolith.modules.inventory.entity.Inventory;
import com.shopee.monolith.modules.inventory.repository.InventoryRepository;
import com.shopee.monolith.modules.product.entity.Category;
import com.shopee.monolith.modules.product.entity.Product;
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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class InventoryControllerIT extends BasePostgresRedisIntegrationTest {

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
    private InventoryRepository inventoryRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User sellerUser;
    private String sellerToken;
    private User nonOwnerUser;
    private String nonOwnerToken;
    private User adminUser;
    private String adminToken;

    private Shop shop;
    private Category category;
    private Product product;
    private ProductVariant variant;
    private Inventory inventory;

    @BeforeEach
    void setUp() {
        tearDown();
        sellerUser = User.builder()
                .email("seller.inventory.rest@shoppe.local")
                .normalizedEmail("seller.inventory.rest@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build();
        sellerUser = userRepository.save(sellerUser);
        sellerToken = jwtTokenProvider.generateAccessToken(sellerUser.getId(), sellerUser.getRole());

        nonOwnerUser = User.builder()
                .email("buyer.inventory.rest@shoppe.local")
                .normalizedEmail("buyer.inventory.rest@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        nonOwnerUser = userRepository.save(nonOwnerUser);
        nonOwnerToken = jwtTokenProvider.generateAccessToken(nonOwnerUser.getId(), nonOwnerUser.getRole());

        adminUser = User.builder()
                .email("admin.inventory.rest@shoppe.local")
                .normalizedEmail("admin.inventory.rest@shoppe.local")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .build();
        adminUser = userRepository.save(adminUser);
        adminToken = jwtTokenProvider.generateAccessToken(adminUser.getId(), adminUser.getRole());

        shop = Shop.builder()
                .ownerId(sellerUser.getId())
                .name("Seller REST Shop")
                .build();
        shop = shopRepository.save(shop);

        category = Category.builder()
                .name("Kitchen REST")
                .build();
        category = categoryRepository.save(category);

        product = Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Toaster")
                .description("2-slice toaster")
                .build();
        product = productRepository.save(product);

        variant = ProductVariant.builder()
                .productId(product.getId())
                .sku("TOAST-01")
                .name("Standard Toaster")
                .price(BigDecimal.valueOf(29.99))
                .build();
        variant = productVariantRepository.save(variant);

        inventory = Inventory.builder()
                .variantId(variant.getId())
                .availableStock(10)
                .reservedStock(0)
                .build();
        inventory = inventoryRepository.save(inventory);
    }

    @AfterEach
    void tearDown() {
        inventoryRepository.deleteAll();
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        if (category != null) {
            categoryRepository.delete(category);
        }
        shopRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void createInventoryWhenPublicShouldReturn401() throws Exception {
        CreateInventoryRequest request = new CreateInventoryRequest(UUID.randomUUID(), 10);

        mockMvc.perform(post("/api/inventories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createInventoryWhenValidShouldSucceed() throws Exception {
        ProductVariant variant2 = ProductVariant.builder()
                .productId(product.getId())
                .sku("TOAST-02")
                .name("Premium Toaster")
                .price(BigDecimal.valueOf(49.99))
                .build();
        variant2 = productVariantRepository.save(variant2);

        CreateInventoryRequest request = new CreateInventoryRequest(variant2.getId(), 20);

        mockMvc.perform(post("/api/inventories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.variantId").value(variant2.getId().toString()))
                .andExpect(jsonPath("$.data.availableStock").value(20))
                .andExpect(jsonPath("$.data.reservedStock").value(0));
    }

    @Test
    void createInventoryWhenAdminShouldSucceed() throws Exception {
        ProductVariant variant2 = ProductVariant.builder()
                .productId(product.getId())
                .sku("TOAST-02")
                .name("Premium Toaster")
                .price(BigDecimal.valueOf(49.99))
                .build();
        variant2 = productVariantRepository.save(variant2);

        CreateInventoryRequest request = new CreateInventoryRequest(variant2.getId(), 20);

        mockMvc.perform(post("/api/inventories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.variantId").value(variant2.getId().toString()));
    }

    @Test
    void createInventoryWhenNonOwnerShouldReturn403() throws Exception {
        ProductVariant variant2 = ProductVariant.builder()
                .productId(product.getId())
                .sku("TOAST-02")
                .name("Premium Toaster")
                .price(BigDecimal.valueOf(49.99))
                .build();
        variant2 = productVariantRepository.save(variant2);

        CreateInventoryRequest request = new CreateInventoryRequest(variant2.getId(), 20);

        mockMvc.perform(post("/api/inventories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_OWNER_REQUIRED.getHttpStatus()));
    }

    @Test
    void createInventoryWhenNegativeStockShouldReturn400() throws Exception {
        CreateInventoryRequest request = new CreateInventoryRequest(variant.getId(), -5);

        mockMvc.perform(post("/api/inventories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void createInventoryWhenVariantNotFoundShouldReturn404() throws Exception {
        CreateInventoryRequest request = new CreateInventoryRequest(UUID.randomUUID(), 10);

        mockMvc.perform(post("/api/inventories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.VARIANT_NOT_FOUND.getHttpStatus()));
    }

    @Test
    void createInventoryWhenAlreadyExistsShouldReturn409() throws Exception {
        CreateInventoryRequest request = new CreateInventoryRequest(variant.getId(), 10);

        mockMvc.perform(post("/api/inventories")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVENTORY_ALREADY_EXISTS.getHttpStatus()));
    }

    @Test
    void getInventoryByVariantIdWhenValidShouldReturnResponse() throws Exception {
        mockMvc.perform(get("/api/inventories/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.variantId").value(variant.getId().toString()))
                .andExpect(jsonPath("$.data.availableStock").value(10));
    }

    @Test
    void getInventoryByVariantIdWhenAdminShouldSucceed() throws Exception {
        mockMvc.perform(get("/api/inventories/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void getInventoryByVariantIdWhenNonOwnerShouldReturn403() throws Exception {
        mockMvc.perform(get("/api/inventories/variants/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonOwnerToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_OWNER_REQUIRED.getHttpStatus()));
    }

    @Test
    void getInventoryByVariantIdWhenNotFoundShouldReturn404() throws Exception {
        mockMvc.perform(get("/api/inventories/variants/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.VARIANT_NOT_FOUND.getHttpStatus()));
    }

    @Test
    void updateAvailableStockWhenValidShouldSucceed() throws Exception {
        UpdateStockRequest request = new UpdateStockRequest(150);

        mockMvc.perform(patch("/api/inventories/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.availableStock").value(150));
    }

    @Test
    void updateAvailableStockWhenAdminShouldSucceed() throws Exception {
        UpdateStockRequest request = new UpdateStockRequest(150);

        mockMvc.perform(patch("/api/inventories/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void updateAvailableStockWhenNonOwnerShouldReturn403() throws Exception {
        UpdateStockRequest request = new UpdateStockRequest(150);

        mockMvc.perform(patch("/api/inventories/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + nonOwnerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_OWNER_REQUIRED.getHttpStatus()));
    }

    @Test
    void updateAvailableStockWhenNegativeStockShouldReturn400() throws Exception {
        UpdateStockRequest request = new UpdateStockRequest(-1);

        mockMvc.perform(patch("/api/inventories/variants/" + variant.getId() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void updateAvailableStockWhenNotFoundShouldReturn404() throws Exception {
        UpdateStockRequest request = new UpdateStockRequest(50);

        mockMvc.perform(patch("/api/inventories/variants/" + UUID.randomUUID() + "/stock")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.VARIANT_NOT_FOUND.getHttpStatus()));
    }
}
