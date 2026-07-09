package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.inventory.dto.response.InventoryMovementResponse;
import com.shopee.monolith.modules.inventory.entity.InventoryMovementType;
import com.shopee.monolith.modules.inventory.repository.InventoryMovementRepository;
import com.shopee.monolith.modules.inventory.repository.InventoryRepository;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.request.CheckoutRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.shopee.monolith.modules.order.dto.response.SellerDashboardResponse;
import com.shopee.monolith.modules.order.dto.response.SellerOrderDetailResponse;
import com.shopee.monolith.modules.order.dto.response.SellerOrderSummaryResponse;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.model.FulfillmentStatus;
import com.shopee.monolith.modules.order.model.OrderPaymentStatus;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "app.checkout.mock-shipping.flat-fee-per-shop=0")
class SellerOrderServiceIT extends BasePostgresRedisIntegrationTest {

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

    private User buyer;
    private User seller;
    private User otherSeller;
    private Shop shop;
    private ProductVariant variant;
    private Address defaultAddress;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = userRepository.save(User.builder()
                .email("buyer.seller.it@shoppe.local")
                .normalizedEmail("buyer.seller.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());

        defaultAddress = addressRepository.save(Address.builder()
                .userId(buyer.getId())
                .recipientName("Seller IT Buyer")
                .phone("0987654321")
                .addressLine("123 Seller St")
                .wardCode("WARD-1").wardName("Ward 1")
                .districtCode("DIST-1").districtName("District 1")
                .provinceCode("PROV-1").provinceName("Province 1")
                .isDefault(true)
                .build());

        seller = userRepository.save(User.builder()
                .email("seller.seller.it@shoppe.local")
                .normalizedEmail("seller.seller.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build());

        otherSeller = userRepository.save(User.builder()
                .email("other.seller.it@shoppe.local")
                .normalizedEmail("other.seller.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build());

        shop = shopRepository.save(Shop.builder()
                .ownerId(seller.getId())
                .name("Seller IT Shop")
                .build());

        shopRepository.save(Shop.builder()
                .ownerId(otherSeller.getId())
                .name("Other Seller Shop")
                .build());

        Category category = categoryRepository.save(Category.builder().name("Seller IT Category").build());

        Product product = productRepository.save(Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Seller IT Product")
                .status(ProductStatus.ACTIVE)
                .build());

        variant = productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId())
                .sku("SELL-ORD-V1")
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
        inventoryMovementRepository.deleteAll();
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

    private UUID confirmedOrderId() {
        CheckoutResponse checkout = checkout(2);
        checkoutSettlementService.confirmCheckoutSession(checkout.checkoutSessionId(), "COD");
        return checkout.orderIds().get(0);
    }

    @Test
    void listOrdersShouldReturnOnlyOwnShopOrders() {
        UUID orderId = confirmedOrderId();

        PagedResponse<SellerOrderSummaryResponse> own =
                sellerOrderService.listOrders(seller.getId(), null, PageRequest.of(0, 20));
        assertEquals(1, own.totalElements());
        SellerOrderSummaryResponse summary = own.items().get(0);
        assertEquals(orderId, summary.orderId());
        assertEquals(FulfillmentStatus.READY_TO_SHIP.name(), summary.fulfillmentStatus());
        assertEquals(1, summary.itemCount());
        assertEquals("Seller IT Buyer", summary.shippingRecipientName());

        PagedResponse<SellerOrderSummaryResponse> foreign =
                sellerOrderService.listOrders(otherSeller.getId(), null, PageRequest.of(0, 20));
        assertEquals(0, foreign.totalElements());
    }

    @Test
    void getOrderDetailWhenForeignSellerShouldThrowOrderNotFound() {
        UUID orderId = confirmedOrderId();

        SellerOrderDetailResponse detail = sellerOrderService.getOrderDetail(seller.getId(), orderId);
        assertEquals(orderId, detail.orderId());
        assertEquals(1, detail.items().size());
        assertEquals("Seller IT Product", detail.items().get(0).productName());

        AppException ex = assertThrows(AppException.class,
                () -> sellerOrderService.getOrderDetail(otherSeller.getId(), orderId));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void fulfillmentFlowShipThenDeliverShouldTransitionStates() {
        UUID orderId = confirmedOrderId();

        Order beforeShip = orderRepository.findById(orderId).orElseThrow();
        assertEquals(OrderStatus.CONFIRMED, beforeShip.getStatus());
        assertEquals(OrderPaymentStatus.UNPAID, beforeShip.getPaymentStatus());

        SellerOrderDetailResponse shipped = sellerOrderService.shipOrder(seller.getId(), orderId);
        assertEquals(FulfillmentStatus.SHIPPED.name(), shipped.fulfillmentStatus());
        assertEquals(OrderStatus.FULFILLED.name(), shipped.status());
        assertEquals(OrderPaymentStatus.UNPAID.name(), shipped.paymentStatus());

        SellerOrderDetailResponse delivered = sellerOrderService.deliverOrder(seller.getId(), orderId);
        assertEquals(FulfillmentStatus.DELIVERED.name(), delivered.fulfillmentStatus());
        assertEquals(OrderStatus.DELIVERED.name(), delivered.status());

        Order persisted = orderRepository.findById(orderId).orElseThrow();
        assertEquals(FulfillmentStatus.DELIVERED, persisted.getFulfillmentStatus());
        assertEquals(OrderStatus.DELIVERED, persisted.getStatus());
        // COD cash is only collected when the carrier hands the order over.
        assertEquals(OrderPaymentStatus.PAID, persisted.getPaymentStatus());
    }

    @Test
    void shipOrderWhenUnpaidOrForeignShouldFail() {
        CheckoutResponse checkout = checkout(1);
        UUID unconfirmedOrderId = checkout.orderIds().get(0);

        AppException unpaid = assertThrows(AppException.class,
                () -> sellerOrderService.shipOrder(seller.getId(), unconfirmedOrderId));
        assertEquals(ErrorCode.ORDER_FULFILLMENT_INVALID_STATE, unpaid.getErrorCode());

        AppException foreign = assertThrows(AppException.class,
                () -> sellerOrderService.shipOrder(otherSeller.getId(), unconfirmedOrderId));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, foreign.getErrorCode());
    }

    @Test
    void deliverOrderWhenNotShippedShouldThrowInvalidState() {
        UUID orderId = confirmedOrderId();

        AppException ex = assertThrows(AppException.class,
                () -> sellerOrderService.deliverOrder(seller.getId(), orderId));
        assertEquals(ErrorCode.ORDER_FULFILLMENT_INVALID_STATE, ex.getErrorCode());
    }

    @Test
    void inventoryMovementLedgerShouldRecordInitialReserveAndConfirm() {
        confirmedOrderId();

        PagedResponse<InventoryMovementResponse> movements = inventoryService.listMovements(
                variant.getId(), seller.getId(), seller.getRole(), PageRequest.of(0, 20));

        List<String> types = movements.items().stream().map(InventoryMovementResponse::movementType).toList();
        assertTrue(types.contains(InventoryMovementType.INITIAL.name()));
        assertTrue(types.contains(InventoryMovementType.RESERVE.name()));
        assertTrue(types.contains(InventoryMovementType.CONFIRM.name()));

        InventoryMovementResponse confirm = movements.items().stream()
                .filter(m -> m.movementType().equals(InventoryMovementType.CONFIRM.name()))
                .findFirst().orElseThrow();
        assertEquals(2, confirm.quantity());
        assertEquals(8, confirm.availableStockAfter());
        assertEquals(0, confirm.reservedStockAfter());

        // Ownership: foreign seller cannot read the ledger
        AppException ex = assertThrows(AppException.class,
                () -> inventoryService.listMovements(
                        variant.getId(), otherSeller.getId(), otherSeller.getRole(), PageRequest.of(0, 20)));
        assertEquals(ErrorCode.SHOP_OWNER_REQUIRED, ex.getErrorCode());
    }

    @Test
    void getDashboardShouldAggregateCountsAndActionableOrders() {
        UUID orderId = confirmedOrderId();

        SellerDashboardResponse dashboard = sellerOrderService.getDashboard(seller.getId());

        assertEquals(shop.getId(), dashboard.shopId());
        assertEquals(1, dashboard.totalProducts());
        assertEquals(1, dashboard.activeProducts());
        assertEquals(1L, dashboard.orderCountsByFulfillmentStatus().get(FulfillmentStatus.READY_TO_SHIP.name()));
        // COD confirms fulfillment immediately but stays UNPAID until delivery collects cash.
        assertEquals(1L, dashboard.orderCountsByPaymentStatus().get("UNPAID"));
        assertEquals(1, dashboard.latestActionableOrders().size());
        assertEquals(orderId, dashboard.latestActionableOrders().get(0).orderId());

        sellerOrderService.shipOrder(seller.getId(), orderId);
        SellerDashboardResponse afterShip = sellerOrderService.getDashboard(seller.getId());
        assertTrue(afterShip.latestActionableOrders().isEmpty());
        assertEquals(1L, afterShip.orderCountsByFulfillmentStatus().get(FulfillmentStatus.SHIPPED.name()));
    }
}
