package com.shopee.monolith.modules.cart.service;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshot;
import com.shopee.monolith.modules.cart.dto.request.AddCartItemRequest;
import com.shopee.monolith.modules.cart.dto.request.UpdateCartItemRequest;
import com.shopee.monolith.modules.cart.dto.response.CartResponse;
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
import org.springframework.data.redis.core.StringRedisTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CartServiceIT extends BasePostgresRedisIntegrationTest {

    @Autowired
    private CartService cartService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

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

    private User buyer;
    private ProductVariant variant1;
    private ProductVariant variant2;
    private Category category;

    @BeforeEach
    void setUp() {
        tearDown();

        buyer = User.builder()
                .email("buyer.cart.it@shoppe.local")
                .normalizedEmail("buyer.cart.it@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        buyer = userRepository.save(buyer);

        User seller = User.builder()
                .email("seller.cart.it@shoppe.local")
                .normalizedEmail("seller.cart.it@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build();
        seller = userRepository.save(seller);

        Shop shop = Shop.builder()
                .ownerId(seller.getId())
                .name("Cart test Shop")
                .build();
        shop = shopRepository.save(shop);

        category = Category.builder()
                .name("Cart test Cat")
                .build();
        category = categoryRepository.save(category);

        Product product = Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Cart test Product")
                .description("Cart testing desc")
                .status(ProductStatus.ACTIVE)
                .build();
        product = productRepository.save(product);

        variant1 = ProductVariant.builder()
                .productId(product.getId())
                .sku("CART-V1")
                .name("Variant 1")
                .price(BigDecimal.valueOf(10.00))
                .build();
        variant1 = productVariantRepository.save(variant1);

        variant2 = ProductVariant.builder()
                .productId(product.getId())
                .sku("CART-V2")
                .name("Variant 2")
                .price(BigDecimal.valueOf(25.50))
                .build();
        variant2 = productVariantRepository.save(variant2);
    }

    @AfterEach
    void tearDown() {
        if (buyer != null) {
            cartService.clearCart(buyer.getId());
        }
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        if (category != null) {
            categoryRepository.delete(category);
        }
        shopRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void cartLifecycleShouldMutateRedisCorrectly() {
        UUID userId = buyer.getId();
        String itemsKey = "cart:" + userId + ":items";
        String versionKey = "cart:" + userId + ":version";

        // 1. Initial State
        CartResponse cart = cartService.getCart(userId);
        assertNotNull(cart);
        assertTrue(cart.items().isEmpty());
        assertEquals(0, cart.version());

        // 2. Add Item 1
        cart = cartService.addItem(userId, new AddCartItemRequest(variant1.getId(), 2));
        assertEquals(1, cart.items().size());
        assertEquals(2, cart.items().get(0).quantity());
        assertEquals(1, cart.version());

        // Check key format & sliding TTL
        Boolean keyExists = stringRedisTemplate.hasKey(itemsKey);
        assertTrue(keyExists);
        Long ttl = stringRedisTemplate.getExpire(itemsKey);
        assertTrue(ttl > 0 && ttl <= Duration.ofDays(7).toSeconds());

        // 3. Add Item 2
        cart = cartService.addItem(userId, new AddCartItemRequest(variant2.getId(), 3));
        assertEquals(2, cart.items().size());
        assertEquals(2, cart.version());

        // 4. Update quantity
        cart = cartService.updateItem(userId, variant1.getId(), new UpdateCartItemRequest(5));
        assertEquals(2, cart.items().size());
        assertEquals(3, cart.version());
        // Verify quantity updated
        var item1 = cart.items().stream().filter(i -> i.variantId().equals(variant1.getId())).findFirst().orElseThrow();
        assertEquals(5, item1.quantity());

        // 5. Get Snapshot
        CartSnapshot snapshot = cartService.getSnapshot(userId);
        assertNotNull(snapshot);
        assertEquals(userId, snapshot.userId());
        assertEquals(3, snapshot.version());
        assertEquals(2, snapshot.items().size());

        // 6. Clear conditional snapshot (wrong version, shouldn't clear)
        cartService.clearSnapshotIfVersionUnchanged(userId, 99L);
        assertTrue(stringRedisTemplate.hasKey(itemsKey));

        // 7. Clear conditional snapshot (correct version, should clear)
        cartService.clearSnapshotIfVersionUnchanged(userId, 3L);
        assertFalse(stringRedisTemplate.hasKey(itemsKey));
        assertFalse(stringRedisTemplate.hasKey(versionKey));
    }

    @Test
    void selectItemsShouldMarkItemAsSelectedAndIncrementVersion() {
        UUID userId = buyer.getId();
        cartService.addItem(userId, new AddCartItemRequest(variant1.getId(), 2));
        long versionAfterAdd = cartService.getCart(userId).version();

        CartResponse cart = cartService.selectItems(userId, List.of(variant1.getId()));

        var item = cart.items().stream().filter(i -> i.variantId().equals(variant1.getId())).findFirst().orElseThrow();
        assertTrue(item.selected());
        assertEquals(versionAfterAdd + 1, cart.version());
    }

    @Test
    void deselectItemsShouldUnmarkAndIncrementVersion() {
        UUID userId = buyer.getId();
        cartService.addItem(userId, new AddCartItemRequest(variant1.getId(), 2));
        cartService.selectItems(userId, List.of(variant1.getId()));

        CartResponse cart = cartService.deselectItems(userId, List.of(variant1.getId()));

        var item = cart.items().stream().filter(i -> i.variantId().equals(variant1.getId())).findFirst().orElseThrow();
        assertFalse(item.selected());
    }

    @Test
    void removeItemShouldAlsoRemoveFromSelectedSet() {
        UUID userId = buyer.getId();
        cartService.addItem(userId, new AddCartItemRequest(variant1.getId(), 2));
        cartService.selectItems(userId, List.of(variant1.getId()));

        cartService.removeItem(userId, variant1.getId());

        CartResponse cart = cartService.getCart(userId);
        assertTrue(cart.items().stream().noneMatch(i -> i.variantId().equals(variant1.getId())));

        CartSnapshot selected = cartService.getSelectedSnapshot(userId);
        assertTrue(selected.items().isEmpty());
    }

    @Test
    void clearCartShouldAlsoClearSelectedSet() {
        UUID userId = buyer.getId();
        cartService.addItem(userId, new AddCartItemRequest(variant1.getId(), 2));
        cartService.selectItems(userId, List.of(variant1.getId()));

        cartService.clearCart(userId);

        CartSnapshot selected = cartService.getSelectedSnapshot(userId);
        assertTrue(selected.items().isEmpty());
    }

    @Test
    void selectNonExistentVariantShouldThrowInvalidRequest() {
        UUID userId = buyer.getId();
        AppException ex = assertThrows(AppException.class,
                () -> cartService.selectItems(userId, List.of(UUID.randomUUID())));
        assertEquals(ErrorCode.INVALID_REQUEST, ex.getErrorCode());
    }

    @Test
    void selectItemsABASafeVersionShouldBeConsistent() {
        UUID userId = buyer.getId();
        cartService.addItem(userId, new AddCartItemRequest(variant1.getId(), 1));
        cartService.addItem(userId, new AddCartItemRequest(variant2.getId(), 1));

        long v1 = cartService.getCart(userId).version();
        cartService.selectItems(userId, List.of(variant1.getId()));
        long v2 = cartService.getCart(userId).version();
        cartService.deselectItems(userId, List.of(variant1.getId()));
        long v3 = cartService.getCart(userId).version();

        assertTrue(v2 > v1);
        assertTrue(v3 > v2);
    }

    @Test
    void addCartItemConcurrentlyShouldSucceedAtomically() throws InterruptedException {
        UUID userId = buyer.getId();
        int threadsCount = 5;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(threadsCount);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(threadsCount);

        for (int i = 0; i < threadsCount; i++) {
            executor.submit(() -> {
                try {
                    cartService.addItem(userId, new AddCartItemRequest(variant1.getId(), 2));
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            assertTrue(latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);
        }

        CartResponse cart = cartService.getCart(userId);
        assertEquals(1, cart.items().size());
        assertEquals(10, cart.items().get(0).quantity());
        assertEquals(5, cart.version());
    }
}
