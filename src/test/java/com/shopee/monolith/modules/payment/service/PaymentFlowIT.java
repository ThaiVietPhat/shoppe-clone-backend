package com.shopee.monolith.modules.payment.service;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.inventory.repository.InventoryRepository;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.request.CheckoutRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.shopee.monolith.modules.order.entity.CheckoutSession;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.model.CheckoutSessionStatus;
import com.shopee.monolith.modules.order.model.InventoryReservationStatus;
import com.shopee.monolith.modules.order.model.OrderPaymentStatus;
import com.shopee.monolith.modules.order.model.OrderStatus;
import com.shopee.monolith.modules.order.repository.CheckoutSessionRepository;
import com.shopee.monolith.modules.order.repository.InventoryReservationRepository;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import com.shopee.monolith.modules.order.service.BuyerOrderService;
import com.shopee.monolith.modules.order.service.CheckoutTimeoutService;
import com.shopee.monolith.modules.order.service.OrderService;
import com.shopee.monolith.modules.payment.dto.request.InitiatePaymentRequest;
import com.shopee.monolith.modules.payment.dto.response.PaymentStatusResponse;
import com.shopee.monolith.modules.payment.entity.PaymentAttempt;
import com.shopee.monolith.modules.payment.model.PaymentAttemptStatus;
import com.shopee.monolith.modules.payment.model.PaymentMethod;
import com.shopee.monolith.modules.payment.repository.PaymentAttemptRepository;
import com.shopee.monolith.modules.payment.repository.PaymentWebhookEventRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "app.checkout.mock-shipping.flat-fee-per-shop=0")
class PaymentFlowIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private BuyerOrderService buyerOrderService;
    @Autowired
    private CheckoutTimeoutService checkoutTimeoutService;
    @Autowired
    private CartService cartService;
    @Autowired
    private PaymentService paymentService;
    @Autowired
    private VNPayWebhookService webhookService;
    @Autowired
    private VNPaySignatureVerifier signatureVerifier;
    @Autowired
    private PaymentTimeoutProcessor paymentTimeoutProcessor;
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
    private PaymentAttemptRepository paymentAttemptRepository;
    @Autowired
    private PaymentWebhookEventRepository paymentWebhookEventRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private User buyer;
    private User seller;
    private ProductVariant variant;
    private Address defaultAddress;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = userRepository.save(User.builder()
                .email("buyer.payment.it@shoppe.local")
                .normalizedEmail("buyer.payment.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());

        defaultAddress = addressRepository.save(Address.builder()
                .userId(buyer.getId())
                .recipientName("Payment Buyer")
                .phone("0987654321")
                .addressLine("123 Payment St")
                .wardCode("WARD-1").wardName("Ward 1")
                .districtCode("DIST-1").districtName("District 1")
                .provinceCode("PROV-1").provinceName("Province 1")
                .isDefault(true)
                .build());

        seller = userRepository.save(User.builder()
                .email("seller.payment.it@shoppe.local")
                .normalizedEmail("seller.payment.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build());

        Shop shop = shopRepository.save(Shop.builder()
                .ownerId(seller.getId())
                .name("Payment Shop")
                .build());

        Category category = categoryRepository.save(Category.builder().name("Payment Category").build());

        Product product = productRepository.save(Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Payment Product")
                .status(ProductStatus.ACTIVE)
                .build());

        variant = productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId())
                .sku("PAY-IT-V1")
                .name("V1")
                .price(BigDecimal.valueOf(10.00))
                .build());

        inventoryService.createInventory(variant.getId(), 10, seller.getId(), seller.getRole());
    }

    @AfterEach
    void tearDown() {
        if (buyer != null) {
            cartService.clearCart(buyer.getId());
        }
        paymentWebhookEventRepository.deleteAll();
        paymentAttemptRepository.deleteAll();
        inventoryReservationRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        checkoutSessionRepository.deleteAll();
        addressRepository.deleteAll();
        inventoryRepository.deleteAll();
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        shopRepository.deleteAll();
        userRepository.deleteAll();
    }

    private CheckoutResponse checkout(int quantity) {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant.getId(), quantity));
        cartService.selectItems(buyer.getId(), List.of(variant.getId()));
        return orderService.checkout(buyer.getId(),
                CheckoutRequest.builder().addressId(defaultAddress.getId()).build(),
                UUID.randomUUID().toString());
    }

    private Map<String, String> signedWebhookParams(PaymentAttempt attempt, String responseCode) {
        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", attempt.getId().toString());
        params.put("vnp_Amount", attempt.getAmount().multiply(BigDecimal.valueOf(100)).toBigInteger().toString());
        params.put("vnp_ResponseCode", responseCode);
        params.put("vnp_TransactionNo", "11112222");
        params.put("vnp_SecureHash", signatureVerifier.sign(params));
        return params;
    }

    private PaymentAttempt latestAttempt(UUID sessionId) {
        return paymentAttemptRepository.findAllByCheckoutSessionIdOrderByCreatedAtDesc(sessionId).get(0);
    }

    @Test
    void codPaymentShouldConfirmInventoryAndOrderWithoutCollectingCashYet() {
        CheckoutResponse checkout = checkout(2);

        PaymentStatusResponse response = paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.COD));

        assertEquals(PaymentAttemptStatus.PENDING_COD.name(), response.status());

        CheckoutSession session = checkoutSessionRepository.findById(checkout.checkoutSessionId()).orElseThrow();
        assertEquals(CheckoutSessionStatus.COMPLETED, session.getStatus());

        // COD confirms inventory/fulfillment immediately but must NOT settle as paid until the
        // carrier collects cash on delivery (Order#deliver) — see DESIGN.md §8 COD contract.
        Order order = orderRepository.findById(checkout.orderIds().get(0)).orElseThrow();
        assertEquals(OrderStatus.CONFIRMED, order.getStatus());
        assertEquals(OrderPaymentStatus.UNPAID, order.getPaymentStatus());
        assertEquals("COD", order.getPaymentMethod());

        assertTrue(inventoryReservationRepository.findAll().stream()
                .allMatch(r -> r.getStatus() == InventoryReservationStatus.CONFIRMED));

        var inventory = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(8, inventory.getAvailableStock());
        assertEquals(0, inventory.getReservedStock());
    }

    @Test
    void vnpayWebhookSuccessShouldConfirmAndDuplicateWebhookShouldNoOp() {
        CheckoutResponse checkout = checkout(2);
        PaymentStatusResponse initiated = paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));
        assertEquals(PaymentAttemptStatus.PENDING.name(), initiated.status());
        assertNotNull(initiated.nextAction());

        PaymentAttempt attempt = latestAttempt(checkout.checkoutSessionId());
        Map<String, String> params = signedWebhookParams(attempt, "00");

        assertEquals(VNPayWebhookService.WebhookResult.PROCESSED, webhookService.processWebhook(params));

        attempt = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.SUCCEEDED, attempt.getStatus());
        assertEquals(CheckoutSessionStatus.COMPLETED,
                checkoutSessionRepository.findById(checkout.checkoutSessionId()).orElseThrow().getStatus());

        var inventoryAfterFirst = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(8, inventoryAfterFirst.getAvailableStock());
        assertEquals(0, inventoryAfterFirst.getReservedStock());

        // Duplicate delivery of the same provider event must be a no-op
        assertEquals(VNPayWebhookService.WebhookResult.DUPLICATE, webhookService.processWebhook(params));

        var inventoryAfterDuplicate = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(8, inventoryAfterDuplicate.getAvailableStock());
        assertEquals(0, inventoryAfterDuplicate.getReservedStock());
    }

    @Test
    void vnpayWebhookWhenAmountMismatchShouldRequireReconciliationWithoutConfirming() {
        CheckoutResponse checkout = checkout(2);
        paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));
        PaymentAttempt attempt = latestAttempt(checkout.checkoutSessionId());

        Map<String, String> params = new HashMap<>();
        params.put("vnp_TxnRef", attempt.getId().toString());
        params.put("vnp_Amount", "999999900");
        params.put("vnp_ResponseCode", "00");
        params.put("vnp_TransactionNo", "33334444");
        params.put("vnp_SecureHash", signatureVerifier.sign(params));

        webhookService.processWebhook(params);

        attempt = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.REQUIRES_RECONCILIATION, attempt.getStatus());
        assertEquals("AMOUNT_MISMATCH", attempt.getReconciliationReason());
        assertEquals(CheckoutSessionStatus.PENDING_PAYMENT,
                checkoutSessionRepository.findById(checkout.checkoutSessionId()).orElseThrow().getStatus());
        var inventory = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(2, inventory.getReservedStock());
    }

    @Test
    void vnpayWebhookWhenFailureCodeShouldReleaseInventoryAndCancelOrders() {
        CheckoutResponse checkout = checkout(2);
        paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));
        PaymentAttempt attempt = latestAttempt(checkout.checkoutSessionId());

        webhookService.processWebhook(signedWebhookParams(attempt, "24"));

        attempt = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.FAILED, attempt.getStatus());

        Order order = orderRepository.findById(checkout.orderIds().get(0)).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());
        assertEquals(OrderPaymentStatus.FAILED, order.getPaymentStatus());

        var inventory = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableStock());
        assertEquals(0, inventory.getReservedStock());
    }

    @Test
    void duplicatePaymentInitiateWhenNonTerminalAttemptExistsShouldReturnSameAttempt() {
        CheckoutResponse checkout = checkout(1);
        PaymentStatusResponse first = paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));
        PaymentStatusResponse second = paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));

        assertEquals(first.paymentAttemptId(), second.paymentAttemptId());
        assertEquals(1, paymentAttemptRepository.findAll().size());

        AppException exception = assertThrows(AppException.class, () -> paymentService.initiatePayment(
                buyer.getId(), new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.COD)));
        assertEquals(ErrorCode.PAYMENT_ATTEMPT_IN_PROGRESS, exception.getErrorCode());
    }

    @Test
    void paymentTimeoutShouldExpireAttemptAndReleaseInventory() {
        CheckoutResponse checkout = checkout(2);
        paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));
        PaymentAttempt attempt = latestAttempt(checkout.checkoutSessionId());

        forceAttemptExpiry(attempt.getId());
        paymentTimeoutProcessor.processTimeout(attempt.getId(), Instant.now());

        attempt = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.EXPIRED, attempt.getStatus());
        assertEquals(CheckoutSessionStatus.PAYMENT_EXPIRED,
                checkoutSessionRepository.findById(checkout.checkoutSessionId()).orElseThrow().getStatus());

        var inventory = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableStock());
        assertEquals(0, inventory.getReservedStock());
    }

    @Test
    void timeoutVsSuccessRaceOnlyOneTransitionShouldWinAndInventoryStaysConsistent() throws Exception {
        CheckoutResponse checkout = checkout(2);
        paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));
        PaymentAttempt attempt = latestAttempt(checkout.checkoutSessionId());
        forceAttemptExpiry(attempt.getId());

        Map<String, String> successParams = signedWebhookParams(attempt, "00");
        UUID attemptId = attempt.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var timeoutFuture = executor.submit(() -> {
                start.await();
                paymentTimeoutProcessor.processTimeout(attemptId, Instant.now());
                return null;
            });
            var webhookFuture = executor.submit(() -> {
                start.await();
                try {
                    webhookService.processWebhook(successParams);
                } catch (Exception e) {
                    // The losing side may observe a terminal/locked attempt — acceptable
                }
                return null;
            });
            start.countDown();
            timeoutFuture.get(30, TimeUnit.SECONDS);
            webhookFuture.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }

        PaymentAttempt finalAttempt = paymentAttemptRepository.findById(attemptId).orElseThrow();
        CheckoutSession session = checkoutSessionRepository.findById(checkout.checkoutSessionId()).orElseThrow();
        var inventory = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();

        assertEquals(0, inventory.getReservedStock());
        if (finalAttempt.getStatus() == PaymentAttemptStatus.SUCCEEDED) {
            assertEquals(CheckoutSessionStatus.COMPLETED, session.getStatus());
            assertEquals(8, inventory.getAvailableStock());
        } else {
            // Timeout won; a late success webhook must never re-confirm released stock
            assertTrue(finalAttempt.getStatus() == PaymentAttemptStatus.EXPIRED
                    || finalAttempt.getStatus() == PaymentAttemptStatus.REQUIRES_RECONCILIATION);
            assertEquals(CheckoutSessionStatus.PAYMENT_EXPIRED, session.getStatus());
            assertEquals(10, inventory.getAvailableStock());
        }
    }

    @Test
    void cancelledOrderShouldNotBeRevivedByLaterVNPaySuccessWebhook() {
        CheckoutResponse checkout = checkout(2);
        paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));
        PaymentAttempt attempt = latestAttempt(checkout.checkoutSessionId());

        // Buyer cancels order before payment completes
        buyerOrderService.cancelOrder(buyer.getId(), checkout.orderIds().get(0));

        // After cancel, payment attempt must be EXPIRED so getPaymentStatus no longer returns PENDING + URL
        attempt = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.EXPIRED, attempt.getStatus());

        // Success webhook arrives after cancel (late success after EXPIRED → REQUIRES_RECONCILIATION)
        webhookService.processWebhook(signedWebhookParams(attempt, "00"));

        // Session must be CANCELLED (not COMPLETED)
        CheckoutSession session = checkoutSessionRepository.findById(checkout.checkoutSessionId()).orElseThrow();
        assertEquals(CheckoutSessionStatus.CANCELLED, session.getStatus());

        // Order must remain CANCELLED (not revived to PAID)
        Order order = orderRepository.findById(checkout.orderIds().get(0)).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());

        // Inventory must be fully released (reserved by checkout, released by cancel)
        var inventory = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableStock());
        assertEquals(0, inventory.getReservedStock());

        // Attempt flagged for reconciliation since session was not payable
        attempt = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.REQUIRES_RECONCILIATION, attempt.getStatus());
    }

    @Test
    void lateVNPaySuccessAfterTimeoutShouldFlagReconciliationWithoutInventoryChange() {
        CheckoutResponse checkout = checkout(2);
        paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));
        PaymentAttempt attempt = latestAttempt(checkout.checkoutSessionId());

        // Force timeout and process it
        forceAttemptExpiry(attempt.getId());
        paymentTimeoutProcessor.processTimeout(attempt.getId(), Instant.now());

        attempt = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.EXPIRED, attempt.getStatus());

        // Inventory already released by timeout
        var inventoryAfterTimeout = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(10, inventoryAfterTimeout.getAvailableStock());
        assertEquals(0, inventoryAfterTimeout.getReservedStock());

        // Late success webhook arrives
        webhookService.processWebhook(signedWebhookParams(attempt, "00"));

        // Attempt must be REQUIRES_RECONCILIATION, not SUCCEEDED
        attempt = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.REQUIRES_RECONCILIATION, attempt.getStatus());
        assertTrue(attempt.getReconciliationReason().startsWith("LATE_SUCCESS_AFTER_"));

        // Inventory must NOT be re-confirmed
        var inventoryAfterLateSuccess = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(10, inventoryAfterLateSuccess.getAvailableStock());
        assertEquals(0, inventoryAfterLateSuccess.getReservedStock());
    }

    @Test
    void checkoutTimeoutShouldExpirePendingPaymentAttemptSoGetStatusNoLongerReturnsPendingUrl() {
        CheckoutResponse checkout = checkout(2);
        paymentService.initiatePayment(buyer.getId(),
                new InitiatePaymentRequest(checkout.checkoutSessionId(), PaymentMethod.VNPAY));
        PaymentAttempt attempt = latestAttempt(checkout.checkoutSessionId());
        assertEquals(PaymentAttemptStatus.PENDING, attempt.getStatus());
        assertNotNull(attempt.getExpiresAt());

        // Force checkout session to be past its expiry
        forceCheckoutSessionExpiry(checkout.checkoutSessionId());

        // Checkout timeout job processes expired sessions
        checkoutTimeoutService.processExpiredCheckouts(10);

        // Session must be EXPIRED (checkout timeout uses EXPIRED; payment timeout uses PAYMENT_EXPIRED)
        var session = checkoutSessionRepository.findById(checkout.checkoutSessionId()).orElseThrow();
        assertEquals(CheckoutSessionStatus.EXPIRED, session.getStatus());

        // Payment attempt must be EXPIRED — getPaymentStatus should no longer return PENDING + VNPay URL
        attempt = paymentAttemptRepository.findById(attempt.getId()).orElseThrow();
        assertEquals(PaymentAttemptStatus.EXPIRED, attempt.getStatus());

        // Inventory fully released
        var inventory = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableStock());
        assertEquals(0, inventory.getReservedStock());
    }

    private void forceAttemptExpiry(UUID attemptId) {
        jdbcTemplate.update("UPDATE payment_attempts SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), attemptId);
    }

    private void forceCheckoutSessionExpiry(UUID sessionId) {
        jdbcTemplate.update("UPDATE checkout_sessions SET expires_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(60)), sessionId);
    }
}
