package com.shopee.monolith.modules.user.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.auth.security.JwtTokenProvider;
import com.shopee.monolith.modules.user.dto.request.CreateShopRequest;
import com.shopee.monolith.modules.user.dto.request.UpdateShopRequest;
import com.shopee.monolith.modules.user.entity.Address;
import com.shopee.monolith.modules.user.entity.Shop;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.AddressRepository;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class ShopControllerIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ShopRepository shopRepository;

    @Autowired
    private AddressRepository addressRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User activeUser;
    private User inactiveUser;
    private String activeToken;
    private String inactiveToken;

    @BeforeEach
    void setUp() {
        activeUser = User.builder()
                .email("seller.active@shoppe.local")
                .normalizedEmail("seller.active@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        activeUser = userRepository.save(activeUser);
        activeToken = jwtTokenProvider.generateAccessToken(activeUser.getId(), activeUser.getRole());

        inactiveUser = User.builder()
                .email("seller.inactive@shoppe.local")
                .normalizedEmail("seller.inactive@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.PENDING_VERIFICATION)
                .build();
        inactiveUser = userRepository.save(inactiveUser);
        inactiveToken = jwtTokenProvider.generateAccessToken(inactiveUser.getId(), inactiveUser.getRole());
    }

    @AfterEach
    void tearDown() {
        shopRepository.deleteAll();
        addressRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UUID saveAddressFor(User owner) {
        Address address = Address.builder()
                .userId(owner.getId())
                .recipientName("Test Recipient")
                .phone("0987654321")
                .addressLine("123 Main Street")
                .wardCode("20314")
                .wardName("Phường Liễu Giai")
                .districtCode("1442")
                .districtName("Quận Ba Đình")
                .provinceCode("201")
                .provinceName("Thành phố Hà Nội")
                .isDefault(true)
                .build();
        return addressRepository.save(address).getId();
    }

    @Test
    void createShopWhenActiveUserAndValidRequestShouldSucceed() throws Exception {
        UUID addressId = saveAddressFor(activeUser);
        CreateShopRequest request = CreateShopRequest.builder()
                .name("Official Active Shop")
                .description("Active description")
                .addressId(addressId)
                .build();

        mockMvc.perform(post("/api/shops")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + activeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("Official Active Shop"))
                .andExpect(jsonPath("$.data.ownerId").value(activeUser.getId().toString()));

        User promotedUser = userRepository.findById(activeUser.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(Role.SELLER, promotedUser.getRole());
    }

    @Test
    void createShopWhenInactiveUserShouldReturn403Forbidden() throws Exception {
        UUID addressId = saveAddressFor(inactiveUser);
        CreateShopRequest request = CreateShopRequest.builder()
                .name("Official Inactive Shop")
                .description("Inactive description")
                .addressId(addressId)
                .build();

        mockMvc.perform(post("/api/shops")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + inactiveToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.ACCOUNT_NOT_ACTIVE.getHttpStatus()))
                .andExpect(jsonPath("$.message").value(ErrorCode.ACCOUNT_NOT_ACTIVE.getMessage()));
    }

    @Test
    void createShopWhenAnonymousUserShouldReturn401Unauthorized() throws Exception {
        CreateShopRequest request = CreateShopRequest.builder()
                .name("Anonymous Shop")
                .build();

        mockMvc.perform(post("/api/shops")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getHttpStatus()));
    }

    @Test
    void createShopWhenShopAlreadyExistsShouldReturn409Conflict() throws Exception {
        Shop shop = Shop.builder()
                .ownerId(activeUser.getId())
                .name("First Shop")
                .build();
        shopRepository.save(shop);

        UUID addressId = saveAddressFor(activeUser);
        CreateShopRequest request = CreateShopRequest.builder()
                .name("Second Shop")
                .addressId(addressId)
                .build();

        mockMvc.perform(post("/api/shops")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + activeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_ALREADY_EXISTS.getHttpStatus()))
                .andExpect(jsonPath("$.message").value(ErrorCode.SHOP_ALREADY_EXISTS.getMessage()));
    }

    @Test
    void getMyShopWhenShopExistsShouldReturnDetails() throws Exception {
        Shop shop = Shop.builder()
                .ownerId(activeUser.getId())
                .name("Active Shop")
                .description("Description")
                .build();
        shopRepository.save(shop);

        mockMvc.perform(get("/api/shops/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + activeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("Active Shop"));
    }

    @Test
    void getMyShopWhenShopDoesNotExistShouldReturn404NotFound() throws Exception {
        mockMvc.perform(get("/api/shops/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + activeToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_NOT_FOUND.getHttpStatus()))
                .andExpect(jsonPath("$.message").value(ErrorCode.SHOP_NOT_FOUND.getMessage()));
    }

    @Test
    void getMyShopWhenAnonymousShouldReturn401Unauthorized() throws Exception {
        mockMvc.perform(get("/api/shops/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getHttpStatus()));
    }

    @Test
    void updateMyShopWhenShopExistsShouldUpdateAndReturnShop() throws Exception {
        Shop shop = Shop.builder()
                .ownerId(activeUser.getId())
                .name("Active Shop")
                .description("Description")
                .build();
        shopRepository.save(shop);

        UpdateShopRequest request = UpdateShopRequest.builder()
                .name("Updated Active Shop")
                .description("Updated description")
                .build();

        mockMvc.perform(patch("/api/shops/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + activeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("Updated Active Shop"))
                .andExpect(jsonPath("$.data.description").value("Updated description"));
    }

    @Test
    void getShopByIdWhenPublicSucceedsWithoutAuth() throws Exception {
        Shop shop = Shop.builder()
                .ownerId(activeUser.getId())
                .name("Public Lookup Shop")
                .description("Public desc")
                .build();
        shop = shopRepository.save(shop);

        mockMvc.perform(get("/api/shops/" + shop.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.name").value("Public Lookup Shop"));
    }

    @Test
    void getShopByIdWhenDoesNotExistShouldReturn404NotFound() throws Exception {
        mockMvc.perform(get("/api/shops/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(ErrorCode.SHOP_NOT_FOUND.getHttpStatus()));
    }
}
