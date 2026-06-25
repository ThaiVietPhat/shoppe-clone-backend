package com.shopee.monolith.modules.search.service;

import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.event.ProductCatalogSnapshotEvent;
import com.shopee.monolith.modules.search.repository.ProductEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EmbeddingIndexServiceTest {

    @Mock
    private EmbeddingModel embeddingModel;

    @Mock
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;

    @Mock
    private ProductEmbeddingRepository productEmbeddingRepository;

    @Mock
    private com.shopee.monolith.common.observability.DemoMetrics demoMetrics;

    private EmbeddingIndexServiceImpl embeddingIndexService;

    private UUID productId;
    private UUID shopId;

    @BeforeEach
    void setUp() {
        lenient().when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        embeddingIndexService = new EmbeddingIndexServiceImpl(embeddingModelProvider, productEmbeddingRepository, demoMetrics);
        productId = UUID.randomUUID();
        shopId = UUID.randomUUID();
    }

    // ===================== buildEmbeddingText — no PII =====================

    @Test
    void buildEmbeddingTextShouldContainPublicCatalogFields() {
        ProductCatalogSnapshotEvent event = buildEvent(
                "iPhone 15 Pro", "Titanium body smartphone", "Electronics/Phones", "Apple",
                Map.of("color", "Black", "storage", "256GB"));

        String text = embeddingIndexService.buildEmbeddingText(event);

        assertTrue(text.contains("iPhone 15 Pro"), "Should include product name");
        assertTrue(text.contains("Apple"), "Should include brand");
        assertTrue(text.contains("Electronics/Phones"), "Should include category path");
        assertTrue(text.contains("Titanium body"), "Should include description excerpt");
        assertTrue(text.contains("color: Black") || text.contains("storage: 256GB"),
                "Should include attribute key-value pairs");
    }

    @Test
    void buildEmbeddingTextShouldNotContainPiiFields() {
        // We verify that none of the user/PII context fields are included.
        // The event itself does not carry user email/phone/address/payment, but we
        // assert that shopId (UUID with no business meaning) is not embedded either.
        ProductCatalogSnapshotEvent event = buildEvent("Laptop", "Fast laptop", "Computers", "Dell", Map.of());

        String text = embeddingIndexService.buildEmbeddingText(event);

        assertFalse(text.contains(shopId.toString()),
                "Embedding text must not contain shopId UUID");
        assertFalse(text.contains(productId.toString()),
                "Embedding text must not contain productId UUID");
    }

    @Test
    void buildEmbeddingTextWithNullOptionalFieldsShouldNotThrow() {
        ProductCatalogSnapshotEvent event = buildEvent("Gadget", null, null, null, null);

        assertDoesNotThrow(() -> embeddingIndexService.buildEmbeddingText(event));
    }

    @Test
    void buildEmbeddingTextWithLongDescriptionShouldTruncateTo500Chars() {
        String longDescription = "A".repeat(1000);
        ProductCatalogSnapshotEvent event = buildEvent("Product", longDescription, "Cat", "Brand", Map.of());

        String text = embeddingIndexService.buildEmbeddingText(event);

        // The truncated description is 500 chars, so total should be less than 1000 + overhead
        assertTrue(text.length() < 700, "Long description should be truncated");
        assertFalse(text.contains("A".repeat(600)), "Truncated to 500 chars max");
    }

    @Test
    void buildEmbeddingTextWithEmptyNameShouldReturnBlankText() {
        ProductCatalogSnapshotEvent event = buildEvent("", null, null, null, null);

        String text = embeddingIndexService.buildEmbeddingText(event);

        assertTrue(text.isBlank(), "Empty name with no other fields should produce blank text");
    }

    // ===================== indexProduct — AI provider failure =====================

    @Test
    void indexProductWhenProviderSucceedsShouldUpsertEmbedding() {
        float[] embedding = new float[768];
        when(embeddingModel.embed(anyString())).thenReturn(embedding);

        ProductCatalogSnapshotEvent event = buildEvent("TV", "4K TV", "Electronics", "Samsung", Map.of());

        assertDoesNotThrow(() -> embeddingIndexService.indexProduct(event));

        verify(productEmbeddingRepository, times(1)).upsert(any(), anyString(), anyString(), any());
    }

    @Test
    void indexProductWhenProviderThrowsShouldNotPropagateException() {
        when(embeddingModel.embed(anyString())).thenThrow(new RuntimeException("AI provider unavailable"));

        ProductCatalogSnapshotEvent event = buildEvent("TV", "4K TV", "Electronics", "Samsung", Map.of());

        // Must not throw — provider failures are logged and swallowed
        assertDoesNotThrow(() -> embeddingIndexService.indexProduct(event));
        verify(productEmbeddingRepository, never()).upsert(any(), anyString(), anyString(), any());
    }

    @Test
    void indexProductWhenRepositoryThrowsShouldNotPropagateException() {
        float[] embedding = new float[768];
        when(embeddingModel.embed(anyString())).thenReturn(embedding);
        doThrow(new RuntimeException("DB upsert failed"))
                .when(productEmbeddingRepository).upsert(any(), anyString(), anyString(), any());

        ProductCatalogSnapshotEvent event = buildEvent("TV", "4K TV", "Electronics", "Samsung", Map.of());

        assertDoesNotThrow(() -> embeddingIndexService.indexProduct(event));
    }

    @Test
    void indexProductWhenEmbeddingTextIsBlankShouldSkipEmbeddingCall() {
        ProductCatalogSnapshotEvent event = buildEvent("", null, null, null, null);

        embeddingIndexService.indexProduct(event);

        verify(embeddingModel, never()).embed(anyString());
        verify(productEmbeddingRepository, never()).upsert(any(), anyString(), anyString(), any());
    }

    // ===================== removeProduct =====================

    @Test
    void removeProductWhenSuccessfulShouldDeleteById() {
        assertDoesNotThrow(() -> embeddingIndexService.removeProduct(productId));

        verify(productEmbeddingRepository, times(1)).deleteById(productId);
    }

    @Test
    void removeProductWhenRepositoryThrowsShouldNotPropagateException() {
        doThrow(new RuntimeException("DB error"))
                .when(productEmbeddingRepository).deleteById(productId);

        assertDoesNotThrow(() -> embeddingIndexService.removeProduct(productId));
    }

    // ===================== toVectorLiteral =====================

    @Test
    void toVectorLiteralShouldProduceValidPgvectorFormat() {
        float[] vector = {0.1f, 0.2f, 0.3f};
        String literal = EmbeddingIndexServiceImpl.toVectorLiteral(vector);

        assertTrue(literal.startsWith("["), "Should start with [");
        assertTrue(literal.endsWith("]"), "Should end with ]");
        assertTrue(literal.contains("0.1"), "Should contain first element");
        assertTrue(literal.contains("0.3"), "Should contain last element");
    }

    // ===================== Helpers =====================

    private ProductCatalogSnapshotEvent buildEvent(
            String name,
            String description,
            String categoryPath,
            String brand,
            Map<String, Object> attributes) {
        return new ProductCatalogSnapshotEvent(
                productId,
                shopId,
                ProductStatus.ACTIVE,
                name,
                description,
                categoryPath,
                brand,
                "SKU-001",
                attributes,
                BigDecimal.valueOf(100),
                BigDecimal.valueOf(100),
                null,
                null,
                null,
                null,
                "Test Shop",
                null,
                true,
                List.of(),
                List.of()
        );
    }
}
