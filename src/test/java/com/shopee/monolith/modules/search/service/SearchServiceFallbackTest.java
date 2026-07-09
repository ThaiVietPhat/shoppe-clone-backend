package com.shopee.monolith.modules.search.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.entity.Product;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.repository.ProductRepository;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.search.dto.SearchRequest;
import com.shopee.monolith.modules.search.dto.SearchResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests verifying that SearchServiceImpl falls back to PostgreSQL when
 * Elasticsearch throws any exception, and that the degraded flag is set correctly.
 */
@ExtendWith(MockitoExtension.class)
class SearchServiceFallbackTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;

    @Mock
    private ProductService productService;

    @Mock
    private ProductRepository productRepository;

    @Mock
    private com.shopee.monolith.common.observability.DemoMetrics demoMetrics;

    private SearchServiceImpl searchService;

    @BeforeEach
    void setUp() {
        lenient().when(elasticsearchOperationsProvider.getIfAvailable()).thenReturn(elasticsearchOperations);
        searchService = new SearchServiceImpl(elasticsearchOperationsProvider, productService, productRepository, demoMetrics);
    }

    // ===================== Elasticsearch success path =====================

    @Test
    void searchWhenElasticsearchSucceedsShouldReturnNonDegradedResponse() {
        UUID productId = UUID.randomUUID();
        SearchRequest request = new SearchRequest("laptop", null, null, null, null, "RELEVANCE", 0, 10);

        // Stub ES to return one hit
        org.springframework.data.elasticsearch.core.SearchHits<com.shopee.monolith.modules.search.document.ProductDocument> hits
                = mockSearchHits(List.of(productId));
        doReturn(hits).when(elasticsearchOperations).search(any(NativeQuery.class), any(Class.class));

        ProductCardResponse card = buildCard(productId, "Laptop");
        when(productService.loadActiveProductCards(List.of(productId))).thenReturn(List.of(card));

        SearchResponse response = searchService.search(request);

        assertFalse(response.degraded(), "Should not be degraded when ES succeeds");
        assertEquals(1, response.products().items().size());
    }

    @Test
    void searchWhenElasticsearchHitIsStaleShouldExcludeFromTotalElements() {
        UUID productId = UUID.randomUUID();
        SearchRequest request = new SearchRequest("mouse", null, null, null, null, "RELEVANCE", 0, 10);

        // ES reports 1 hit, but the product no longer exists/isn't ACTIVE in Postgres
        org.springframework.data.elasticsearch.core.SearchHits<com.shopee.monolith.modules.search.document.ProductDocument> hits
                = mockSearchHits(List.of(productId));
        doReturn(hits).when(elasticsearchOperations).search(any(NativeQuery.class), any(Class.class));
        when(productService.loadActiveProductCards(List.of(productId))).thenReturn(List.of());

        SearchResponse response = searchService.search(request);

        PagedResponse<ProductCardResponse> paged = response.products();
        assertTrue(paged.items().isEmpty());
        assertEquals(0, paged.totalElements(), "Stale ES hit must not be counted in totalElements");
        assertEquals(0, paged.totalPages());
    }

    // ===================== Elasticsearch fallback path =====================

    @Test
    void searchWhenElasticsearchThrowsShouldFallBackToDatabase() {
        SearchRequest request = new SearchRequest("phone", null, null, null, null, "RELEVANCE", 0, 10);

        doThrow(new RuntimeException("ES connection refused"))
                .when(elasticsearchOperations).search(any(NativeQuery.class), any(Class.class));

        UUID productId = UUID.randomUUID();
        Product product = buildProduct(productId, "Phone");
        Page<Product> dbPage = new PageImpl<>(List.of(product));
        when(productRepository.findAllByStatusAndKeyword(
                eq(ProductStatus.ACTIVE), eq("phone"), any(Pageable.class)))
                .thenReturn(dbPage);

        ProductCardResponse card = buildCard(productId, "Phone");
        when(productService.loadActiveProductCards(List.of(productId))).thenReturn(List.of(card));

        SearchResponse response = searchService.search(request);

        assertTrue(response.degraded(), "Should be degraded when ES is unavailable");
        assertEquals("ELASTICSEARCH_UNAVAILABLE", response.degradedReason());
        assertEquals(1, response.products().items().size());
        assertEquals("Phone", response.products().items().get(0).name());
    }

    @Test
    void searchWhenElasticsearchThrowsAndNoKeywordShouldUseFindAllByStatus() {
        SearchRequest request = new SearchRequest(null, null, null, null, null, "RELEVANCE", 0, 10);

        doThrow(new RuntimeException("ES timeout"))
                .when(elasticsearchOperations).search(any(NativeQuery.class), any(Class.class));

        Page<Product> dbPage = new PageImpl<>(Collections.emptyList());
        when(productRepository.findAllByStatus(eq(ProductStatus.ACTIVE), any(Pageable.class)))
                .thenReturn(dbPage);
        when(productService.loadActiveProductCards(List.of())).thenReturn(List.of());

        SearchResponse response = searchService.search(request);

        assertTrue(response.degraded());
        verify(productRepository).findAllByStatus(eq(ProductStatus.ACTIVE), any(Pageable.class));
    }

    @Test
    void searchWhenElasticsearchThrowsShouldNotPropagateException() {
        SearchRequest request = new SearchRequest("tv", null, null, null, null, "RELEVANCE", 0, 10);

        doThrow(new RuntimeException("Fatal ES error"))
                .when(elasticsearchOperations).search(any(NativeQuery.class), any(Class.class));

        Page<Product> dbPage = new PageImpl<>(Collections.emptyList());
        when(productRepository.findAllByStatusAndKeyword(any(), anyString(), any()))
                .thenReturn(dbPage);
        when(productService.loadActiveProductCards(any())).thenReturn(List.of());

        // Must not throw
        SearchResponse response = searchService.search(request);
        assertNotNull(response);
    }

    @Test
    void searchDegradedResponseShouldContainValidPagedStructure() {
        SearchRequest request = new SearchRequest("camera", null, null, null, null, "NEWEST", 0, 5);

        doThrow(new RuntimeException("ES down"))
                .when(elasticsearchOperations).search(any(NativeQuery.class), any(Class.class));

        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        Product p1 = buildProduct(id1, "Camera A");
        Product p2 = buildProduct(id2, "Camera B");
        Page<Product> dbPage = new PageImpl<>(List.of(p1, p2));
        when(productRepository.findAllByStatusAndKeyword(any(), anyString(), any())).thenReturn(dbPage);
        when(productService.loadActiveProductCards(any()))
                .thenReturn(List.of(buildCard(id1, "Camera A"), buildCard(id2, "Camera B")));

        SearchResponse response = searchService.search(request);

        assertTrue(response.degraded());
        PagedResponse<ProductCardResponse> paged = response.products();
        assertEquals(2, paged.totalElements());
        assertEquals(0, paged.page());
        assertEquals(2, paged.items().size());
    }

    // ===================== Helpers =====================

    private org.springframework.data.elasticsearch.core.SearchHits<com.shopee.monolith.modules.search.document.ProductDocument>
            mockSearchHits(List<UUID> productIds) {
        com.shopee.monolith.modules.search.document.ProductDocument doc =
                com.shopee.monolith.modules.search.document.ProductDocument.builder()
                        .productId(productIds.get(0).toString())
                        .build();

        @SuppressWarnings("unchecked")
        org.springframework.data.elasticsearch.core.SearchHit<com.shopee.monolith.modules.search.document.ProductDocument>
                hit = mock(org.springframework.data.elasticsearch.core.SearchHit.class);
        when(hit.getContent()).thenReturn(doc);

        @SuppressWarnings("unchecked")
        org.springframework.data.elasticsearch.core.SearchHits<com.shopee.monolith.modules.search.document.ProductDocument>
                hits = mock(org.springframework.data.elasticsearch.core.SearchHits.class);
        when(hits.stream()).thenReturn(List.of(hit).stream());
        when(hits.getTotalHits()).thenReturn((long) productIds.size());
        return hits;
    }

    private Product buildProduct(UUID id, String name) {
        Product p = Product.builder()
                .shopId(UUID.randomUUID())
                .categoryId(UUID.randomUUID())
                .name(name)
                .status(ProductStatus.ACTIVE)
                .minPrice(BigDecimal.valueOf(100))
                .maxPrice(BigDecimal.valueOf(100))
                .build();
        // Reflectively set the ID so repository results return proper IDs
        try {
            java.lang.reflect.Field idField = com.shopee.monolith.common.entity.BaseEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(p, id);
        } catch (Exception ignored) {
            // If reflection fails, getId() returns null but test still verifies degraded flag
        }
        return p;
    }

    private ProductCardResponse buildCard(UUID productId, String name) {
        return ProductCardResponse.builder()
                .id(productId)
                .name(name)
                .status(ProductStatus.ACTIVE)
                .minPrice(BigDecimal.valueOf(100))
                .maxPrice(BigDecimal.valueOf(100))
                .build();
    }
}
