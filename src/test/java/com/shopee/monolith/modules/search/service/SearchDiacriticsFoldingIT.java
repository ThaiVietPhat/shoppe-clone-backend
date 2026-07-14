package com.shopee.monolith.modules.search.service;

import com.shopee.monolith.BaseIntegrationTest;
import com.shopee.monolith.modules.product.entity.Product;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.entity.ProductVariant;
import com.shopee.monolith.modules.product.event.ProductCatalogSnapshotEvent;
import com.shopee.monolith.modules.product.repository.ProductRepository;
import com.shopee.monolith.modules.product.repository.ProductVariantRepository;
import com.shopee.monolith.modules.search.document.ProductDocument;
import com.shopee.monolith.modules.search.dto.SearchRequest;
import com.shopee.monolith.modules.search.dto.SearchResponse;
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
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vietnamese buyers very commonly type search queries without dấu (diacritics) —
 * this verifies the {@code vi_folding} analyzer on {@link ProductDocument} makes
 * "chuot" match a product named "Chuột không dây" the same way the fully-accented
 * query does, instead of returning zero results.
 */
class SearchDiacriticsFoldingIT extends BaseIntegrationTest {

    @Autowired
    private UserRepository userRepository;
    @Autowired
    private ShopRepository shopRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private ProductVariantRepository productVariantRepository;
    @Autowired
    private SearchIndexService searchIndexService;
    @Autowired
    private SearchService searchService;
    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    private User seller;
    private Shop shop;
    private Product product;

    @BeforeEach
    void setUp() {
        tearDown();

        seller = userRepository.save(User.builder()
                .email("seller-diacritics@shoppe.local")
                .normalizedEmail("seller-diacritics@shoppe.local")
                .role(Role.SELLER)
                .status(UserStatus.ACTIVE)
                .build());

        shop = shopRepository.save(Shop.builder()
                .ownerId(seller.getId())
                .name("Diacritics Test Shop")
                .build());

        product = productRepository.save(Product.builder()
                .shopId(shop.getId())
                .name("Chuột không dây")
                .status(ProductStatus.ACTIVE)
                .minPrice(BigDecimal.valueOf(250000))
                .maxPrice(BigDecimal.valueOf(250000))
                .build());

        productVariantRepository.save(ProductVariant.builder()
                .productId(product.getId())
                .sku("MOUSE-001")
                .name("Standard")
                .price(BigDecimal.valueOf(250000))
                .build());

        ProductCatalogSnapshotEvent event = new ProductCatalogSnapshotEvent(
                product.getId(), shop.getId(), ProductStatus.ACTIVE,
                "Chuột không dây", null, null, null, null, null,
                BigDecimal.valueOf(250000), BigDecimal.valueOf(250000),
                null, null, null, null,
                shop.getName(), BigDecimal.ZERO,
                true, List.of(), List.of());
        searchIndexService.upsertDocument(event);
        elasticsearchOperations.indexOps(ProductDocument.class).refresh();
    }

    @AfterEach
    void tearDown() {
        if (product != null && product.getId() != null) {
            searchIndexService.deleteDocument(product.getId());
        }
        productVariantRepository.deleteAll();
        productRepository.deleteAll();
        shopRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void searchWithoutDiacriticsShouldMatchAccentedProductName() {
        SearchResponse response = searchService.search(
                new SearchRequest("chuot khong day", null, null, null, null, null, 0, 20));

        assertFalse(response.degraded());
        assertEquals(1, response.products().totalElements());
        assertTrue(response.products().items().stream().anyMatch(p -> p.id().equals(product.getId())));
    }

    @Test
    void searchWithDiacriticsShouldStillMatch() {
        SearchResponse response = searchService.search(
                new SearchRequest("chuột không dây", null, null, null, null, null, 0, 20));

        assertFalse(response.degraded());
        assertEquals(1, response.products().totalElements());
        assertTrue(response.products().items().stream().anyMatch(p -> p.id().equals(product.getId())));
    }
}
