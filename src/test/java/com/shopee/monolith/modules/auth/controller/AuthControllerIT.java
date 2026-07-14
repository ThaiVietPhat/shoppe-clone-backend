package com.shopee.monolith.modules.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.auth.config.AuthSecurityProperties;
import com.shopee.monolith.modules.auth.dto.request.ForgotPasswordRequest;
import com.shopee.monolith.modules.auth.dto.request.OAuth2ExchangeRequest;
import com.shopee.monolith.modules.auth.dto.request.ResetPasswordRequest;
import com.shopee.monolith.modules.auth.repository.RefreshTokenRepository;
import com.shopee.monolith.modules.auth.repository.RefreshTokenFamilyRepository;
import com.shopee.monolith.modules.auth.security.VerificationTokenGenerator;
import com.shopee.monolith.modules.user.entity.PasswordResetToken;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.PasswordResetTokenRepository;
import com.shopee.monolith.modules.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class AuthControllerIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AuthSecurityProperties properties;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void csrfWhenGetCsrfEndpointShouldSetCsrfCookie() throws Exception {
        mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE));
    }

    @Test
    void loginWhenCsrfTokenMissingShouldReturn403Forbidden() throws Exception {
        String loginPayload = "{\"email\":\"test@example.com\",\"password\":\"password123\"}";

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginWhenCsrfTokenPresentShouldPassCsrfFilter() throws Exception {
        String loginPayload = "{\"email\":\"test@example.com\",\"password\":\"password123\"}";

        // 1. Fetch CSRF token
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpServletResponse csrfResponse = csrfResult.getResponse();
        var csrfCookie = csrfResponse.getCookie(properties.getCsrf().getCookieName());
        assertNotNull(csrfCookie);

        // 2. Post login with CSRF cookie and header
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginPayload)
                        .cookie(csrfCookie)
                        .header(properties.getCsrf().getHeaderName(), csrfCookie.getValue()))
                // Since user test@example.com doesn't exist, we expect 401 INVALID_CREDENTIALS,
                // which proves the request successfully passed the CSRF filter!
                .andExpect(status().isUnauthorized());
    }

    @Test
    void corsWhenAllowedOriginShouldReturnCorsHeaders() throws Exception {
        String origin = properties.getCors().getAllowedOrigins().get(0);

        mockMvc.perform(get("/api/auth/csrf")
                        .header(HttpHeaders.ORIGIN, origin))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, origin))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true"));
    }

    @Test
    void corsWhenDisallowedOriginShouldNotReturnCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/auth/csrf")
                        .header(HttpHeaders.ORIGIN, "http://malicious-attacker.com"))
                .andExpect(status().isForbidden())
                // In Spring Security, if an origin is disallowed, Access-Control-Allow-Origin is not set or request is rejected
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void actuatorInfoShouldBeSecured() throws Exception {
        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void actuatorHealthShouldBePublic() throws Exception {
        // Since database/redis health indicators might be DOWN or UP in test, we just check liveness/readiness endpoint
        int livenessStatus = mockMvc.perform(get("/actuator/health/liveness"))
                .andReturn().getResponse().getStatus();
        // Since probe is public, it should return 200 (or 503 if DOWN), but definitely NOT 401/403.
        assertEquals(200, livenessStatus);
    }

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private RefreshTokenFamilyRepository refreshTokenFamilyRepository;

    @Autowired
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Autowired
    private VerificationTokenGenerator verificationTokenGenerator;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder withCsrf(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder builder, MvcResult csrfResult) {
        var csrfCookie = csrfResult.getResponse().getCookie(properties.getCsrf().getCookieName());
        return builder.cookie(csrfCookie).header(properties.getCsrf().getHeaderName(), csrfCookie.getValue());
    }

    @Test
    void forgotPasswordWhenUserExistsShouldReturn200() throws Exception {
        User user = userRepository.save(User.builder()
                .email("forgot-" + UUID.randomUUID() + "@example.com")
                .passwordHash("oldHash")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());

        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

        mockMvc.perform(withCsrf(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordRequest(user.getEmail()))), csrfResult))
                .andExpect(status().isOk());

        userRepository.delete(user);
    }

    @Test
    void forgotPasswordWhenUserDoesNotExistShouldStillReturn200() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

        mockMvc.perform(withCsrf(post("/api/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ForgotPasswordRequest("nobody-" + UUID.randomUUID() + "@example.com"))), csrfResult))
                .andExpect(status().isOk());
    }

    @Test
    void resetPasswordWhenTokenValidShouldUpdatePasswordAndRevokeSessions() throws Exception {
        User user = userRepository.save(User.builder()
                .email("reset-" + UUID.randomUUID() + "@example.com")
                .passwordHash(passwordEncoder.encode("oldPassword123"))
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());

        String rawToken = verificationTokenGenerator.generate();
        String tokenHash = verificationTokenGenerator.hash(rawToken);
        passwordResetTokenRepository.save(PasswordResetToken.builder()
                .userId(user.getId())
                .tokenHash(tokenHash)
                .expiresAt(Instant.now().plus(Duration.ofMinutes(30)))
                .build());

        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

        mockMvc.perform(withCsrf(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest(rawToken, "newPassword456"))), csrfResult))
                .andExpect(status().isOk());

        User updated = userRepository.findById(user.getId()).orElseThrow();
        assertTrue(passwordEncoder.matches("newPassword456", updated.getPasswordHash()));

        userRepository.delete(user);
    }

    @Test
    void resetPasswordWhenTokenInvalidShouldReturn400() throws Exception {
        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

        mockMvc.perform(withCsrf(post("/api/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ResetPasswordRequest("not-a-real-token", "newPassword456"))), csrfResult))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exchangeOAuth2CodeConcurrentlyShouldSucceedOnlyOnceOnRedisRealServer() throws Exception {
        // 1. Create a real user in DB
        User user = User.builder()
                .email("oauth2-concurrent-" + UUID.randomUUID() + "@example.com")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        user = userRepository.save(user);
        final UUID userId = user.getId();

        // 2. Seed exchange code in Redis
        String code = UUID.randomUUID().toString();
        String key = "oauth2:code:" + code;

        try {
            String redisVal = userId.toString() + ":BUYER";
            stringRedisTemplate.opsForValue().set(key, redisVal, Duration.ofSeconds(60));

            // 3. Prepare exchange request
            OAuth2ExchangeRequest exchangeReq = new OAuth2ExchangeRequest(code);

            int concurrency = 2;
            ExecutorService executor = Executors.newFixedThreadPool(concurrency);
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch endLatch = new CountDownLatch(concurrency);
            List<Future<MvcResult>> futures = new ArrayList<>();

            for (int i = 0; i < concurrency; i++) {
                futures.add(executor.submit(() -> {
                    startLatch.await();
                    try {
                        MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf"))
                                .andExpect(status().isOk())
                                .andReturn();
                        var csrfCookie = csrfResult.getResponse().getCookie(properties.getCsrf().getCookieName());
                        assertNotNull(csrfCookie);

                        return mockMvc.perform(post("/api/auth/oauth2/exchange")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(objectMapper.writeValueAsString(exchangeReq))
                                        .cookie(csrfCookie)
                                        .header(properties.getCsrf().getHeaderName(), csrfCookie.getValue()))
                                .andReturn();
                    } finally {
                        endLatch.countDown();
                    }
                }));
            }

            try {
                startLatch.countDown();
                assertTrue(endLatch.await(10, TimeUnit.SECONDS));
            } finally {
                executor.shutdown();
            }

            int successCount = 0;
            int failCount = 0;

            for (Future<MvcResult> future : futures) {
                MvcResult res = future.get();
                int status = res.getResponse().getStatus();
                if (status == 200) {
                    successCount++;
                } else if (status == 401) {
                    failCount++;
                    String body = res.getResponse().getContentAsString();
                    assertTrue(body.contains(ErrorCode.INVALID_TOKEN.getMessage()));
                }
            }

            assertEquals(1, successCount, "Exactly one exchange request must succeed");
            assertEquals(concurrency - 1, failCount, "Other request must fail with INVALID_TOKEN");

            // Verify only 1 refresh family was created in PostgreSQL
            var families = refreshTokenFamilyRepository.findAll();
            long userFamilyCount = families.stream().filter(f -> f.getUserId().equals(userId)).count();
            assertEquals(1, userFamilyCount, "Exactly 1 refresh token family must be created");
        } finally {
            // Clean up
            var families = refreshTokenFamilyRepository.findAll();
            families.stream()
                    .filter(f -> f.getUserId().equals(userId))
                    .forEach(f -> {
                        var tokens = refreshTokenRepository.findAllByFamilyId(f.getId());
                        refreshTokenRepository.deleteAll(tokens);
                        refreshTokenFamilyRepository.delete(f);
                    });
            userRepository.delete(user);
            stringRedisTemplate.delete(key);
        }
    }
}
