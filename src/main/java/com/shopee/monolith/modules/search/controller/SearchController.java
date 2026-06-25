package com.shopee.monolith.modules.search.controller;

import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.modules.search.dto.SearchRequest;
import com.shopee.monolith.modules.search.dto.SearchResponse;
import com.shopee.monolith.modules.search.service.SearchService;
import com.shopee.monolith.modules.search.service.SemanticSearchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.UUID;

@Tag(name = "Search", description = "Keyword, facet and semantic product search APIs")
@RestController
@RequestMapping("/api/search")
@RequiredArgsConstructor
@Validated
public class SearchController {

    private final SearchService searchService;
    private final SemanticSearchService semanticSearchService;

    @Operation(
            summary = "Keyword and facet product search",
            description = "Searches ACTIVE products using Elasticsearch keyword/facet query. "
                    + "Falls back to PostgreSQL LIKE search when Elasticsearch is unavailable "
                    + "(response will contain degraded=true). "
                    + "Results are always revalidated against live DB state before returning.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Search results. degraded=true means ES was unavailable and results come from DB.")
    @GetMapping("/products")
    public ApiResponse<SearchResponse> search(
            @Parameter(description = "Full-text keyword query", example = "iphone 15")
            @RequestParam(required = false) String q,

            @Parameter(description = "Filter by category ID (subtree included)")
            @RequestParam(required = false) UUID categoryId,

            @Parameter(description = "Filter by brand", example = "Apple")
            @RequestParam(required = false) String brand,

            @Parameter(description = "Minimum price filter", example = "100.00")
            @RequestParam(required = false) BigDecimal priceMin,

            @Parameter(description = "Maximum price filter", example = "2000.00")
            @RequestParam(required = false) BigDecimal priceMax,

            @Parameter(description = "Sort: RELEVANCE (default), PRICE_ASC, PRICE_DESC, NEWEST")
            @RequestParam(defaultValue = "RELEVANCE") String sort,

            @Parameter(description = "Page index (0-indexed)")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size (max 50)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {

        SearchRequest request = new SearchRequest(q, categoryId, brand, priceMin, priceMax, sort, page, size);
        return ApiResponse.success(searchService.search(request));
    }

    @Operation(
            summary = "Semantic product search",
            description = "Embeds the query using Google Gemini text-embedding-004 and performs "
                    + "pgvector cosine similarity search over indexed product embeddings. "
                    + "Returns degraded=true when the AI provider is unavailable. "
                    + "Results are always revalidated against live product state.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200",
            description = "Semantic search results. degraded=true means AI provider was unavailable.")
    @GetMapping("/products/semantic")
    public ApiResponse<SearchResponse> semanticSearch(
            @Parameter(description = "Natural language query", example = "stylish blue sneakers for running")
            @RequestParam String q,

            @Parameter(description = "Page index (0-indexed)")
            @RequestParam(defaultValue = "0") @Min(0) int page,

            @Parameter(description = "Page size (max 50)")
            @RequestParam(defaultValue = "20") @Min(1) @Max(50) int size) {

        return ApiResponse.success(semanticSearchService.search(q, page, size));
    }
}
