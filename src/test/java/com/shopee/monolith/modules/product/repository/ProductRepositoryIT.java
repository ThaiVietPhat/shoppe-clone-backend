package com.shopee.monolith.modules.product.repository;

import com.shopee.monolith.BasePostgresRedisIntegrationTest;
import com.shopee.monolith.modules.product.entity.Category;
import com.shopee.monolith.modules.product.entity.Product;
import com.shopee.monolith.modules.product.entity.ProductVariant;
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

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductRepositoryIT extends BasePostgresRedisIntegrationTest {

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

    private User user;
    private Shop shop;
    private Category parentCategory;
    private Category subCategory;
    private Product product;

    @BeforeEach
    void setUp() {
        tearDown();
        user = User.builder()
                .email("seller.catalog@shoppe.local")
                .normalizedEmail("seller.catalog@shoppe.local")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();
        user = userRepository.save(user);

        shop = Shop.builder()
                .ownerId(user.getId())
                .name("Seller Catalog Shop")
                .build();
        shop = shopRepository.save(shop);

        parentCategory = Category.builder()
                .name("Electronics")
                .build();
        parentCategory = categoryRepository.save(parentCategory);

        subCategory = Category.builder()
                .parentId(parentCategory.getId())
                .name("Smartphones")
                .build();
        subCategory = categoryRepository.save(subCategory);

        product = Product.builder()
                .shopId(shop.getId())
                .categoryId(subCategory.getId())
                .name("iPhone 15 Pro")
                .description("Titanium body")
                .build();
        product = productRepository.save(product);
    }

    @AfterEach
    void tearDown() {
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        if (subCategory != null) {
            categoryRepository.delete(subCategory);
        }
        if (parentCategory != null) {
            categoryRepository.delete(parentCategory);
        }
        shopRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void saveCategoryAndProductShouldPopulateAuditFields() {
        assertNotNull(subCategory.getId());
        assertNotNull(subCategory.getCreatedAt());
        assertNotNull(subCategory.getUpdatedAt());

        assertNotNull(product.getId());
        assertNotNull(product.getCreatedAt());
        assertNotNull(product.getUpdatedAt());
    }

    @Test
    void saveVariantWhenSkuExistsShouldThrowDataIntegrityViolation() {
        ProductVariant variant1 = ProductVariant.builder()
                .productId(product.getId())
                .sku("IPHONE15PRO-128")
                .name("128GB Gold")
                .price(BigDecimal.valueOf(999.00))
                .build();
        productVariantRepository.save(variant1);

        ProductVariant variant2 = ProductVariant.builder()
                .productId(product.getId())
                .sku("IPHONE15PRO-128")
                .name("128GB Titanium")
                .price(BigDecimal.valueOf(999.00))
                .build();

        assertThrows(DataIntegrityViolationException.class, () -> productVariantRepository.saveAndFlush(variant2));
    }
}
