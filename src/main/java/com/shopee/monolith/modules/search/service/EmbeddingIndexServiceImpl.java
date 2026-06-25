package com.shopee.monolith.modules.search.service;

import com.shopee.monolith.modules.product.event.ProductCatalogSnapshotEvent;
import com.shopee.monolith.modules.search.repository.ProductEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingIndexServiceImpl implements EmbeddingIndexService {

    static final String MODEL_VERSION = "text-embedding-004";
    /** Maximum characters taken from description to keep prompt size bounded. */
    private static final int MAX_DESCRIPTION_CHARS = 500;

    // Optional: not every environment configures a Google AI key (e.g. unrelated IT suites).
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ProductEmbeddingRepository productEmbeddingRepository;
    private final com.shopee.monolith.common.observability.DemoMetrics demoMetrics;

    @Override
    @Transactional
    public void indexProduct(ProductCatalogSnapshotEvent event) {
        String text = buildEmbeddingText(event);
        if (text.isBlank()) {
            log.debug("Skipping embedding for productId={}: empty text", event.productId());
            return;
        }
        try {
            EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
            if (embeddingModel == null) {
                throw new IllegalStateException("Embedding model is not available");
            }
            float[] embedding = embeddingModel.embed(text);
            String pgvectorLiteral = toVectorLiteral(embedding);
            productEmbeddingRepository.upsert(event.productId(), pgvectorLiteral, MODEL_VERSION, Instant.now());
            log.debug("Embedding upsert OK productId={}", event.productId());
        } catch (Exception ex) {
            // Do not rethrow: AI provider failures must not crash the event listener.
            // Spring Modulith publication log will replay after provider recovers.
            log.warn("Embedding indexing failed for productId={} — will retry via publication replay: {}",
                    event.productId(), ex.getMessage());
            demoMetrics.incrementIndexingFailure();
        }
    }

    @Override
    @Transactional
    public void removeProduct(UUID productId) {
        try {
            productEmbeddingRepository.deleteById(productId);
            log.debug("Embedding removed for productId={}", productId);
        } catch (Exception ex) {
            log.warn("Embedding removal failed for productId={}: {}", productId, ex.getMessage());
        }
    }

    // ===================== Helpers =====================

    /**
     * Builds the embedding input text from public catalog fields only.
     * Deliberately excludes: userId, email, phone, address, payment data, session data.
     */
    String buildEmbeddingText(ProductCatalogSnapshotEvent event) {
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, event.name());
        appendIfPresent(sb, event.brand());
        appendIfPresent(sb, event.categoryPath());
        if (event.description() != null && !event.description().isBlank()) {
            String truncated = event.description().length() > MAX_DESCRIPTION_CHARS
                    ? event.description().substring(0, MAX_DESCRIPTION_CHARS)
                    : event.description();
            sb.append(truncated).append(". ");
        }
        if (event.attributes() != null && !event.attributes().isEmpty()) {
            String attrs = event.attributes().entrySet().stream()
                    .map(e -> e.getKey() + ": " + e.getValue())
                    .collect(Collectors.joining(", "));
            sb.append("Attributes: ").append(attrs).append(".");
        }
        return sb.toString().trim();
    }

    private void appendIfPresent(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            sb.append(value).append(". ");
        }
    }

    /**
     * Converts a float[] to a pgvector literal string: "[v1,v2,...,vN]".
     */
    public static String toVectorLiteral(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append(vector[i]);
        }
        sb.append("]");
        return sb.toString();
    }
}
