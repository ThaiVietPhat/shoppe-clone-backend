package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.inventory.repository.InventoryRepository;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.request.CheckoutRequest;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderDetailResponse;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderSummaryResponse;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.shopee.monolith.modules.order.entity.Order;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "app.checkout.mock-shipping.flat-fee-per-shop=0")
class BuyerOrderServiceIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private OrderService orderService;
    @Autowired
    private BuyerOrderService buyerOrderService;
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

    private User buyer;
    private User otherBuyer;
    private User seller;
    private Shop shop;
    private Category category;
    private ProductVariant variant;
    private Address defaultAddress;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = userRepository.save(User.builder()
                .email("buyer.orders.it@shoppe.local")
                .normalizedEmail("buyer.orders.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());

        otherBuyer = userRepository.save(User.builder()
                .email("other.orders.it@shoppe.local")
                .normalizedEmail("other.orders.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());

        defaultAddress = addressRepository.save(Address.builder()
                .userId(buyer.getId())
                .recipientName("Order Buyer")
                .phone("0987654321")
                .addressLine("123 Order St")
                .wardCode("WARD-1").wardName("Ward 1")
                .districtCode("DIST-1").districtName("District 1")
                .provinceCode("PROV-1").provinceName("Province 1")
                .isDefault(true)
                .build());

        seller = userRepository.save(User.builder()
                .email("seller.orders.it@shoppe.local")
                .normalizedEmail("seller.orders.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build());

        shop = shopRepository.save(Shop.builder()
                .ownerId(seller.getId())
                .name("Order Shop")
                .build());

        category = categoryRepository.save(Category.builder().name("Order Category").build());

        Product product = productRepository.save(Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Order Product")
                .status(ProductStatus.ACTIVE)
                .build());

        variant = productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId())
                .sku("BUY-ORD-V1")
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
        inventoryReservationRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        checkoutSessionRepository.deleteAll();
        addressRepository.deleteAll();
        inventoryRepository.deleteAll();
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        // Delete only the category this test created — categories now also holds hierarchical
        // seed data (V25 migration) that a blanket deleteAll() would violate FK constraints on.
        if (category != null) {
            categoryRepository.delete(category);
        }
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

    @Test
    void listOrdersShouldReturnOnlyOwnOrdersWithShopNameAndItemCount() {
        CheckoutResponse checkout = checkout(2);

        PagedResponse<BuyerOrderSummaryResponse> own =
                buyerOrderService.listOrders(buyer.getId(), null, PageRequest.of(0, 20));
        assertEquals(1, own.totalElements());
        BuyerOrderSummaryResponse summary = own.items().get(0);
        assertEquals(checkout.orderIds().get(0), summary.orderId());
        assertEquals("Order Shop", summary.shopName());
        assertEquals(1, summary.itemCount());
        assertEquals(OrderStatus.PENDING_PAYMENT.name(), summary.status());

        PagedResponse<BuyerOrderSummaryResponse> foreign =
                buyerOrderService.listOrders(otherBuyer.getId(), null, PageRequest.of(0, 20));
        assertEquals(0, foreign.totalElements());
    }

    @Test
    void getOrderDetailShouldReturnSnapshotsAndTimelineAndRejectForeignBuyer() {
        CheckoutResponse checkout = checkout(2);
        UUID orderId = checkout.orderIds().get(0);

        BuyerOrderDetailResponse detail = buyerOrderService.getOrderDetail(buyer.getId(), orderId);
        assertEquals(orderId, detail.orderId());
        assertEquals("Order Buyer", detail.shippingRecipientName());
        assertEquals("123 Order St", detail.shippingAddressLine());
        assertEquals(1, detail.items().size());
        assertEquals("Order Product", detail.items().get(0).productName());
        assertEquals(2, detail.items().get(0).quantity());
        assertNotNull(detail.timeline());
        assertEquals("PLACED", detail.timeline().get(0).event());
        assertEquals(0, java.math.BigDecimal.ZERO.compareTo(detail.discountAmount()));

        AppException exception = assertThrows(AppException.class,
                () -> buyerOrderService.getOrderDetail(otherBuyer.getId(), orderId));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void cancelOrderWhenPendingPaymentShouldCancelEntireSessionAndReleaseInventory() {
        CheckoutResponse checkout = checkout(2);
        UUID orderId = checkout.orderIds().get(0);

        buyerOrderService.cancelOrder(buyer.getId(), orderId);

        // Order cancelled
        Order order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CANCELLED, order.getStatus());

        // Checkout session also cancelled (cancel entire session, not just one order)
        var session = checkoutSessionRepository.findById(checkout.checkoutSessionId()).orElseThrow();
        assertEquals(com.shopee.monolith.modules.order.model.CheckoutSessionStatus.CANCELLED, session.getStatus());

        // All reservations released
        assertTrue(inventoryReservationRepository.findAll().stream()
                .allMatch(r -> r.getStatus() == InventoryReservationStatus.RELEASED));

        var inventory = inventoryRepository.findByVariantId(variant.getId()).orElseThrow();
        assertEquals(10, inventory.getAvailableStock());
        assertEquals(0, inventory.getReservedStock());
    }

    @Test
    void cancelOrderWhenPaidShouldThrowConflict() {
        CheckoutResponse checkout = checkout(2);
        UUID orderId = checkout.orderIds().get(0);
        checkoutSettlementService.confirmCheckoutSession(checkout.checkoutSessionId(), "COD");

        AppException exception = assertThrows(AppException.class,
                () -> buyerOrderService.cancelOrder(buyer.getId(), orderId));
        assertEquals(ErrorCode.ORDER_CANNOT_BE_CANCELLED, exception.getErrorCode());
    }

    @Test
    void cancelOrderWhenNotOwnerShouldThrowNotFound() {
        CheckoutResponse checkout = checkout(1);
        UUID orderId = checkout.orderIds().get(0);

        AppException exception = assertThrows(AppException.class,
                () -> buyerOrderService.cancelOrder(otherBuyer.getId(), orderId));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, exception.getErrorCode());
    }
}
