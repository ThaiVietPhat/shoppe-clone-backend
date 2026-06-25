package com.shopee.monolith.modules.search.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.search.dto.SearchResponse;
import com.shopee.monolith.modules.search.repository.ProductEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for SemanticSearchServiceImpl.
 * Verifies: happy-path ordering, manual pagination, AI-provider-down degradation.
 */
@ExtendWith(MockitoExtension.class)
class SemanticSearchServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Mock
    private ProductEmbeddingRepository productEmbeddingRepository;

    @Mock
    private ProductService productService;

    @Mock
    private com.shopee.monolith.common.observability.DemoMetrics demoMetrics;

    private SemanticSearchServiceImpl semanticSearchService;

    @BeforeEach
    void setUp() {
        lenient().when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        semanticSearchService = new SemanticSearchServiceImpl(
                embeddingModelProvider, productEmbeddingRepository, productService, demoMetrics);
    }

    // ===================== Happy path =====================

    @Test
    void searchWhenProviderSucceedsShouldReturnNonDegradedResponse() {
        float[] embedding = new float[768];
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();

        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        when(productEmbeddingRepository.findSimilarProductIdStrings(anyString(), anyInt()))
                .thenReturn(List.of(id1.toString(), id2.toString()));
        when(productService.loadActiveProductCards(any()))
                .thenReturn(List.of(buildCard(id1, "Laptop"), buildCard(id2, "Phone")));

        SearchResponse response = semanticSearchService.search("fast laptop", 0, 10);

        assertFalse(response.degraded(), "Should not be degraded when AI provider succeeds");
        assertEquals(2, response.products().items().size());
        assertEquals(2, response.products().totalElements());
    }

    @Test
    void searchShouldPassQueryTextToEmbeddingModel() {
        float[] embedding = new float[768];
        when(embeddingModel.embed("wireless earbuds")).thenReturn(embedding);
        when(productEmbeddingRepository.findSimilarProductIdStrings(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(productService.loadActiveProductCards(any())).thenReturn(List.of());

        semanticSearchService.search("wireless earbuds", 0, 10);

        verify(embeddingModel).embed("wireless earbuds");
    }

    @Test
    void searchShouldRevalidateWithProductService() {
        float[] embedding = new float[768];
        UUID id = UUID.randomUUID();

        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        when(productEmbeddingRepository.findSimilarProductIdStrings(anyString(), anyInt()))
                .thenReturn(List.of(id.toString()));
        when(productService.loadActiveProductCards(List.of(id))).thenReturn(List.of(buildCard(id, "TV")));

        SearchResponse response = semanticSearchService.search("television", 0, 10);

        verify(productService).loadActiveProductCards(List.of(id));
        assertFalse(response.degraded());
    }

    // ===================== Manual pagination =====================

    @Test
    void searchWithPaginationShouldReturnCorrectPageSlice() {
        float[] embedding = new float[768];
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        List<ProductCardResponse> cards = ids.stream()
                .map(id -> buildCard(id, "Product " + id))
                .toList();

        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        when(productEmbeddingRepository.findSimilarProductIdStrings(anyString(), anyInt()))
                .thenReturn(ids.stream().map(UUID::toString).toList());
        when(productService.loadActiveProductCards(any())).thenReturn(cards);

        // Request page 1, size 2 → should return only item index 2
        SearchResponse response = semanticSearchService.search("query", 1, 2);

        assertFalse(response.degraded());
        assertEquals(1, response.products().items().size(), "Page 1 with size 2 from 3 items should return 1 item");
        assertEquals(1, response.products().page());
        assertEquals(2, response.products().size());
    }

    @Test
    void searchWithPageBeyondResultsShouldReturnEmptyPage() {
        float[] embedding = new float[768];
        UUID id = UUID.randomUUID();

        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        when(productEmbeddingRepository.findSimilarProductIdStrings(anyString(), anyInt()))
                .thenReturn(List.of(id.toString()));
        when(productService.loadActiveProductCards(any())).thenReturn(List.of(buildCard(id, "Product")));

        // Page 5 is way beyond the single result
        SearchResponse response = semanticSearchService.search("query", 5, 10);

        assertFalse(response.degraded());
        assertTrue(response.products().items().isEmpty(), "Page beyond results should be empty");
    }

    @Test
    void searchSizeShouldBeCappedAt50() {
        float[] embedding = new float[768];
        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        when(productEmbeddingRepository.findSimilarProductIdStrings(anyString(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(productService.loadActiveProductCards(any())).thenReturn(List.of());

        SearchResponse response = semanticSearchService.search("query", 0, 200);

        // Should not throw; clamped to 50 internally
        assertNotNull(response);
        assertEquals(50, response.products().size());
    }

    // ===================== AI provider degradation =====================

    @Test
    void searchWhenEmbeddingModelThrowsShouldReturnDegradedEmptyResponse() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Gemini API unavailable"));

        SearchResponse response = semanticSearchService.search("laptop", 0, 10);

        assertTrue(response.degraded(), "Should be degraded when AI provider throws");
        assertEquals("AI_PROVIDER_UNAVAILABLE", response.degradedReason());
        assertTrue(response.products().items().isEmpty(), "Degraded response should have empty items");
        assertEquals(0, response.products().totalElements());
    }

    @Test
    void searchWhenEmbeddingModelThrowsShouldNotCallProductService() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Timeout"));

        SearchResponse response = semanticSearchService.search("query", 0, 10);

        assertTrue(response.degraded());
        verify(productService, never()).loadActiveProductCards(any());
    }

    @Test
    void searchWhenEmbeddingModelThrowsShouldNotPropagateException() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Fatal AI error"));

        // Must not throw
        assertNotNull(semanticSearchService.search("query", 0, 10));
    }

    @Test
    void searchWhenRepositoryThrowsAfterEmbedShouldReturnDegradedResponse() {
        float[] embedding = new float[768];
        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        when(productEmbeddingRepository.findSimilarProductIdStrings(anyString(), anyInt()))
                .thenThrow(new RuntimeException("pgvector query failed"));

        SearchResponse response = semanticSearchService.search("query", 0, 10);

        assertTrue(response.degraded());
        assertEquals("AI_PROVIDER_UNAVAILABLE", response.degradedReason());
    }

    @Test
    void searchDegradedResponseShouldHaveCorrectPageMetadata() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("Down"));

        SearchResponse response = semanticSearchService.search("query", 2, 5);

        assertTrue(response.degraded());
        PagedResponse<ProductCardResponse> paged = response.products();
        assertEquals(2, paged.page());
        assertEquals(5, paged.size());
        assertEquals(0, paged.totalElements());
        assertEquals(0, paged.totalPages());
        assertTrue(paged.last());
    }

    // ===================== Helpers =====================

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
