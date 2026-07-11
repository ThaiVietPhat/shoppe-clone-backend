package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.modules.inventory.dto.response.InventoryResponse;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.entity.CheckoutSession;
import com.shopee.monolith.modules.order.entity.InventoryReservation;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.entity.OrderItem;
import com.shopee.monolith.modules.order.model.CheckoutSessionStatus;
import com.shopee.monolith.modules.order.model.InventoryReservationStatus;
import com.shopee.monolith.modules.order.model.OrderStatus;
import com.shopee.monolith.modules.order.repository.CheckoutSessionRepository;
import com.shopee.monolith.modules.order.repository.InventoryReservationRepository;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckoutTimeoutIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private CheckoutTimeoutService checkoutTimeoutService;

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
    private com.shopee.monolith.modules.inventory.repository.InventoryRepository inventoryRepository;

    private User buyer;
    private User seller;
    private Shop shop;
    private ProductVariant variant;
    private Category category;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = User.builder()
                .email("buyer.timeout.it@shoppe.local")
                .normalizedEmail("buyer.timeout.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        buyer = userRepository.save(buyer);

        seller = User.builder()
                .email("seller.timeout.it@shoppe.local")
                .normalizedEmail("seller.timeout.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build();
        seller = userRepository.save(seller);

        shop = Shop.builder()
                .ownerId(seller.getId())
                .name("Timeout Test Shop")
                .build();
        shop = shopRepository.save(shop);

        category = Category.builder()
                .name("Timeout Category")
                .build();
        category = categoryRepository.save(category);

        Product product = Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Timeout Product")
                .status(ProductStatus.ACTIVE)
                .build();
        product = productRepository.save(product);

        variant = ProductVariant.builder()
                .productId(product.getId())
                .sku("ORD-TO-V1")
                .name("V1")
                .price(BigDecimal.valueOf(10.00))
                .build();
        variant = productVariantRepository.save(variant);

        inventoryService.createInventory(variant.getId(), 10, seller.getId(), seller.getRole());
    }

    @AfterEach
    void tearDown() {
        inventoryReservationRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        checkoutSessionRepository.deleteAll();
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
    void processExpiredCheckoutsShouldReleaseInventoryAndCancelExpiredOrders() {
        // 1. Manually insert an expired checkout session with reservation
        CheckoutSession session = CheckoutSession.builder()
                .buyerId(buyer.getId())
                .status(CheckoutSessionStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(20.00))
                .shippingRecipientName("John Buyer")
                .shippingPhone("0987654321")
                .shippingAddressLine("123 Street")
                .shippingWardCode("W1")
                .shippingWardName("Ward 1")
                .shippingDistrictCode("D1")
                .shippingDistrictName("District 1")
                .shippingProvinceCode("P1")
                .shippingProvinceName("Province 1")
                .expiresAt(Instant.now().minusSeconds(5)) // Expired
                .build();
        session = checkoutSessionRepository.save(session);

        Order order = Order.builder()
                .buyerId(buyer.getId())
                .shopId(shop.getId())
                .checkoutSessionId(session.getId())
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(20.00))
                .shippingRecipientName("John Buyer")
                .shippingPhone("0987654321")
                .shippingAddressLine("123 Street")
                .shippingWardCode("W1")
                .shippingWardName("Ward 1")
                .shippingDistrictCode("D1")
                .shippingDistrictName("District 1")
                .shippingProvinceCode("P1")
                .shippingProvinceName("Province 1")
                .build();
        order = orderRepository.save(order);

        OrderItem orderItem = OrderItem.builder()
                .orderId(order.getId())
                .variantId(variant.getId())
                .productName("Timeout Product")
                .variantName("V1")
                .sku("ORD-TO-V1")
                .price(BigDecimal.valueOf(10.00))
                .quantity(2)
                .subtotal(BigDecimal.valueOf(20.00))
                .build();
        orderItemRepository.save(orderItem);

        InventoryReservation reservation = InventoryReservation.builder()
                .checkoutSessionId(session.getId())
                .orderId(order.getId())
                .variantId(variant.getId())
                .quantity(2)
                .status(InventoryReservationStatus.RESERVED)
                .expiresAt(session.getExpiresAt())
                .build();
        inventoryReservationRepository.save(reservation);

        // Manually adjust inventory stock values to mimic reservation
        adjustInventoryStock(variant.getId(), 8, 2);

        // Verify pre-timeout inventory status
        InventoryResponse preInv = inventoryService.getInventoryByVariantId(variant.getId(), seller.getId(), seller.getRole());
        assertEquals(8, preInv.availableStock());
        assertEquals(2, preInv.reservedStock());

        // 2. Trigger timeout processing
        checkoutTimeoutService.processExpiredCheckouts(10);

        // 3. Verify outcomes
        CheckoutSession updatedSession = checkoutSessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(CheckoutSessionStatus.EXPIRED, updatedSession.getStatus());

        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, updatedOrder.getStatus());

        InventoryReservation updatedRes = inventoryReservationRepository.findById(reservation.getId()).orElseThrow();
        assertEquals(InventoryReservationStatus.RELEASED, updatedRes.getStatus());

        // Verify inventory stock has reverted
        InventoryResponse postInv = inventoryService.getInventoryByVariantId(variant.getId(), seller.getId(), seller.getRole());
        assertEquals(10, postInv.availableStock());
        assertEquals(0, postInv.reservedStock());
    }

    @Test
    void processExpiredCheckoutsShouldNotAffectNonExpiredCheckouts() {
        CheckoutSession session = CheckoutSession.builder()
                .buyerId(buyer.getId())
                .status(CheckoutSessionStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(20.00))
                .shippingRecipientName("John Buyer")
                .shippingPhone("0987654321")
                .shippingAddressLine("123 Street")
                .shippingWardCode("W1")
                .shippingWardName("Ward 1")
                .shippingDistrictCode("D1")
                .shippingDistrictName("District 1")
                .shippingProvinceCode("P1")
                .shippingProvinceName("Province 1")
                .expiresAt(Instant.now().plusSeconds(60)) // Active / Not expired
                .build();
        session = checkoutSessionRepository.save(session);

        Order order = Order.builder()
                .buyerId(buyer.getId())
                .shopId(shop.getId())
                .checkoutSessionId(session.getId())
                .status(OrderStatus.PENDING_PAYMENT)
                .totalAmount(BigDecimal.valueOf(20.00))
                .shippingRecipientName("John Buyer")
                .shippingPhone("0987654321")
                .shippingAddressLine("123 Street")
                .shippingWardCode("W1")
                .shippingWardName("Ward 1")
                .shippingDistrictCode("D1")
                .shippingDistrictName("District 1")
                .shippingProvinceCode("P1")
                .shippingProvinceName("Province 1")
                .build();
        order = orderRepository.save(order);

        InventoryReservation reservation = InventoryReservation.builder()
                .checkoutSessionId(session.getId())
                .orderId(order.getId())
                .variantId(variant.getId())
                .quantity(2)
                .status(InventoryReservationStatus.RESERVED)
                .expiresAt(session.getExpiresAt())
                .build();
        inventoryReservationRepository.save(reservation);

        adjustInventoryStock(variant.getId(), 8, 2);

        // Process timeout
        checkoutTimeoutService.processExpiredCheckouts(10);

        // Verify status remains unchanged
        CheckoutSession updatedSession = checkoutSessionRepository.findById(session.getId()).orElseThrow();
        assertEquals(CheckoutSessionStatus.PENDING_PAYMENT, updatedSession.getStatus());

        Order updatedOrder = orderRepository.findById(order.getId()).orElseThrow();
        assertEquals(OrderStatus.PENDING_PAYMENT, updatedOrder.getStatus());

        InventoryReservation updatedRes = inventoryReservationRepository.findById(reservation.getId()).orElseThrow();
        assertEquals(InventoryReservationStatus.RESERVED, updatedRes.getStatus());

        InventoryResponse postInv = inventoryService.getInventoryByVariantId(variant.getId(), seller.getId(), seller.getRole());
        assertEquals(8, postInv.availableStock());
        assertEquals(2, postInv.reservedStock());
    }

    @Test
    void processExpiredCheckoutsConcurrencyTestShouldNotDoubleRelease() throws InterruptedException {
        // Create 5 expired checkout sessions
        List<CheckoutSession> sessions = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            CheckoutSession session = CheckoutSession.builder()
                    .buyerId(buyer.getId())
                    .status(CheckoutSessionStatus.PENDING_PAYMENT)
                    .totalAmount(BigDecimal.valueOf(10.00))
                    .shippingRecipientName("John Buyer")
                    .shippingPhone("0987654321")
                    .shippingAddressLine("123 Street")
                    .shippingWardCode("W1")
                    .shippingWardName("Ward 1")
                    .shippingDistrictCode("D1")
                    .shippingDistrictName("District 1")
                    .shippingProvinceCode("P1")
                    .shippingProvinceName("Province 1")
                    .expiresAt(Instant.now().minusSeconds(10))
                    .build();
            session = checkoutSessionRepository.save(session);

            Order order = Order.builder()
                    .buyerId(buyer.getId())
                    .shopId(shop.getId())
                    .checkoutSessionId(session.getId())
                    .status(OrderStatus.PENDING_PAYMENT)
                    .totalAmount(BigDecimal.valueOf(10.00))
                    .shippingRecipientName("John Buyer")
                    .shippingPhone("0987654321")
                    .shippingAddressLine("123 Street")
                    .shippingWardCode("W1")
                    .shippingWardName("Ward 1")
                    .shippingDistrictCode("D1")
                    .shippingDistrictName("District 1")
                    .shippingProvinceCode("P1")
                    .shippingProvinceName("Province 1")
                    .build();
            order = orderRepository.save(order);

            InventoryReservation reservation = InventoryReservation.builder()
                    .checkoutSessionId(session.getId())
                    .orderId(order.getId())
                    .variantId(variant.getId())
                    .quantity(1)
                    .status(InventoryReservationStatus.RESERVED)
                    .expiresAt(session.getExpiresAt())
                    .build();
            inventoryReservationRepository.save(reservation);

            sessions.add(session);
        }

        // Available: 5, Reserved: 5 (representing 5 reserves)
        adjustInventoryStock(variant.getId(), 5, 5);

        // Execute concurrent processors using thread pool
        int threadCount = 4;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    startLatch.await();
                    // Each thread scans and processes expired checkouts concurrently
                    checkoutTimeoutService.processExpiredCheckouts(10);
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // Fire all threads simultaneously
        try {
            startLatch.countDown();
            assertTrue(doneLatch.await(10, java.util.concurrent.TimeUnit.SECONDS), "Concurrency test timed out!");
        } finally {
            executor.shutdown();
            if (!executor.isTerminated()) {
                executor.shutdownNow();
            }
        }

        // Verify that all 5 sessions were processed correctly and stock is returned exactly 5 times (not more)
        for (CheckoutSession s : sessions) {
            CheckoutSession updated = checkoutSessionRepository.findById(s.getId()).orElseThrow();
            assertEquals(CheckoutSessionStatus.EXPIRED, updated.getStatus());
        }

        // Stock must be fully returned back to 10 available, 0 reserved
        InventoryResponse inv = inventoryService.getInventoryByVariantId(variant.getId(), seller.getId(), seller.getRole());
        assertEquals(10, inv.availableStock());
        assertEquals(0, inv.reservedStock());
    }

    private void adjustInventoryStock(UUID variantId, int available, int reserved) {
        var inv = inventoryRepository.findByVariantId(variantId).orElseThrow();
        var updated = com.shopee.monolith.modules.inventory.entity.Inventory.builder()
                .id(inv.getId())
                .variantId(inv.getVariantId())
                .availableStock(available)
                .reservedStock(reserved)
                .createdAt(inv.getCreatedAt())
                .updatedAt(Instant.now())
                .build();
        inventoryRepository.save(updated);
    }
}
