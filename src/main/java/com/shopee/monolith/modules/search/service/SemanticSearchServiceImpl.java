package com.shopee.monolith.modules.search.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.search.dto.SearchResponse;
import com.shopee.monolith.modules.search.repository.ProductEmbeddingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SemanticSearchServiceImpl implements SemanticSearchService {

    private static final String DEGRADED_REASON_AI = "AI_PROVIDER_UNAVAILABLE";
    /** Fetch more candidates than requested to account for revalidation filtering. */
    private static final int CANDIDATE_MULTIPLIER = 3;

    private final EmbeddingModel embeddingModel;
    private final ProductEmbeddingRepository productEmbeddingRepository;
    private final ProductService productService;
    private final com.shopee.monolith.common.observability.DemoMetrics demoMetrics;

    @Override
    public SearchResponse search(String query, int page, int size) {
        int clampedSize = Math.min(size, 50);
        int offset = page * clampedSize;
        int candidateLimit = (offset + clampedSize) * CANDIDATE_MULTIPLIER;

        try {
            return searchWithEmbedding(query, page, clampedSize, candidateLimit);
        } catch (Exception ex) {
            log.warn("Semantic search failed (AI provider unavailable): {}", ex.getMessage());
            return buildEmptyDegradedResponse(page, clampedSize);
        }
    }

    private SearchResponse searchWithEmbedding(String query, int page, int size, int candidateLimit) {
        float[] queryEmbedding = embeddingModel.embed(query);
        String vectorLiteral = EmbeddingIndexServiceImpl.toVectorLiteral(queryEmbedding);

        List<UUID> candidateIds = productEmbeddingRepository.findSimilarProductIdStrings(vectorLiteral, candidateLimit)
                .stream().map(UUID::fromString).toList();

        // Revalidate against live DB: remove non-ACTIVE, hydrate full card
        List<ProductCardResponse> allCards = productService.loadActiveProductCards(candidateIds);

        // Manual pagination over revalidated list (pgvector returns ordered candidates)
        int offset = page * size;
        List<ProductCardResponse> pageItems = offset >= allCards.size()
                ? Collections.emptyList()
                : allCards.subList(offset, Math.min(offset + size, allCards.size()));

        int totalPages = allCards.isEmpty() ? 0 : (int) Math.ceil((double) allCards.size() / size);
        PagedResponse<ProductCardResponse> paged = PagedResponse.<ProductCardResponse>builder()
                .items(pageItems)
                .page(page)
                .size(size)
                .totalElements(allCards.size())
                .totalPages(totalPages)
                .last(page >= totalPages - 1)
                .build();

        return SearchResponse.ok(paged);
    }

    private SearchResponse buildEmptyDegradedResponse(int page, int size) {
        PagedResponse<ProductCardResponse> empty = PagedResponse.<ProductCardResponse>builder()
                .items(Collections.emptyList())
                .page(page)
                .size(size)
                .totalElements(0)
                .totalPages(0)
                .last(true)
                .build();
        demoMetrics.incrementAiFallback();
        return SearchResponse.degraded(empty, DEGRADED_REASON_AI);
    }
}
