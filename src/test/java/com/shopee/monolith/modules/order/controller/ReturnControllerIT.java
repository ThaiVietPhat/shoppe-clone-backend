package com.shopee.monolith.modules.order.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.modules.auth.security.JwtTokenProvider;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.inventory.entity.Inventory;
import com.shopee.monolith.modules.inventory.repository.InventoryMovementRepository;
import com.shopee.monolith.modules.inventory.repository.InventoryRepository;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.request.CheckoutRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.shopee.monolith.modules.order.repository.CheckoutSessionRepository;
import com.shopee.monolith.modules.order.repository.InventoryReservationRepository;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import com.shopee.monolith.modules.order.repository.ReturnEvidenceRepository;
import com.shopee.monolith.modules.order.repository.ReturnRepository;
import com.shopee.monolith.modules.order.service.CheckoutSettlementService;
import com.shopee.monolith.modules.order.service.OrderService;
import com.shopee.monolith.modules.order.service.SellerOrderService;
import com.shopee.monolith.modules.product.entity.Category;
import com.shopee.monolith.modules.product.entity.Product;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.entity.ProductVariant;
import com.shopee.monolith.modules.product.repository.CategoryRepository;
import com.shopee.monolith.modules.product.repository.ProductRepository;
import com.shopee.monolith.modules.product.repository.ProductVariantRepository;
import com.shopee.monolith.modules.user.entity.Address;
import com.shopee.monolith.modules.user.entity.Shop;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.AddressRepository;
import com.shopee.monolith.modules.user.repository.ShopRepository;
import com.shopee.monolith.modules.user.repository.UserRepository;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@AutoConfigureMockMvc
@TestPropertySource(properties = "app.checkout.mock-shipping.flat-fee-per-shop=0")
class ReturnControllerIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;
    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OrderService orderService;
    @Autowired
    private SellerOrderService sellerOrderService;
    @Autowired
    private CheckoutSettlementService checkoutSettlementService;
    @Autowired
    private CartService cartService;
    @Autowired
    private InventoryService inventoryService;

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
    private CheckoutSessionRepository checkoutSessionRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderItemRepository orderItemRepository;
    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;
    @Autowired
    private AddressRepository addressRepository;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private InventoryMovementRepository inventoryMovementRepository;
    @Autowired
    private ReturnRepository returnRepository;
    @Autowired
    private ReturnEvidenceRepository returnEvidenceRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private WalletTransactionRepository walletTransactionRepository;
    @Autowired
    private PayoutRequestRepository payoutRequestRepository;

    private User buyer;
    private User seller;
    private User otherSeller;
    private Shop shop;
    private ProductVariant variant;
    private Address defaultAddress;
    private Category category;
    private String buyerToken;
    private String sellerToken;
    private String otherSellerToken;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = userRepository.save(User.builder()
                .email("buyer.return.it@shoppe.local").normalizedEmail("buyer.return.it@shoppe.local")
                .role(Role.BUYER).status(UserStatus.ACTIVE).build());
        buyerToken = jwtTokenProvider.generateAccessToken(buyer.getId(), buyer.getRole());

        defaultAddress = addressRepository.save(Address.builder()
                .userId(buyer.getId())
                .recipientName("Return IT Buyer")
                .phone("0987654321")
                .addressLine("123 Return St")
                .wardCode("WARD-1").wardName("Ward 1")
                .districtCode("DIST-1").districtName("District 1")
                .provinceCode("PROV-1").provinceName("Province 1")
                .isDefault(true)
                .build());

        seller = userRepository.save(User.builder()
                .email("seller.return.it@shoppe.local").normalizedEmail("seller.return.it@shoppe.local")
                .role(Role.SELLER).status(UserStatus.ACTIVE).build());
        sellerToken = jwtTokenProvider.generateAccessToken(seller.getId(), seller.getRole());

        otherSeller = userRepository.save(User.builder()
                .email("other.return.it@shoppe.local").normalizedEmail("other.return.it@shoppe.local")
                .role(Role.SELLER).status(UserStatus.ACTIVE).build());
        otherSellerToken = jwtTokenProvider.generateAccessToken(otherSeller.getId(), otherSeller.getRole());

        shop = shopRepository.save(Shop.builder().ownerId(seller.getId()).name("Return IT Shop").build());
        shopRepository.save(Shop.builder().ownerId(otherSeller.getId()).name("Other Return Shop").build());

        category = categoryRepository.save(Category.builder().name("Return IT Category").build());

        Product product = productRepository.save(Product.builder()
                .shopId(shop.getId()).categoryId(category.getId())
                .name("Return IT Product").status(ProductStatus.ACTIVE).build());

        variant = productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId()).sku("RET-IT-V1").name("V1")
                .price(BigDecimal.valueOf(50000)).build());

        inventoryService.createInventory(variant.getId(), 10, seller.getId(), seller.getRole());
    }

    @AfterEach
    void tearDown() {
        if (buyer != null) {
            cartService.clearCart(buyer.getId());
        }
        returnEvidenceRepository.deleteAll();
        returnRepository.deleteAll();
        payoutRequestRepository.deleteAll();
        walletTransactionRepository.deleteAll();
        walletRepository.deleteAll();
        inventoryReservationRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        checkoutSessionRepository.deleteAll();
        addressRepository.deleteAll();
        inventoryMovementRepository.deleteAll();
        inventoryRepository.deleteAll();
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        if (category != null) {
            categoryRepository.delete(category);
        }
        shopRepository.deleteAll();
        userRepository.deleteAll();
    }

    private UUID deliveredOrderId(int quantity) {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant.getId(), quantity));
        cartService.selectItems(buyer.getId(), List.of(variant.getId()));
        CheckoutResponse checkout = orderService.checkout(buyer.getId(),
                CheckoutRequest.builder().addressId(defaultAddress.getId()).build(),
                UUID.randomUUID().toString());
        checkoutSettlementService.confirmCheckoutSession(checkout.checkoutSessionId(), "COD");
        UUID orderId = checkout.orderIds().get(0);
        sellerOrderService.shipOrder(seller.getId(), orderId);
        sellerOrderService.deliverOrder(seller.getId(), orderId);
        return orderId;
    }

    @Test
    void requestReturnWhenDeliveredShouldReturn200() throws Exception {
        UUID orderId = deliveredOrderId(2);

        mockMvc.perform(post("/api/buyer/orders/" + orderId + "/return")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCategory\": \"DEFECTIVE\", \"description\": \"broken on arrival\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REQUESTED"));
    }

    @Test
    void requestReturnWhenNotDeliveredShouldReturn409() throws Exception {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant.getId(), 1));
        cartService.selectItems(buyer.getId(), List.of(variant.getId()));
        CheckoutResponse checkout = orderService.checkout(buyer.getId(),
                CheckoutRequest.builder().addressId(defaultAddress.getId()).build(),
                UUID.randomUUID().toString());
        checkoutSettlementService.confirmCheckoutSession(checkout.checkoutSessionId(), "COD");
        UUID orderId = checkout.orderIds().get(0);

        mockMvc.perform(post("/api/buyer/orders/" + orderId + "/return")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCategory\": \"DEFECTIVE\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void approveReturnShouldRefundBuyerClawBackSellerAndRestockInventory() throws Exception {
        UUID orderId = deliveredOrderId(2);
        int availableBeforeReturn = inventoryRepository.findByVariantId(variant.getId()).orElseThrow().getAvailableStock();

        mockMvc.perform(post("/api/buyer/orders/" + orderId + "/return")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCategory\": \"DEFECTIVE\", \"description\": \"broken\"}"))
                .andExpect(status().isOk());

        String listBody = mockMvc.perform(get("/api/seller/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID returnId = UUID.fromString(objectMapper.readTree(listBody).at("/data/items/0/id").asText());

        mockMvc.perform(post("/api/seller/returns/" + returnId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNote\": \"confirmed defective\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        // Seller earned itemsSubtotal at delivery, then had it clawed back on approval — net zero.
        var sellerWallet = walletRepository.findByUserId(seller.getId()).orElseThrow();
        assertEquals(0, sellerWallet.getBalance().compareTo(BigDecimal.ZERO));

        var buyerWallet = walletRepository.findByUserId(buyer.getId()).orElseThrow();
        assertEquals(0, buyerWallet.getBalance().compareTo(new BigDecimal("100000.00")));

        Inventory inventory = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(availableBeforeReturn + 2, inventory.getAvailableStock());
    }

    @Test
    void rejectReturnShouldNotRefundOrRestock() throws Exception {
        UUID orderId = deliveredOrderId(1);

        mockMvc.perform(post("/api/buyer/orders/" + orderId + "/return")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCategory\": \"CHANGED_MIND\"}"))
                .andExpect(status().isOk());

        String listBody = mockMvc.perform(get("/api/seller/returns")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID returnId = UUID.fromString(objectMapper.readTree(listBody).at("/data/items/0/id").asText());

        mockMvc.perform(post("/api/seller/returns/" + returnId + "/reject")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + sellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolutionNote\": \"outside policy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        var buyerWallet = walletRepository.findByUserId(buyer.getId());
        assertEquals(true, buyerWallet.isEmpty());
    }

    @Test
    void approveReturnWhenForeignSellerShouldReturn404() throws Exception {
        UUID orderId = deliveredOrderId(1);

        mockMvc.perform(post("/api/buyer/orders/" + orderId + "/return")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCategory\": \"DEFECTIVE\"}"))
                .andExpect(status().isOk());

        UUID fakeReturnId = returnRepository.findByOrderId(orderId).orElseThrow().getId();

        mockMvc.perform(post("/api/seller/returns/" + fakeReturnId + "/approve")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + otherSellerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }
}
