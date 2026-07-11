package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.dto.request.CheckoutRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.repository.InventoryReservationRepository;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import com.shopee.monolith.modules.order.repository.CheckoutSessionRepository;
import com.shopee.monolith.modules.order.repository.IdempotencyKeyRepository;
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
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestPropertySource(properties = "app.checkout.mock-shipping.flat-fee-per-shop=30000")
class OrderCheckoutShippingFeeIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private OrderService orderService;

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
    private AddressRepository addressRepository;

    @Autowired
    private com.shopee.monolith.modules.inventory.repository.InventoryRepository inventoryRepository;

    @Autowired
    private CheckoutSessionRepository checkoutSessionRepository;

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderItemRepository orderItemRepository;

    @Autowired
    private InventoryReservationRepository inventoryReservationRepository;

    @Autowired
    private IdempotencyKeyRepository idempotencyKeyRepository;

    private User buyer;
    private User seller;
    private Shop shop;
    private ProductVariant variant;
    private Address defaultAddress;
    private Category category;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = userRepository.save(User.builder()
                .email("buyer.fee.it@shoppe.local")
                .normalizedEmail("buyer.fee.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build());

        defaultAddress = addressRepository.save(Address.builder()
                .userId(buyer.getId())
                .recipientName("Fee Buyer")
                .phone("0987654321")
                .addressLine("99 Fee St")
                .wardCode("W1")
                .wardName("Ward 1")
                .districtCode("D1")
                .districtName("District 1")
                .provinceCode("P1")
                .provinceName("Province 1")
                .isDefault(true)
                .build());

        seller = userRepository.save(User.builder()
                .email("seller.fee.it@shoppe.local")
                .normalizedEmail("seller.fee.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build());

        shop = shopRepository.save(Shop.builder()
                .ownerId(seller.getId())
                .name("Fee Shop")
                .build());

        category = categoryRepository.save(Category.builder().name("Fee Cat").build());

        Product product = productRepository.save(Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Fee Product")
                .status(ProductStatus.ACTIVE)
                .build());

        variant = productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId())
                .sku("FEE-V1")
                .name("Fee Variant")
                .price(BigDecimal.valueOf(100.00))
                .build());

        inventoryService.createInventory(variant.getId(), 50, seller.getId(), seller.getRole());
    }

    @AfterEach
    void tearDown() {
        if (buyer != null) {
            cartService.clearCart(buyer.getId());
        }
        idempotencyKeyRepository.deleteAll();
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
    void checkoutShouldPersistItemsSubtotalAndShippingFeeSnapshot() {
        cartService.addItem(buyer.getId(), new AddCartItemRequest(variant.getId(), 2));
        cartService.selectItems(buyer.getId(), List.of(variant.getId()));

        CheckoutResponse response = orderService.checkout(
                buyer.getId(),
                CheckoutRequest.builder().addressId(defaultAddress.getId()).build(),
                UUID.randomUUID().toString()
        );

        List<Order> orders = orderRepository.findAll();
        assertEquals(1, orders.size());
        Order order = orders.get(0);

        BigDecimal expectedSubtotal = BigDecimal.valueOf(200.00).setScale(2);
        BigDecimal expectedFee = BigDecimal.valueOf(30000).setScale(2);
        BigDecimal expectedTotal = expectedSubtotal.add(expectedFee);

        assertEquals(0, order.getItemsSubtotal().compareTo(expectedSubtotal));
        assertEquals(0, order.getShippingFee().compareTo(expectedFee));
        assertEquals(0, order.getTotalAmount().compareTo(expectedTotal));
        assertEquals(0, response.totalAmount().compareTo(expectedTotal));

        // Also check that shippingFee stored is non-zero (guard against accidental zero override)
        assertTrue(order.getShippingFee().compareTo(BigDecimal.ZERO) > 0);
    }
}
