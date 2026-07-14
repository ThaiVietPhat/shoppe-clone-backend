package com.shopee.monolith.modules.wallet.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.modules.auth.security.JwtTokenProvider;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.UserRepository;
import com.shopee.monolith.modules.wallet.entity.Wallet;
import com.shopee.monolith.modules.wallet.repository.PayoutRequestRepository;
import com.shopee.monolith.modules.wallet.repository.WalletRepository;
import com.shopee.monolith.modules.wallet.repository.WalletTransactionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
class WalletControllerIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private WalletTransactionRepository walletTransactionRepository;
    @Autowired
    private PayoutRequestRepository payoutRequestRepository;
    @Autowired
    private ObjectMapper objectMapper;

    private User buyer;
    private User seller;
    private String buyerToken;
    private String sellerToken;

    @BeforeEach
    void setUp() {
        tearDown();
        buyer = userRepository.save(User.builder()
                .email("buyer-wallet-" + java.util.UUID.randomUUID() + "@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());
        buyerToken = jwtTokenProvider.generateAccessToken(buyer.getId(), buyer.getRole());

        seller = userRepository.save(User.builder()
                .email("seller-wallet-" + java.util.UUID.randomUUID() + "@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build());
        sellerToken = jwtTokenProvider.generateAccessToken(seller.getId(), seller.getRole());
    }

    @AfterEach
    void tearDown() {
        payoutRequestRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletRepository.deleteAll();
        if (buyer != null) {
            userRepository.deleteById(buyer.getId());
        }
        if (seller != null) {
            userRepository.deleteById(seller.getId());
        }
    }

    @Test
    void getWalletWhenNoneExistsShouldAutoCreateZeroBalance() throws Exception {
        mockMvc.perform(get("/api/wallet")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(0));
    }

    @Test
    void getWalletWhenUnauthenticatedShouldReturn401() throws Exception {
        mockMvc.perform(get("/api/wallet"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void withdrawWhenBuyerShouldReturn409ShopOwnerRequired() throws Exception {
        mockMvc.perform(post("/api/wallet/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 10}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void withdrawWhenExceedsBalanceShouldReturn409() throws Exception {
        walletRepository.save(Wallet.builder().userId(seller.getId()).balance(new BigDecimal("50")).build());

        mockMvc.perform(post("/api/wallet/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 100}"))
                .andExpect(status().isConflict());
    }

    @Test
    void withdrawWhenSufficientBalanceShouldCompleteAndDecrementBalance() throws Exception {
        walletRepository.save(Wallet.builder().userId(seller.getId()).balance(new BigDecimal("100")).build());

        mockMvc.perform(post("/api/wallet/withdraw")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": 40}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(60));

        mockMvc.perform(get("/api/wallet/transactions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].type").value("WITHDRAWAL"));
    }
}
