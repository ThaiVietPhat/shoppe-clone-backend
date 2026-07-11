package com.shopee.monolith.modules.inventory.service;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.inventory.dto.command.ReserveInventoryCommand;
import com.shopee.monolith.modules.inventory.entity.Inventory;
import com.shopee.monolith.modules.inventory.repository.InventoryRepository;
import com.shopee.monolith.modules.product.entity.Category;
import com.shopee.monolith.modules.product.entity.Product;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryConcurrencyIT extends BasePostgresRedisIntegrationTest {

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
    private InventoryRepository inventoryRepository;

    @Autowired
    private InventoryService inventoryService;

    private User user;
    private Shop shop;
    private Category category;
    private Product product;
    private ProductVariant variant1;
    private ProductVariant variant2;

    @BeforeEach
    void setUp() {
        tearDown();
        user = User.builder()
                .email("seller.inventory.concurrency@shoppe.local")
                .normalizedEmail("seller.inventory.concurrency@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        user = userRepository.save(user);

        shop = Shop.builder()
                .ownerId(user.getId())
                .name("Seller Inventory Concurrency Shop")
                .build();
        shop = shopRepository.save(shop);

        category = Category.builder()
                .name("Kitchen")
                .build();
        category = categoryRepository.save(category);

        product = Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Coffee Maker")
                .description("Espresso Machine")
                .build();
        product = productRepository.save(product);

        variant1 = ProductVariant.builder()
                .productId(product.getId())
                .sku("COFFEE-M-1")
                .name("Standard Espresso")
                .price(BigDecimal.valueOf(149.99))
                .build();
        variant1 = productVariantRepository.save(variant1);

        variant2 = ProductVariant.builder()
                .productId(product.getId())
                .sku("COFFEE-M-2")
                .name("Premium Espresso")
                .price(BigDecimal.valueOf(249.99))
                .build();
        variant2 = productVariantRepository.save(variant2);
    }

    @AfterEach
    void tearDown() {
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
    void reserveStockConcurrencyOnlyOneShouldSucceedIfInsufficientStock() throws Exception {
        Inventory inventory = Inventory.builder()
                .variantId(variant1.getId())
                .availableStock(10)
                .reservedStock(0)
                .build();
        inventoryRepository.saveAndFlush(inventory);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        // Thread A reserves 7
        Future<?> futureA = executor.submit(() -> {
            try {
                startLatch.await();
                inventoryService.reserve(Collections.singletonList(
                        new ReserveInventoryCommand(variant1.getId(), 7)
                ));
                successCount.incrementAndGet();
            } catch (Exception e) {
                if (e instanceof AppException && ((AppException) e).getErrorCode() == ErrorCode.INSUFFICIENT_STOCK) {
                    failureCount.incrementAndGet();
                }
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread B reserves 5
        Future<?> futureB = executor.submit(() -> {
            try {
                startLatch.await();
                inventoryService.reserve(Collections.singletonList(
                        new ReserveInventoryCommand(variant1.getId(), 5)
                ));
                successCount.incrementAndGet();
            } catch (Exception e) {
                if (e instanceof AppException && ((AppException) e).getErrorCode() == ErrorCode.INSUFFICIENT_STOCK) {
                    failureCount.incrementAndGet();
                }
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

        executor.shutdown();

        // 7 + 5 = 12 > 10. Only one thread can succeed.
        assertEquals(1, successCount.get());
        assertEquals(1, failureCount.get());

        // Reload and verify stock state
        Inventory updatedInventory = inventoryRepository.findByVariantId(variant1.getId()).orElseThrow();
        // Remaining stock: either 3 (if 7 reserved) or 5 (if 5 reserved)
        assertTrue(updatedInventory.getAvailableStock() == 3 || updatedInventory.getAvailableStock() == 5);
        assertTrue(updatedInventory.getReservedStock() == 7 || updatedInventory.getReservedStock() == 5);
    }

    @Test
    void reserveMultiStockConcurrencyShouldAvoidDeadlockByOrdering() throws Exception {
        Inventory inv1 = Inventory.builder()
                .variantId(variant1.getId())
                .availableStock(10)
                .reservedStock(0)
                .build();
        Inventory inv2 = Inventory.builder()
                .variantId(variant2.getId())
                .availableStock(10)
                .reservedStock(0)
                .build();
        inventoryRepository.saveAllAndFlush(Arrays.asList(inv1, inv2));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);

        // Thread 1 locks variant1 then variant2 (or vice-versa, since service sorts them)
        executor.submit(() -> {
            try {
                startLatch.await();
                inventoryService.reserve(Arrays.asList(
                        new ReserveInventoryCommand(variant1.getId(), 2),
                        new ReserveInventoryCommand(variant2.getId(), 3)
                ));
                successCount.incrementAndGet();
            } catch (Exception e) {
                // ignore
            } finally {
                doneLatch.countDown();
            }
        });

        // Thread 2 locks in the opposite order
        executor.submit(() -> {
            try {
                startLatch.await();
                inventoryService.reserve(Arrays.asList(
                        new ReserveInventoryCommand(variant2.getId(), 4),
                        new ReserveInventoryCommand(variant1.getId(), 3)
                ));
                successCount.incrementAndGet();
            } catch (Exception e) {
                // ignore
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        // Concurrency test should complete without blocking (deadlock)
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));

        executor.shutdown();

        assertEquals(2, successCount.get());

        // Reload and check stocks
        Inventory updated1 = inventoryRepository.findByVariantId(variant1.getId()).orElseThrow();
        Inventory updated2 = inventoryRepository.findByVariantId(variant2.getId()).orElseThrow();

        assertEquals(5, updated1.getAvailableStock()); // 10 - 2 - 3
        assertEquals(5, updated1.getReservedStock());  // 0 + 2 + 3

        assertEquals(3, updated2.getAvailableStock()); // 10 - 3 - 4
        assertEquals(7, updated2.getReservedStock());  // 0 + 3 + 4
    }

    @Test
    void createInventoryConcurrencyOnlyOneShouldSucceed() throws Exception {
        // Create another variant without inventory
        ProductVariant variant3 = ProductVariant.builder()
                .productId(product.getId())
                .sku("COFFEE-M-3")
                .name("Ultra Espresso")
                .price(BigDecimal.valueOf(349.99))
                .build();
        variant3 = productVariantRepository.save(variant3);

        final UUID targetVariantId = variant3.getId();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger conflictCount = new AtomicInteger(0);

        executor.submit(() -> {
            try {
                startLatch.await();
                inventoryService.createInventory(targetVariantId, 10, user.getId(), Role.SELLER);
                successCount.incrementAndGet();
            } catch (Exception e) {
                if (e instanceof AppException && ((AppException) e).getErrorCode() == ErrorCode.INVENTORY_ALREADY_EXISTS) {
                    conflictCount.incrementAndGet();
                }
            } finally {
                doneLatch.countDown();
            }
        });

        executor.submit(() -> {
            try {
                startLatch.await();
                inventoryService.createInventory(targetVariantId, 20, user.getId(), Role.SELLER);
                successCount.incrementAndGet();
            } catch (Exception e) {
                if (e instanceof AppException && ((AppException) e).getErrorCode() == ErrorCode.INVENTORY_ALREADY_EXISTS) {
                    conflictCount.incrementAndGet();
                }
            } finally {
                doneLatch.countDown();
            }
        });

        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        // One must succeed, one must fail with 409 (INVENTORY_ALREADY_EXISTS)
        assertEquals(1, successCount.get());
        assertEquals(1, conflictCount.get());

        // Verify that only one inventory row exists in DB
        Optional<Inventory> opt = inventoryRepository.findByVariantId(targetVariantId);
        assertTrue(opt.isPresent());
    }
}
