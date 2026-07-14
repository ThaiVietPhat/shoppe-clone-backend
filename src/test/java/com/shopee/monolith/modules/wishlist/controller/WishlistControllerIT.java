package com.shopee.monolith.modules.wishlist.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.modules.auth.security.JwtTokenProvider;
import com.shopee.monolith.modules.product.entity.Category;
import com.shopee.monolith.modules.product.entity.Product;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.repository.CategoryRepository;
import com.shopee.monolith.modules.product.repository.ProductRepository;
import com.shopee.monolith.modules.user.entity.Shop;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.ShopRepository;
import com.shopee.monolith.modules.user.repository.UserRepository;
import com.shopee.monolith.modules.wishlist.repository.WishlistItemRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WishlistControllerIT extends BasePostgresRedisIntegrationTest {

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
    private WishlistItemRepository wishlistItemRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private User buyer;
    private User seller;
    private Category category;
    private Product product;
    private String buyerToken;

    @BeforeEach
    void setUp() {
        tearDown();
        buyer = userRepository.save(User.builder()
                .email("buyer-wishlist-" + UUID.randomUUID() + "@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());
        buyerToken = jwtTokenProvider.generateAccessToken(buyer.getId(), buyer.getRole());

        seller = userRepository.save(User.builder()
                .email("seller-wishlist-" + UUID.randomUUID() + "@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build());
        Shop shop = shopRepository.save(Shop.builder().ownerId(seller.getId()).name("Wishlist Shop").build());
        category = categoryRepository.save(Category.builder().name("Wishlist Category").build());
        product = productRepository.save(Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Wishlist Product")
                .status(ProductStatus.ACTIVE)
                .build());
    }

    @AfterEach
    void tearDown() {
        wishlistItemRepository.deleteAll();
        if (product != null) {
            productRepository.deleteById(product.getId());
        }
        if (category != null) {
            categoryRepository.deleteById(category.getId());
        }
        shopRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void addThenListShouldReturnProductCard() throws Exception {
        mockMvc.perform(post("/api/wishlist/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/wishlist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].id").value(product.getId().toString()))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void addWhenAlreadyWishlistedShouldReturn409() throws Exception {
        mockMvc.perform(post("/api/wishlist/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/wishlist/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isConflict());
    }

    @Test
    void addWhenProductMissingShouldReturn404() throws Exception {
        mockMvc.perform(post("/api/wishlist/" + UUID.randomUUID())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void removeShouldDeleteWishlistItem() throws Exception {
        mockMvc.perform(post("/api/wishlist/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/wishlist/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/wishlist")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void checkStatusShouldReportWishlistedAndNonWishlisted() throws Exception {
        mockMvc.perform(post("/api/wishlist/" + product.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk());

        UUID otherProductId = UUID.randomUUID();
        String body = objectMapper.writeValueAsString(
                new com.shopee.monolith.modules.wishlist.dto.request.WishlistCheckRequest(
                        java.util.List.of(product.getId(), otherProductId)));

        mockMvc.perform(post("/api/wishlist/check")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data['" + product.getId() + "']").value(true))
                .andExpect(jsonPath("$.data['" + otherProductId + "']").value(false));
    }
}
