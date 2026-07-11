package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.inventory.dto.response.InventoryResponse;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.request.CheckoutRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.shopee.monolith.modules.order.entity.CheckoutSession;
import com.shopee.monolith.modules.order.entity.InventoryReservation;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.entity.OrderItem;
import com.shopee.monolith.modules.order.model.CheckoutSessionStatus;
import com.shopee.monolith.modules.order.model.InventoryReservationStatus;
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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@org.springframework.test.context.TestPropertySource(properties = "app.checkout.mock-shipping.flat-fee-per-shop=0")
class OrderCheckoutIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private CartService cartService;

    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
    private com.shopee.monolith.modules.inventory.repository.InventoryRepository inventoryRepository;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private com.shopee.monolith.modules.order.repository.IdempotencyKeyRepository idempotencyKeyRepository;

    private User buyer;
    private User seller1;
    private User seller2;
    private Shop shop1;
    private Shop shop2;
    private ProductVariant variant1;
    private ProductVariant variant2;
    private Address defaultAddress;
    private Category category;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = User.builder()
                .email("buyer.checkout.it@shoppe.local")
                .normalizedEmail("buyer.checkout.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        buyer = userRepository.save(buyer);

        defaultAddress = Address.builder()
                .userId(buyer.getId())
                .recipientName("John Buyer")
                .phone("0987654321")
                .addressLine("123 Buyer St")
                .wardCode("WARD-1")
                .wardName("Ward 1")
                .districtCode("DIST-1")
                .districtName("District 1")
                .provinceCode("PROV-1")
                .provinceName("Province 1")
                .isDefault(true)
                .build();
        defaultAddress = addressRepository.save(defaultAddress);

        seller1 = User.builder()
                .email("seller1.checkout.it@shoppe.local")
                .normalizedEmail("seller1.checkout.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build();
        seller1 = userRepository.save(seller1);

        shop1 = Shop.builder()
                .ownerId(seller1.getId())
                .name("Checkout Shop 1")
                .build();
        shop1 = shopRepository.save(shop1);

        seller2 = User.builder()
                .email("seller2.checkout.it@shoppe.local")
                .normalizedEmail("seller2.checkout.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build();
        seller2 = userRepository.save(seller2);

        shop2 = Shop.builder()
                .ownerId(seller2.getId())
                .name("Checkout Shop 2")
                .build();
        shop2 = shopRepository.save(shop2);

        category = Category.builder()
                .name("Checkout Category")
                .build();
        category = categoryRepository.save(category);

        Product product1 = Product.builder()
                .shopId(shop1.getId())
                .categoryId(category.getId())
                .name("Checkout Product 1")
                .status(ProductStatus.ACTIVE)
                .build();
        product1 = productRepository.save(product1);

        Product product2 = Product.builder()
                .shopId(shop2.getId())
                .categoryId(category.getId())
                .name("Checkout Product 2")
                .status(ProductStatus.ACTIVE)
                .build();
        product2 = productRepository.save(product2);

        variant1 = ProductVariant.builder()
                .productId(product1.getId())
                .sku("ORD-CH-V1")
                .name("V1")
                .price(BigDecimal.valueOf(10.00))
                .build();
        variant1 = productVariantRepository.save(variant1);

        variant2 = ProductVariant.builder()
                .productId(product2.getId())
                .sku("ORD-CH-V2")
                .name("V2")
                .price(BigDecimal.valueOf(20.00))
                .build();
        variant2 = productVariantRepository.save(variant2);

        inventoryService.createInventory(variant1.getId(), 10, seller1.getId(), seller1.getRole());
        inventoryService.createInventory(variant2.getId(), 20, seller2.getId(), seller2.getRole());
    }

    @AfterEach
    void tearDown() {
        if (buyer != null) {
            cartService.clearCart(buyer.getId());
        }
        inventoryReservationRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        checkoutSessionRepository.deleteAll();
        addressRepository.deleteAll();
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
    void checkoutShouldCreateEntitiesAndReserveStockCorrectly() {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant1.getId(), 2));
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant2.getId(), 3));
        cartService.selectItems(buyer.getId(), List.of(variant1.getId(), variant2.getId()));

        CheckoutRequest request = CheckoutRequest.builder().addressId(defaultAddress.getId()).build();
        String idempotencyKey = UUID.randomUUID().toString();

        CheckoutResponse response = orderService.checkout(buyer.getId(), request, idempotencyKey);

        assertNotNull(response);
        assertNotNull(response.checkoutSessionId());
        assertEquals(2, response.orderIds().size());
        assertEquals(new BigDecimal("80.00"), response.totalAmount());
        assertEquals("PENDING_PAYMENT", response.status());

        // Verify database records
        CheckoutSession session = checkoutSessionRepository.findById(response.checkoutSessionId()).orElseThrow();
        assertEquals(CheckoutSessionStatus.PENDING_PAYMENT, session.getStatus());
        assertEquals(new BigDecimal("80.00"), session.getTotalAmount());
        assertEquals("John Buyer", session.getShippingRecipientName());
        assertEquals("0987654321", session.getShippingPhone());
        assertEquals("123 Buyer St", session.getShippingAddressLine());

        List<Order> orders = orderRepository.findAll();
        assertEquals(2, orders.size());
        assertTrue(orders.stream().anyMatch(o -> o.getShopId().equals(shop1.getId()) && o.getTotalAmount().compareTo(new BigDecimal("20.00")) == 0));
        assertTrue(orders.stream().anyMatch(o -> o.getShopId().equals(shop2.getId()) && o.getTotalAmount().compareTo(new BigDecimal("60.00")) == 0));

        List<OrderItem> items = orderItemRepository.findAll();
        assertEquals(2, items.size());

        // Verify inventory reservation ledger
        List<InventoryReservation> reservations = inventoryReservationRepository.findAll();
        assertEquals(2, reservations.size());
        assertTrue(reservations.stream().anyMatch(r -> r.getVariantId().equals(variant1.getId()) && r.getQuantity() == 2 && r.getStatus() == InventoryReservationStatus.RESERVED));
        assertTrue(reservations.stream().anyMatch(r -> r.getVariantId().equals(variant2.getId()) && r.getQuantity() == 3 && r.getStatus() == InventoryReservationStatus.RESERVED));

        // Verify inventory stock levels
        InventoryResponse inv1 = inventoryService.getInventoryByVariantId(variant1.getId(), seller1.getId(), seller1.getRole());
        assertEquals(8, inv1.availableStock());
        assertEquals(2, inv1.reservedStock());

        // Verify cart is cleared
        assertTrue(cartService.getCart(buyer.getId()).items().isEmpty());

        // Verify itemsSubtotal and shippingFee snapshots on each order
        assertTrue(orders.stream().anyMatch(o ->
                o.getShopId().equals(shop1.getId())
                        && o.getItemsSubtotal().compareTo(new BigDecimal("20.00")) == 0
                        && o.getShippingFee().compareTo(BigDecimal.ZERO) == 0));
        assertTrue(orders.stream().anyMatch(o ->
                o.getShopId().equals(shop2.getId())
                        && o.getItemsSubtotal().compareTo(new BigDecimal("60.00")) == 0
                        && o.getShippingFee().compareTo(BigDecimal.ZERO) == 0));
    }

    @Test
    void checkoutWithDefaultAddressWhenAddressIdNullShouldSucceed() {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant1.getId(), 2));
        cartService.selectItems(buyer.getId(), List.of(variant1.getId()));

        CheckoutRequest request = CheckoutRequest.builder().addressId(null).build();
        String idempotencyKey = UUID.randomUUID().toString();

        CheckoutResponse response = orderService.checkout(buyer.getId(), request, idempotencyKey);
        assertNotNull(response);

        CheckoutSession session = checkoutSessionRepository.findById(response.checkoutSessionId()).orElseThrow();
        assertEquals("John Buyer", session.getShippingRecipientName());
        assertEquals("0987654321", session.getShippingPhone());
    }

    @Test
    void checkoutWhenUserNotActiveShouldFailBeforeUsingAddress() {
        User lockedBuyer = User.builder()
                .email("locked.checkout.it@shoppe.local")
                .normalizedEmail("locked.checkout.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.LOCKED)
                .build();
        lockedBuyer = userRepository.save(lockedBuyer);
        UUID lockedBuyerId = lockedBuyer.getId();

        Address lockedAddress = Address.builder()
                .userId(lockedBuyerId)
                .recipientName("Locked Buyer")
                .phone("0987654321")
                .addressLine("456 Locked St")
                .wardCode("WARD-2")
                .wardName("Ward 2")
                .districtCode("DIST-2")
                .districtName("District 2")
                .provinceCode("PROV-2")
                .provinceName("Province 2")
                .isDefault(true)
                .build();
        lockedAddress = addressRepository.save(lockedAddress);
        cartService.addItem(lockedBuyerId, new AddCartItemRequest(variant1.getId(), 1));
        cartService.selectItems(lockedBuyerId, List.of(variant1.getId()));

        CheckoutRequest request = CheckoutRequest.builder().addressId(lockedAddress.getId()).build();

        try {
            AppException exception = assertThrows(AppException.class, () ->
                    orderService.checkout(lockedBuyerId, request, UUID.randomUUID().toString())
            );

            assertEquals(ErrorCode.ACCOUNT_NOT_ACTIVE, exception.getErrorCode());
        } finally {
            cartService.clearCart(lockedBuyerId);
        }
    }

    @Test
    void checkoutWhenCartMutatedAfterSnapshotShouldNotDeleteCartMutation() {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant1.getId(), 2));
        cartService.selectItems(buyer.getId(), List.of(variant1.getId()));

        // When inventoryService.reserve is called during checkout (mid-transaction), mutate the cart
        org.mockito.Mockito.doAnswer(invocation -> {
            cartService.addItem(buyer.getId(), new AddCartItemRequest(variant2.getId(), 1));
            invocation.callRealMethod();
            return null;
        }).when(inventoryService).reserve(org.mockito.ArgumentMatchers.anyList());

        CheckoutRequest request = CheckoutRequest.builder().addressId(defaultAddress.getId()).build();
        String idempotencyKey = UUID.randomUUID().toString();

        CheckoutResponse response = orderService.checkout(buyer.getId(), request, idempotencyKey);

        assertNotNull(response);

        var cart = cartService.getCart(buyer.getId());
        assertEquals(2, cart.items().size());
        assertTrue(cart.items().stream().anyMatch(i -> i.variantId().equals(variant1.getId())));
        assertTrue(cart.items().stream().anyMatch(i -> i.variantId().equals(variant2.getId())));
    }

    @Test
    void checkoutShouldOnlyReserveSelectedItemsNotAllCartItems() {
        // Add both variants to cart but only select variant1
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant1.getId(), 1));
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant2.getId(), 5));
        cartService.selectItems(buyer.getId(), List.of(variant1.getId()));

        CheckoutRequest request = CheckoutRequest.builder().addressId(defaultAddress.getId()).build();
        CheckoutResponse response = orderService.checkout(buyer.getId(), request, UUID.randomUUID().toString());

        assertNotNull(response);
        assertEquals(1, response.orderIds().size());

        // Only variant1 should have a reservation
        List<InventoryReservation> reservations = inventoryReservationRepository.findAll();
        assertEquals(1, reservations.size());
        assertEquals(variant1.getId(), reservations.get(0).getVariantId());

        // variant2 stock should be untouched
        InventoryResponse inv2 = inventoryService.getInventoryByVariantId(variant2.getId(), seller2.getId(), seller2.getRole());
        assertEquals(20, inv2.availableStock());
        assertEquals(0, inv2.reservedStock());
    }

    @Test
    void checkoutWithExpiredIdempotencyKeyShouldSucceedAndResetKey() {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant1.getId(), 2));
        cartService.selectItems(buyer.getId(), List.of(variant1.getId()));

        CheckoutRequest request = CheckoutRequest.builder().addressId(defaultAddress.getId()).build();
        String idempotencyKey = UUID.randomUUID().toString();

        // 1. First checkout attempt with the key
        CheckoutResponse response1 = orderService.checkout(buyer.getId(), request, idempotencyKey);
        assertNotNull(response1);

        // 2. Fetch the created idempotency key and set its expiration in the past
        var dbKey = idempotencyKeyRepository.findByActorIdAndOperationAndIdempotencyKey(buyer.getId(), "CHECKOUT", idempotencyKey)
                .orElseThrow();

        com.shopee.monolith.modules.order.entity.IdempotencyKey expiredKey = com.shopee.monolith.modules.order.entity.IdempotencyKey.builder()
                .id(dbKey.getId())
                .actorId(dbKey.getActorId())
                .operation(dbKey.getOperation())
                .idempotencyKey(dbKey.getIdempotencyKey())
                .requestHash(dbKey.getRequestHash())
                .requestBodyHash(dbKey.getRequestBodyHash())
                .status(dbKey.getStatus())
                .responseBody(dbKey.getResponseBody())
                .expiresAt(java.time.Instant.now().minus(java.time.Duration.ofHours(1)))
                .createdAt(dbKey.getCreatedAt())
                .updatedAt(dbKey.getUpdatedAt())
                .build();
        idempotencyKeyRepository.save(expiredKey);

        // 3. Add items to cart again since checkout cleared it
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant1.getId(), 3));
        cartService.selectItems(buyer.getId(), List.of(variant1.getId()));

        // 4. Run second checkout with the SAME idempotency key but a different cart (different request/data)
        CheckoutResponse response2 = orderService.checkout(buyer.getId(), request, idempotencyKey);
        assertNotNull(response2);

        assertEquals(new BigDecimal("30.00"), response2.totalAmount());

        var updatedKey = idempotencyKeyRepository.findByActorIdAndOperationAndIdempotencyKey(buyer.getId(), "CHECKOUT", idempotencyKey)
                .orElseThrow();
        assertTrue(updatedKey.getExpiresAt().isAfter(java.time.Instant.now()));
        assertTrue(updatedKey.getResponseBody().contains("30.00"));
        assertEquals(dbKey.getId(), updatedKey.getId());
    }
}
