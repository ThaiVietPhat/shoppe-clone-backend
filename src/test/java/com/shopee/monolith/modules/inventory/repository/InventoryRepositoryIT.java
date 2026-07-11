package com.shopee.monolith.modules.inventory.repository;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.modules.inventory.entity.Inventory;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InventoryRepositoryIT extends BasePostgresRedisIntegrationTest {

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
    private TransactionTemplate transactionTemplate;

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
                .email("seller.inventory@shoppe.local")
                .normalizedEmail("seller.inventory@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        user = userRepository.save(user);

        shop = Shop.builder()
                .ownerId(user.getId())
                .name("Seller Inventory Shop")
                .build();
        shop = shopRepository.save(shop);

        category = Category.builder()
                .name("Home Goods")
                .build();
        category = categoryRepository.save(category);

        product = Product.builder()
                .shopId(shop.getId())
                .categoryId(category.getId())
                .name("Table Lamp")
                .description("Warm LED light")
                .build();
        product = productRepository.save(product);

        variant1 = ProductVariant.builder()
                .productId(product.getId())
                .sku("LAMP-LED-WHITE")
                .name("White LED")
                .price(BigDecimal.valueOf(19.99))
                .build();
        variant1 = productVariantRepository.save(variant1);

        variant2 = ProductVariant.builder()
                .productId(product.getId())
                .sku("LAMP-LED-BLACK")
                .name("Black LED")
                .price(BigDecimal.valueOf(19.99))
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
    void saveInventoryWhenValidShouldSucceed() {
        Inventory inventory = Inventory.builder()
                .variantId(variant1.getId())
                .availableStock(10)
                .reservedStock(0)
                .build();

        inventory = inventoryRepository.save(inventory);

        assertNotNull(inventory.getId());
        assertNotNull(inventory.getCreatedAt());
        assertNotNull(inventory.getUpdatedAt());
        assertEquals(variant1.getId(), inventory.getVariantId());
    }

    @Test
    void saveInventoryWhenDuplicateVariantIdShouldThrowException() {
        Inventory inventory1 = Inventory.builder()
                .variantId(variant1.getId())
                .availableStock(10)
                .reservedStock(0)
                .build();
        inventoryRepository.save(inventory1);

        Inventory inventory2 = Inventory.builder()
                .variantId(variant1.getId())
                .availableStock(5)
                .reservedStock(0)
                .build();

        assertThrows(DataIntegrityViolationException.class, () -> inventoryRepository.saveAndFlush(inventory2));
    }

    @Test
    void saveInventoryWhenNegativeAvailableStockShouldThrowException() {
        Inventory inventory = Inventory.builder()
                .variantId(variant1.getId())
                .availableStock(-1)
                .reservedStock(0)
                .build();

        assertThrows(DataIntegrityViolationException.class, () -> inventoryRepository.saveAndFlush(inventory));
    }

    @Test
    void saveInventoryWhenNegativeReservedStockShouldThrowException() {
        Inventory inventory = Inventory.builder()
                .variantId(variant1.getId())
                .availableStock(10)
                .reservedStock(-1)
                .build();

        assertThrows(DataIntegrityViolationException.class, () -> inventoryRepository.saveAndFlush(inventory));
    }

    @Test
    void findByVariantIdForUpdateShouldAcquireLock() {
        Inventory inventory = Inventory.builder()
                .variantId(variant1.getId())
                .availableStock(10)
                .reservedStock(0)
                .build();
        inventoryRepository.saveAndFlush(inventory);

        transactionTemplate.execute(status -> {
            Optional<Inventory> locked = inventoryRepository.findByVariantIdForUpdate(variant1.getId());
            assertTrue(locked.isPresent());
            assertEquals(10, locked.get().getAvailableStock());
            return null;
        });
    }

    @Test
    void findAllByVariantIdInForUpdateShouldReturnSortedAndLockedInventories() {
        Inventory inv1 = Inventory.builder()
                .variantId(variant1.getId())
                .availableStock(10)
                .reservedStock(0)
                .build();
        Inventory inv2 = Inventory.builder()
                .variantId(variant2.getId())
                .availableStock(20)
                .reservedStock(0)
                .build();

        inventoryRepository.saveAllAndFlush(Arrays.asList(inv1, inv2));

        transactionTemplate.execute(status -> {
            // Locking multiple variants in sorted order
            List<Inventory> locked = inventoryRepository.findAllByVariantIdInForUpdate(
                    Arrays.asList(variant2.getId(), variant1.getId())
            );

            assertEquals(2, locked.size());
            // Assert that results are sorted by variantId ASC
            UUID firstId = locked.get(0).getVariantId();
            UUID secondId = locked.get(1).getVariantId();
            assertTrue(firstId.toString().compareTo(secondId.toString()) < 0);
            return null;
        });
    }
}
