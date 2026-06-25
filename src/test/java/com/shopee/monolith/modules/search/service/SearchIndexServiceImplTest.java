package com.shopee.monolith.modules.search.service;

import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.event.ProductCatalogSnapshotEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SearchIndexServiceImplTest {

    @Mock
    private ElasticsearchOperations elasticsearchOperations;

    @Mock
    private ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;

    private SearchIndexServiceImpl searchIndexService;

    @BeforeEach
    void setUp() {
        lenient().when(elasticsearchOperationsProvider.getIfAvailable()).thenReturn(elasticsearchOperations);
        searchIndexService = new SearchIndexServiceImpl(elasticsearchOperationsProvider);
    }

    private final UUID productId = UUID.randomUUID();
    private final UUID shopId = UUID.randomUUID();

    // ===================== upsertDocument =====================

    @Test
    void upsertDocumentWhenIndexExistsShouldNotThrow() {
        IndexOperations indexOps = mock(IndexOperations.class);
        when(elasticsearchOperations.indexOps(any(Class.class))).thenReturn(indexOps);
        when(indexOps.exists()).thenReturn(true);

        assertDoesNotThrow(() -> searchIndexService.upsertDocument(buildEvent("Laptop", "Fast laptop")));
    }

    @Test
    void upsertDocumentWhenIndexAbsentShouldCreateIndexAndNotThrow() {
        IndexOperations indexOps = mock(IndexOperations.class);
        when(elasticsearchOperations.indexOps(any(Class.class))).thenReturn(indexOps);
        when(indexOps.exists()).thenReturn(false);

        assertDoesNotThrow(() -> searchIndexService.upsertDocument(buildEvent("Phone", "Smart phone")));
    }

    @Test
    void upsertDocumentWhenSaveThrowsShouldNotPropagateException() {
        IndexOperations indexOps = mock(IndexOperations.class);
        when(elasticsearchOperations.indexOps(any(Class.class))).thenReturn(indexOps);
        when(indexOps.exists()).thenReturn(true);
        doThrow(new RuntimeException("ES unavailable"))
                .when(elasticsearchOperations).save(any(), any());

        assertDoesNotThrow(() -> searchIndexService.upsertDocument(buildEvent("TV", "4K TV")));
    }

    @Test
    void upsertDocumentWhenIndexCheckThrowsShouldNotPropagateException() {
        IndexOperations indexOps = mock(IndexOperations.class);
        when(elasticsearchOperations.indexOps(any(Class.class))).thenReturn(indexOps);
        doThrow(new RuntimeException("ES down")).when(indexOps).exists();

        assertDoesNotThrow(() -> searchIndexService.upsertDocument(buildEvent("Camera", "Digital camera")));
    }

    @Test
    void upsertDocumentWhenNullFieldsShouldNotThrow() {
        IndexOperations indexOps = mock(IndexOperations.class);
        when(elasticsearchOperations.indexOps(any(Class.class))).thenReturn(indexOps);
        when(indexOps.exists()).thenReturn(true);

        ProductCatalogSnapshotEvent eventWithNulls = new ProductCatalogSnapshotEvent(
                productId, null, ProductStatus.ACTIVE, "Product",
                null, null, null, null, null,
                BigDecimal.valueOf(100), BigDecimal.valueOf(100),
                null, null, null, null, null, null, true, List.of(), List.of());

        assertDoesNotThrow(() -> searchIndexService.upsertDocument(eventWithNulls));
    }

    // ===================== deleteDocument =====================

    @Test
    void deleteDocumentShouldNotThrow() {
        assertDoesNotThrow(() -> searchIndexService.deleteDocument(productId));
    }

    @Test
    void deleteDocumentWhenDeleteThrowsShouldNotPropagateException() {
        doThrow(new RuntimeException("ES delete failed"))
                .when(elasticsearchOperations)
                .delete(any(String.class), any(org.springframework.data.elasticsearch.core.mapping.IndexCoordinates.class));

        assertDoesNotThrow(() -> searchIndexService.deleteDocument(productId));
    }

    // ===================== Helpers =====================

    private ProductCatalogSnapshotEvent buildEvent(String name, String description) {
        return new ProductCatalogSnapshotEvent(
                productId, shopId, ProductStatus.ACTIVE, name, description,
                "Electronics", "Brand", "SKU-001", Map.of(),
                BigDecimal.valueOf(100), BigDecimal.valueOf(200),
                null, null, null, null, "Test Shop", null, true, List.of(), List.of());
    }
}
