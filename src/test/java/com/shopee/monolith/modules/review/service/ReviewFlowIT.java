package com.shopee.monolith.modules.review.service;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.chat.dto.response.ChatRoomResponse;
import com.shopee.monolith.modules.chat.entity.ChatRoom;
import com.shopee.monolith.modules.chat.repository.ChatMessageRepository;
import com.shopee.monolith.modules.chat.repository.ChatRoomRepository;
import com.shopee.monolith.modules.chat.service.ChatService;
import com.shopee.monolith.modules.inventory.repository.InventoryMovementRepository;
import com.shopee.monolith.modules.inventory.repository.InventoryRepository;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.notification.model.NotificationType;
import com.shopee.monolith.modules.notification.repository.NotificationRepository;
import com.shopee.monolith.modules.notification.service.NotificationInboxService;
import com.shopee.monolith.modules.order.dto.request.CheckoutRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.shopee.monolith.modules.order.entity.OrderItem;
import com.shopee.monolith.modules.order.repository.CheckoutSessionRepository;
import com.shopee.monolith.modules.order.repository.InventoryReservationRepository;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
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
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.review.dto.request.CreateReviewRequest;
import com.shopee.monolith.modules.review.dto.response.ProductReviewListResponse;
import com.shopee.monolith.modules.review.dto.response.ReviewResponse;
import com.shopee.monolith.modules.review.entity.Review;
import com.shopee.monolith.modules.review.repository.ReviewRepository;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 7 IT: review flow (delivered-order gating + one-review-per-item DB constraint),
 * notification inbox ownership, chat room uniqueness and participant authorization.
 */
@TestPropertySource(properties = "app.checkout.mock-shipping.flat-fee-per-shop=0")
class ReviewFlowIT extends BasePostgresRedisIntegrationTest {

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
    private ReviewService reviewService;
    @Autowired
    private ReviewRepository reviewRepository;
    @Autowired
    private ProductService productService;
    @Autowired
    private NotificationInboxService notificationInboxService;
    @Autowired
    private NotificationRepository notificationRepository;
    @Autowired
    private ChatService chatService;
    @Autowired
    private ChatRoomRepository chatRoomRepository;
    @Autowired
    private ChatMessageRepository chatMessageRepository;
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
    private Shop shop;
    private Product product;
    private ProductVariant variant;
    private Address defaultAddress;
    private Category category;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = userRepository.save(User.builder()
                .email("buyer.review.it@shoppe.local")
                .normalizedEmail("buyer.review.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());

        defaultAddress = addressRepository.save(Address.builder()
                .userId(buyer.getId())
                .recipientName("Review IT Buyer")
                .phone("0987654321")
                .addressLine("123 Review St")
                .wardCode("WARD-1").wardName("Ward 1")
                .districtCode("DIST-1").districtName("District 1")
                .provinceCode("PROV-1").provinceName("Province 1")
                .isDefault(true)
                .build());

        seller = userRepository.save(User.builder()
                .email("seller.review.it@shoppe.local")
                .normalizedEmail("seller.review.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build());

        shop = shopRepository.save(Shop.builder()
                .ownerId(seller.getId())
                .name("Review IT Shop")
                .build());

        category = categoryRepository.save(Category.builder().name("Review IT Category").build());

        product = productRepository.save(Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Review IT Product")
                .status(ProductStatus.ACTIVE)
                .build());

        variant = productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId())
                .sku("REVIEW-IT-V1")
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
        chatMessageRepository.deleteAll();
        chatRoomRepository.deleteAll();
        notificationRepository.deleteAll();
        reviewRepository.deleteAll();
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

    private UUID placeDeliveredOrderAndGetItemId() {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant.getId(), 1));
        cartService.selectItems(buyer.getId(), List.of(variant.getId()));
        CheckoutResponse checkout = orderService.checkout(buyer.getId(),
                CheckoutRequest.builder().addressId(defaultAddress.getId()).build(),
                UUID.randomUUID().toString());
        checkoutSettlementService.confirmCheckoutSession(checkout.checkoutSessionId(), "COD");
        UUID orderId = checkout.orderIds().get(0);
        sellerOrderService.shipOrder(seller.getId(), orderId);
        sellerOrderService.deliverOrder(seller.getId(), orderId);
        OrderItem item = orderItemRepository.findAllByOrderId(orderId).get(0);
        return item.getId();
    }

    // ==================== Review ====================

    @Test
    void createReviewWhenOrderDeliveredShouldSucceedAndAggregateRating() {
        UUID orderItemId = placeDeliveredOrderAndGetItemId();

        ReviewResponse response = reviewService.createReview(buyer.getId(), CreateReviewRequest.builder()
                .orderItemId(orderItemId).rating(4).comment("solid").build());
        assertNotNull(response.id());
        assertEquals(product.getId(), response.productId());

        ProductReviewListResponse list = reviewService.listProductReviews(product.getId(), PageRequest.of(0, 10));
        assertEquals(1, list.ratingCount());
        assertEquals(0, list.ratingAvg().compareTo(BigDecimal.valueOf(4)));
        assertEquals(1, list.reviews().items().size());
    }

    @Test
    void createReviewWhenOrderNotDeliveredShouldThrowNotReviewable() {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant.getId(), 1));
        cartService.selectItems(buyer.getId(), List.of(variant.getId()));
        CheckoutResponse checkout = orderService.checkout(buyer.getId(),
                CheckoutRequest.builder().addressId(defaultAddress.getId()).build(),
                UUID.randomUUID().toString());
        UUID orderId = checkout.orderIds().get(0);
        UUID orderItemId = orderItemRepository.findAllByOrderId(orderId).get(0).getId();

        AppException ex = assertThrows(AppException.class, () -> reviewService.createReview(
                buyer.getId(), CreateReviewRequest.builder().orderItemId(orderItemId).rating(5).build()));
        assertEquals(ErrorCode.ORDER_NOT_REVIEWABLE, ex.getErrorCode());
    }

    @Test
    void createReviewWhenDuplicateShouldRejectAtServiceAndDbLevel() {
        UUID orderItemId = placeDeliveredOrderAndGetItemId();
        reviewService.createReview(buyer.getId(), CreateReviewRequest.builder()
                .orderItemId(orderItemId).rating(5).build());

        AppException ex = assertThrows(AppException.class, () -> reviewService.createReview(
                buyer.getId(), CreateReviewRequest.builder().orderItemId(orderItemId).rating(1).build()));
        assertEquals(ErrorCode.REVIEW_ALREADY_EXISTS, ex.getErrorCode());

        // DB unique constraint enforces the invariant even if the service pre-check is bypassed
        assertThrows(DataIntegrityViolationException.class, () -> reviewRepository.saveAndFlush(Review.builder()
                .orderItemId(orderItemId)
                .orderId(UUID.randomUUID())
                .productId(product.getId())
                .buyerId(buyer.getId())
                .shopId(shop.getId())
                .rating(2)
                .build()));
    }

    @Test
    void createReviewWhenNotOrderOwnerShouldThrowOrderNotFound() {
        UUID orderItemId = placeDeliveredOrderAndGetItemId();

        AppException ex = assertThrows(AppException.class, () -> reviewService.createReview(
                seller.getId(), CreateReviewRequest.builder().orderItemId(orderItemId).rating(5).build()));
        assertEquals(ErrorCode.ORDER_NOT_FOUND, ex.getErrorCode());
    }

    @Test
    void refreshRatingSummaryShouldPersistAggregateOnProduct() {
        UUID orderItemId = placeDeliveredOrderAndGetItemId();
        reviewService.createReview(buyer.getId(), CreateReviewRequest.builder()
                .orderItemId(orderItemId).rating(4).build());

        ReviewServiceImpl impl = (ReviewServiceImpl) reviewService;
        ReviewServiceImpl.RatingSummary summary = impl.aggregateRating(product.getId());
        productService.refreshRatingSummary(product.getId(), summary.avg(), summary.count());

        Product refreshed = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(0, refreshed.getRatingAvg().compareTo(BigDecimal.valueOf(4)));
        assertEquals(1, refreshed.getRatingCount());
    }

    // ==================== Notification inbox ====================

    @Test
    void notificationInboxMarkReadIsOwnerScopedAndIdempotent() {
        notificationInboxService.createNotification(buyer.getId(), NotificationType.ORDER_CONFIRMED,
                "confirmed", "body", "ORDER", UUID.randomUUID());
        UUID notificationId = notificationRepository
                .findAllByUserIdOrderByCreatedAtDesc(buyer.getId(), PageRequest.of(0, 10))
                .getContent().get(0).getId();

        assertEquals(1, notificationInboxService.countUnread(buyer.getId()));

        AppException ex = assertThrows(AppException.class,
                () -> notificationInboxService.markRead(seller.getId(), notificationId));
        assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, ex.getErrorCode());

        notificationInboxService.markRead(buyer.getId(), notificationId);
        notificationInboxService.markRead(buyer.getId(), notificationId);
        assertEquals(0, notificationInboxService.countUnread(buyer.getId()));
    }

    // ==================== Chat ====================

    @Test
    void chatRoomUniquePerBuyerShopAndParticipantScoped() {
        ChatRoomResponse opened = chatService.openRoom(buyer.getId(), shop.getId());
        ChatRoomResponse reopened = chatService.openRoom(buyer.getId(), shop.getId());
        assertEquals(opened.id(), reopened.id());

        // DB unique constraint backs the one-room-per-pair invariant
        assertThrows(DataIntegrityViolationException.class, () -> chatRoomRepository.saveAndFlush(
                ChatRoom.builder().buyerId(buyer.getId()).shopId(shop.getId()).build()));

        chatService.sendMessage(buyer.getId(), opened.id(), "hello seller");
        chatService.sendMessage(seller.getId(), opened.id(), "hello buyer");
        assertEquals(2, chatService.getMessages(buyer.getId(), opened.id(), PageRequest.of(0, 10))
                .items().size());

        User stranger = userRepository.save(User.builder()
                .email("stranger.review.it@shoppe.local")
                .normalizedEmail("stranger.review.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());
        AppException ex = assertThrows(AppException.class,
                () -> chatService.getMessages(stranger.getId(), opened.id(), PageRequest.of(0, 10)));
        assertEquals(ErrorCode.CHAT_ROOM_ACCESS_DENIED, ex.getErrorCode());
        assertFalse(chatService.isParticipant(stranger.getId(), opened.id()));
        assertTrue(chatService.isParticipant(seller.getId(), opened.id()));
    }
}
