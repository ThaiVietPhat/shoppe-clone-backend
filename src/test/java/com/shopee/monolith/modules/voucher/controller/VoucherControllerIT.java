package com.shopee.monolith.modules.voucher.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.modules.auth.security.JwtTokenProvider;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.UserRepository;
import com.shopee.monolith.modules.voucher.dto.request.CreateVoucherRequest;
import com.shopee.monolith.modules.voucher.dto.request.ValidateVoucherRequest;
import com.shopee.monolith.modules.voucher.entity.Voucher;
import com.shopee.monolith.modules.voucher.model.DiscountType;
import com.shopee.monolith.modules.voucher.repository.VoucherRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class VoucherControllerIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private VoucherRepository voucherRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private User adminUser;
    private User buyerUser;
    private String adminToken;
    private String buyerToken;

    @BeforeEach
    void setUp() {
        tearDown();
        adminUser = userRepository.save(User.builder()
                .email("admin-" + java.util.UUID.randomUUID() + "@shoppe.local")
                .role(Role.ADMIN)
                .status(UserStatus.ACTIVE)
                .build());
        adminToken = jwtTokenProvider.generateAccessToken(adminUser.getId(), adminUser.getRole());

        buyerUser = userRepository.save(User.builder()
                .email("buyer-" + java.util.UUID.randomUUID() + "@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());
        buyerToken = jwtTokenProvider.generateAccessToken(buyerUser.getId(), buyerUser.getRole());
    }

    @AfterEach
    void tearDown() {
        voucherRepository.deleteAll();
        if (adminUser != null) {
            userRepository.deleteById(adminUser.getId());
        }
        if (buyerUser != null) {
            userRepository.deleteById(buyerUser.getId());
        }
    }

    @Test
    void createVoucherWhenAdminShouldReturn200() throws Exception {
        CreateVoucherRequest request = CreateVoucherRequest.builder()
                .code("ADMIN10")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(BigDecimal.TEN)
                .minOrderAmount(BigDecimal.ZERO)
                .startsAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(30)))
                .build();

        mockMvc.perform(post("/api/admin/vouchers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("ADMIN10"));
    }

    @Test
    void createVoucherWhenNotAdminShouldReturn403() throws Exception {
        CreateVoucherRequest request = CreateVoucherRequest.builder()
                .code("BUYER10")
                .discountType(DiscountType.PERCENTAGE)
                .discountValue(BigDecimal.TEN)
                .minOrderAmount(BigDecimal.ZERO)
                .startsAt(Instant.now())
                .expiresAt(Instant.now().plus(Duration.ofDays(30)))
                .build();

        mockMvc.perform(post("/api/admin/vouchers")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void listVouchersWhenUnauthenticatedShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/admin/vouchers"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validateVoucherWhenEligibleShouldReturnDiscount() throws Exception {
        voucherRepository.save(Voucher.builder()
                .code("VALIDME")
                .discountType(DiscountType.FIXED_AMOUNT)
                .discountValue(new BigDecimal("15"))
                .minOrderAmount(BigDecimal.ZERO)
                .startsAt(Instant.now().minus(Duration.ofDays(1)))
                .expiresAt(Instant.now().plus(Duration.ofDays(1)))
                .build());

        ValidateVoucherRequest request = ValidateVoucherRequest.builder()
                .code("validme")
                .orderSubtotal(new BigDecimal("100"))
                .build();

        mockMvc.perform(post("/api/vouchers/validate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.discountAmount").value(15.00));
    }

    @Test
    void validateVoucherWhenCodeMissingShouldReturn404() throws Exception {
        ValidateVoucherRequest request = ValidateVoucherRequest.builder()
                .code("NOPE")
                .orderSubtotal(BigDecimal.TEN)
                .build();

        mockMvc.perform(post("/api/vouchers/validate")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound());
    }
}
