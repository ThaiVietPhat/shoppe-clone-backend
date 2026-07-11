package com.shopee.monolith.modules.cart.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.auth.security.JwtTokenProvider;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.dto.request.UpdateCartItemRequest;
import com.shopee.monolith.modules.cart.service.CartService;
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
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class CartControllerIT extends BasePostgresRedisIntegrationTest {

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
    private CartService cartService;

    @Autowired
    private ObjectMapper objectMapper;

    private User buyer;
    private String buyerToken;
    private ProductVariant variant;
    private ProductVariant zeroPriceVariant;
    private Category category;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = User.builder()
                .email("buyer.cart.controller@shoppe.local")
                .normalizedEmail("buyer.cart.controller@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        buyer = userRepository.save(buyer);
        buyerToken = jwtTokenProvider.generateAccessToken(buyer.getId(), buyer.getRole());

        User seller = User.builder()
                .email("seller.cart.controller@shoppe.local")
                .normalizedEmail("seller.cart.controller@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build();
        seller = userRepository.save(seller);

        Shop shop = Shop.builder()
                .ownerId(seller.getId())
                .name("Cart Controller Shop")
                .build();
        shop = shopRepository.save(shop);

        category = Category.builder()
                .name("Cart Controller Category")
                .build();
        category = categoryRepository.save(category);

        Product product = Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Cart Controller Product")
                .description("Desc")
                .status(ProductStatus.ACTIVE)
                .build();
        product = productRepository.save(product);

        variant = ProductVariant.builder()
                .productId(product.getId())
                .sku("CART-CTRL-V1")
                .name("Ctrl Variant 1")
                .price(BigDecimal.valueOf(100.00))
                .build();
        variant = productVariantRepository.save(variant);

        zeroPriceVariant = ProductVariant.builder()
                .productId(product.getId())
                .sku("CART-CTRL-ZERO")
                .name("Zero price Variant")
                .price(BigDecimal.ZERO)
                .active(true)
                .build();
        zeroPriceVariant = productVariantRepository.save(zeroPriceVariant);
    }

    @AfterEach
    void tearDown() {
        if (buyer != null) {
            cartService.clearCart(buyer.getId());
        }
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        if (category != null) {
            categoryRepository.delete(category);
        }
        shopRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void whenUnauthenticatedShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedCrudOperationsShouldSucceed() throws Exception {
        // 1. Get Cart (Should be empty)
        mockMvc.perform(get("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items").isEmpty())
                .andExpect(jsonPath("$.data.version").value(0));

        // 2. Add item to cart
        AddCartItemRequest addRequest = new AddCartItemRequest(variant.getId(), 2);
        mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].variantId").value(variant.getId().toString()))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2));

        // 3. Update quantity in cart
        UpdateCartItemRequest updateRequest = new UpdateCartItemRequest(5);
        mockMvc.perform(patch("/api/cart/items/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.items[0].quantity").value(5));

        // 4. Remove item from cart
        mockMvc.perform(delete("/api/cart/items/" + variant.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // Get Cart again to verify it is empty
        mockMvc.perform(get("/api/cart")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    @Test
    void addWhenExceedsMaxQuantityShouldReturn400() throws Exception {
        AddCartItemRequest addRequest = new AddCartItemRequest(variant.getId(), 100); // Max is 99
        mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_REQUEST.getHttpStatus()));
    }

    @Test
    void addWhenVariantNotFoundShouldReturn404() throws Exception {
        AddCartItemRequest addRequest = new AddCartItemRequest(UUID.randomUUID(), 5);
        mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.VARIANT_NOT_FOUND.getHttpStatus()));
    }

    @Test
    void addWhenVariantHasZeroPriceShouldReturn404() throws Exception {
        AddCartItemRequest addRequest = new AddCartItemRequest(zeroPriceVariant.getId(), 1);
        mockMvc.perform(post("/api/cart/items")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(addRequest)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.VARIANT_NOT_FOUND.getHttpStatus()));
    }

    @Test
    void whenRedisUnavailableShouldReturn503() throws Exception {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }

        LettuceConnectionFactory connectionFactory = null;
        StringRedisTemplate originalTemplate = (StringRedisTemplate) ReflectionTestUtils.getField(cartService, "stringRedisTemplate");

        try {
            connectionFactory = new LettuceConnectionFactory("localhost", closedPort);
            connectionFactory.afterPropertiesSet();

            StringRedisTemplate disconnectedTemplate = new StringRedisTemplate(connectionFactory);
            // Replace the template in service to trigger Redis outage failure
            ReflectionTestUtils.setField(cartService, "stringRedisTemplate", disconnectedTemplate);

            mockMvc.perform(get("/api/cart")
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value(503))
                    .andExpect(jsonPath("$.message").value(ErrorCode.SERVICE_UNAVAILABLE.getMessage()));
        } finally {
            if (connectionFactory != null) {
                connectionFactory.destroy();
            }
            if (originalTemplate != null) {
                // Restore template
                ReflectionTestUtils.setField(cartService, "stringRedisTemplate", originalTemplate);
            }
        }
    }
}
