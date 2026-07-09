package com.shopee.monolith.modules.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.entity.Product;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.repository.ProductRepository;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.search.document.ProductDocument;
import com.shopee.monolith.modules.search.dto.SearchRequest;
import com.shopee.monolith.modules.search.dto.SearchResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SearchServiceImpl implements SearchService {

    private static final String DEGRADED_REASON_ES = "ELASTICSEARCH_UNAVAILABLE";

    // Optional: tests disable Elasticsearch autoconfiguration to speed up unrelated IT suites.
    private final ObjectProvider<ElasticsearchOperations> elasticsearchOperationsProvider;
    private final ProductService productService;
    private final ProductRepository productRepository;
    private final com.shopee.monolith.common.observability.DemoMetrics demoMetrics;

    @Override
    public SearchResponse search(SearchRequest request) {
        try {
            return searchViaElasticsearch(request);
        } catch (Exception ex) {
            log.warn("Elasticsearch unavailable — falling back to PostgreSQL search: {}", ex.getMessage());
            return searchViaDatabase(request);
        }
    }

    // ===================== Elasticsearch path =====================

    private SearchResponse searchViaElasticsearch(SearchRequest request) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // Always filter to ACTIVE only
        boolQuery.filter(f -> f.term(t -> t.field("status").value("ACTIVE")));

        // Full-text keyword match
        String keyword = request.q();
        if (keyword != null && !keyword.isBlank()) {
            boolQuery.must(m -> m.multiMatch(mm -> mm
                    .fields("name^3", "description", "brand^2", "categoryPath")
                    .query(keyword)));
        } else {
            boolQuery.must(m -> m.matchAll(ma -> ma));
        }

        // Brand filter
        if (request.brand() != null && !request.brand().isBlank()) {
            boolQuery.filter(f -> f.term(t -> t.field("brand").value(request.brand())));
        }

        // Price range filter
        if (request.priceMin() != null || request.priceMax() != null) {
            boolQuery.filter(f -> f.range(r -> r.number(n -> {
                var b = n.field("minPrice");
                if (request.priceMin() != null) {
                    b = b.gte(request.priceMin().doubleValue());
                }
                if (request.priceMax() != null) {
                    b = b.lte(request.priceMax().doubleValue());
                }
                return b;
            })));
        }

        Query builtQuery = Query.of(q -> q.bool(boolQuery.build()));
        Pageable pageable = buildEsPageable(request);
        NativeQuery nativeQuery = NativeQuery.builder()
                .withQuery(builtQuery)
                .withPageable(pageable)
                .build();

        ElasticsearchOperations elasticsearchOperations = elasticsearchOperationsProvider.getIfAvailable();
        if (elasticsearchOperations == null) {
            throw new IllegalStateException("Elasticsearch is not available");
        }
        SearchHits<ProductDocument> hits = elasticsearchOperations.search(nativeQuery, ProductDocument.class);
        List<UUID> productIds = hits.stream()
                .map(h -> UUID.fromString(h.getContent().getProductId()))
                .toList();

        // Revalidate: load full cards from DB, filtering out stale ES entries (e.g. a product
        // hard-deleted or unpublished after being indexed). Subtract those drops from the raw ES
        // hit count so totalElements never overstates what a client can actually page through —
        // otherwise a page can report e.g. totalElements=1 with an empty items list.
        List<ProductCardResponse> cards = productService.loadActiveProductCards(productIds);
        int staleOnThisPage = productIds.size() - cards.size();
        long totalHits = Math.max(0, hits.getTotalHits() - staleOnThisPage);
        int totalPages = request.size() == 0 ? 0 : (int) Math.ceil((double) totalHits / request.size());

        PagedResponse<ProductCardResponse> paged = PagedResponse.<ProductCardResponse>builder()
                .items(cards)
                .page(request.page())
                .size(request.size())
                // known: totalElements is corrected only for stale entries found on THIS page —
                // stale entries on other pages still inflate the grand total; acceptable for demo scope
                .totalElements(totalHits)
                .totalPages(totalPages)
                .last(request.page() >= totalPages - 1)
                .build();

        return SearchResponse.ok(paged);
    }

    // ===================== PostgreSQL fallback path =====================

    private SearchResponse searchViaDatabase(SearchRequest request) {
        // known: brand and price filters are dropped in DB fallback — only keyword and status are applied
        Pageable pageable = buildDbPageable(request);
        Page<Product> page;

        String keyword = request.q();
        if (keyword != null && !keyword.isBlank()) {
            page = productRepository.findAllByStatusAndKeyword(ProductStatus.ACTIVE, keyword, pageable);
        } else {
            page = productRepository.findAllByStatus(ProductStatus.ACTIVE, pageable);
        }

        List<UUID> productIds = page.getContent().stream().map(Product::getId).toList();
        List<ProductCardResponse> cards = productService.loadActiveProductCards(productIds);
        PagedResponse<ProductCardResponse> paged = PagedResponse.from(page, cards);
        demoMetrics.incrementSearchDegraded();
        return SearchResponse.degraded(paged, DEGRADED_REASON_ES);
    }

    // ===================== Helpers =====================

    private Pageable buildEsPageable(SearchRequest request) {
        Sort sort = switch (request.sort()) {
            case "PRICE_ASC" -> Sort.by(Sort.Direction.ASC, "minPrice");
            case "PRICE_DESC" -> Sort.by(Sort.Direction.DESC, "minPrice");
            case "NEWEST" -> Sort.by(Sort.Direction.DESC, "createdAt");
            default -> Sort.unsorted(); // RELEVANCE — ES scores by default
        };
        return PageRequest.of(request.page(), Math.min(request.size(), 50), sort);
    }

    private Pageable buildDbPageable(SearchRequest request) {
        Sort sort = switch (request.sort()) {
            case "PRICE_ASC" -> Sort.by(Sort.Direction.ASC, "minPrice");
            case "PRICE_DESC" -> Sort.by(Sort.Direction.DESC, "minPrice");
            default -> Sort.by(Sort.Direction.DESC, "createdAt");
        };
        return PageRequest.of(request.page(), Math.min(request.size(), 50), sort);
    }
}
