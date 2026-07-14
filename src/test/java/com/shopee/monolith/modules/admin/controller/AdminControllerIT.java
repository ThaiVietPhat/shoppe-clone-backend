package com.shopee.monolith.modules.admin.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.modules.auth.security.JwtTokenProvider;
import com.shopee.monolith.modules.moderation.dto.request.CreateReportRequest;
import com.shopee.monolith.modules.moderation.dto.request.ResolveReportRequest;
import com.shopee.monolith.modules.moderation.entity.Report;
import com.shopee.monolith.modules.moderation.model.ReportReasonCategory;
import com.shopee.monolith.modules.moderation.model.ReportStatus;
import com.shopee.monolith.modules.moderation.model.ReportTargetType;
import com.shopee.monolith.modules.moderation.repository.ReportRepository;
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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AdminControllerIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ShopRepository shopRepository;
    @Autowired
    private ReportRepository reportRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private User adminUser;
    private User buyerUser;
    private String adminToken;
    private String buyerToken;
    private Shop shop;

    @BeforeEach
    void setUp() {
        tearDown();
        adminUser = userRepository.save(User.builder()
                .email("admin-" + UUID.randomUUID() + "@shoppe.local")
                .role(Role.ADMIN).status(UserStatus.ACTIVE).build());
        adminToken = jwtTokenProvider.generateAccessToken(adminUser.getId(), adminUser.getRole());

        buyerUser = userRepository.save(User.builder()
                .email("buyer-" + UUID.randomUUID() + "@shoppe.local")
                .role(Role.BUYER).status(UserStatus.ACTIVE).build());
        buyerToken = jwtTokenProvider.generateAccessToken(buyerUser.getId(), buyerUser.getRole());

        shop = shopRepository.save(Shop.builder().ownerId(buyerUser.getId()).name("Test Shop").build());
    }

    @AfterEach
    void tearDown() {
        reportRepository.deleteAll();
        if (shop != null) {
            shopRepository.deleteById(shop.getId());
        }
        if (adminUser != null) {
            userRepository.deleteById(adminUser.getId());
        }
        if (buyerUser != null) {
            userRepository.deleteById(buyerUser.getId());
        }
    }

    @Test
    void listUsersWhenAdminShouldReturn200() throws Exception {
        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void listUsersWhenNotAdminShouldReturn403() throws Exception {
        mockMvc.perform(get("/api/admin/users").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void banThenUnbanUserShouldToggleStatus() throws Exception {
        mockMvc.perform(post("/api/admin/users/" + buyerUser.getId() + "/ban")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        User banned = userRepository.findById(buyerUser.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(UserStatus.LOCKED, banned.getStatus());

        mockMvc.perform(post("/api/admin/users/" + buyerUser.getId() + "/unban")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        User unbanned = userRepository.findById(buyerUser.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(UserStatus.ACTIVE, unbanned.getStatus());
    }

    @Test
    void suspendShopWhenNotAdminShouldReturn403() throws Exception {
        mockMvc.perform(post("/api/admin/shops/" + shop.getId() + "/suspend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isForbidden());
    }

    @Test
    void suspendShopWhenAdminShouldSucceed() throws Exception {
        mockMvc.perform(post("/api/admin/shops/" + shop.getId() + "/suspend")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken))
                .andExpect(status().isOk());

        Shop suspended = shopRepository.findById(shop.getId()).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(
                com.shopee.monolith.modules.user.model.ShopStatus.SUSPENDED, suspended.getStatus());
    }

    @Test
    void createReportThenAdminResolveShouldSucceed() throws Exception {
        CreateReportRequest createRequest = CreateReportRequest.builder()
                .targetType(ReportTargetType.SHOP)
                .targetId(shop.getId())
                .reasonCategory(ReportReasonCategory.PROHIBITED)
                .description("selling banned items")
                .build();

        String responseJson = mockMvc.perform(post("/api/reports")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        UUID reportId = UUID.fromString(objectMapper.readTree(responseJson).path("data").path("id").asText());

        mockMvc.perform(get("/api/admin/reports").header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isForbidden());

        ResolveReportRequest resolveRequest = ResolveReportRequest.builder()
                .outcome(ReportStatus.RESOLVED).note("shop warned").build();

        mockMvc.perform(patch("/api/admin/reports/" + reportId + "/resolve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(resolveRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("RESOLVED"));

        Report resolved = reportRepository.findById(reportId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(ReportStatus.RESOLVED, resolved.getStatus());
    }
}
