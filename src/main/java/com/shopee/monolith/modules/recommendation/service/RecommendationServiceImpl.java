package com.shopee.monolith.modules.recommendation.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshot;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderItemResponse;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderSummaryResponse;
import com.shopee.monolith.modules.order.service.BuyerOrderService;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.recommendation.dto.ChatRecommendRequest;
import com.shopee.monolith.modules.recommendation.dto.RecommendationReasonCode;
import com.shopee.monolith.modules.recommendation.dto.RecommendationResponse;
import com.shopee.monolith.modules.recommendation.dto.RecommendedProductResponse;
import com.shopee.monolith.modules.search.repository.ProductEmbeddingRepository;
import com.shopee.monolith.modules.search.service.EmbeddingIndexServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RecommendationServiceImpl implements RecommendationService {

    static final String DEGRADED_REASON_AI = "AI_PROVIDER_UNAVAILABLE";
    private static final int MAX_HOME_SIZE = 50;
    private static final int DEFAULT_CHAT_LIMIT = 5;
    private static final int MAX_CHAT_LIMIT = 20;
    private static final int ORDER_SIGNAL_LIMIT = 5;
    private static final int VECTOR_CANDIDATE_MULTIPLIER = 4;
    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN =
            Pattern.compile("(?<!\\d)(?:\\+?\\d[\\d\\s().-]{7,}\\d)(?!\\d)");

    private final ProductService productService;
    private final CartService cartService;
    private final BuyerOrderService buyerOrderService;
    private final ProductEmbeddingRepository productEmbeddingRepository;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private final com.shopee.monolith.common.observability.DemoMetrics demoMetrics;

    @Override
    public RecommendationResponse homeRecommendations(UUID userId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_HOME_SIZE);
        List<ProductCardResponse> fallback = fallbackCards(safePage, safeSize);

        if (userId == null) {
            return responseFromCards(fallback, RecommendationReasonCode.TRENDING, false, null, null);
        }

        List<ProductCardResponse> cartCards = loadCartSignalCards(userId);
        List<ProductCardResponse> orderCards = loadOrderSignalCards(userId);
        List<ProductCardResponse> signalCards = mergeSignalCards(cartCards, orderCards);
        if (signalCards.isEmpty()) {
            return responseFromCards(fallback, RecommendationReasonCode.TRENDING, false, null, null);
        }

        String query = buildInterestQuery(signalCards);
        try {
            List<ProductCardResponse> semanticCards = semanticCards(query, safeSize * VECTOR_CANDIDATE_MULTIPLIER);
            Map<UUID, EnumSet<RecommendationReasonCode>> reasons = new LinkedHashMap<>();
            addCards(reasons, semanticCards, RecommendationReasonCode.AI_SEMANTIC_MATCH);
            addCards(reasons, cartCards, RecommendationReasonCode.SIMILAR_TO_CART);
            addCards(reasons, orderCards, RecommendationReasonCode.SIMILAR_TO_ORDER);
            addCards(reasons, fallback, RecommendationReasonCode.TRENDING);
            List<RecommendedProductResponse> items = toItems(reasons, safeSize);
            return RecommendationResponse.builder().items(items).degraded(false).build();
        } catch (Exception ex) {
            log.warn("Home recommendation vector retrieval failed: {}", ex.getMessage());
            return responseFromCards(fallback, RecommendationReasonCode.TRENDING, true, DEGRADED_REASON_AI, null);
        }
    }

    @Override
    public RecommendationResponse chatRecommendations(UUID userId, ChatRecommendRequest request) {
        int limit = request.limit() != null ? Math.min(Math.max(request.limit(), 1), MAX_CHAT_LIMIT) : DEFAULT_CHAT_LIMIT;
        String sanitizedMessage = sanitizeForAi(request.message());
        List<ProductCardResponse> fallback = fallbackCards(0, limit);

        if (!isShoppingIntent(sanitizedMessage)) {
            return responseFromCards(fallback, RecommendationReasonCode.TRENDING, false, null, null);
        }

        boolean degraded = false;
        String degradedReason = null;
        boolean retrievalSucceeded = true;
        boolean semanticMatched = false;
        List<ProductCardResponse> cards;
        try {
            cards = semanticCards(sanitizedMessage, limit * VECTOR_CANDIDATE_MULTIPLIER);
            cards = filterAndRank(cards, request, limit);
            semanticMatched = !cards.isEmpty();
            if (!semanticMatched) {
                cards = fallback;
            }
        } catch (Exception ex) {
            log.warn("Chat recommendation retrieval failed: {}", ex.getMessage());
            cards = fallback;
            degraded = true;
            degradedReason = DEGRADED_REASON_AI;
            demoMetrics.incrementAiFallback();
            retrievalSucceeded = false;
        }

        String explanation = null;
        if (retrievalSucceeded && semanticMatched) {
            try {
                explanation = groundedExplanation(sanitizedMessage, cards);
            } catch (Exception ex) {
                log.warn("Chat recommendation explanation failed: {}", ex.getMessage());
                degraded = true;
                degradedReason = DEGRADED_REASON_AI;
                demoMetrics.incrementAiFallback();
            }
        }

        Map<UUID, EnumSet<RecommendationReasonCode>> reasons = new LinkedHashMap<>();
        addCards(reasons, cards, retrievalSucceeded && semanticMatched
                ? RecommendationReasonCode.AI_SEMANTIC_MATCH
                : RecommendationReasonCode.TRENDING);
        List<ProductCardResponse> cartCards = userId != null ? loadCartSignalCards(userId) : List.of();
        if (!cartCards.isEmpty()) {
            addCards(reasons, cartCards, RecommendationReasonCode.SIMILAR_TO_CART);
        }
        List<ProductCardResponse> orderCards = userId != null ? loadOrderSignalCards(userId) : List.of();
        if (!orderCards.isEmpty()) {
            addCards(reasons, orderCards, RecommendationReasonCode.SIMILAR_TO_ORDER);
        }
        return RecommendationResponse.builder()
                .items(toItems(reasons, limit))
                .degraded(degraded)
                .degradedReason(degradedReason)
                .generatedText(explanation)
                .build();
    }

    private List<ProductCardResponse> fallbackCards(int page, int size) {
        PagedResponse<ProductCardResponse> products = productService.listHomepageProducts(page, size);
        return products.items();
    }

    private List<ProductCardResponse> loadCartSignalCards(UUID userId) {
        try {
            CartSnapshot snapshot = cartService.getSnapshot(userId);
            if (snapshot == null || snapshot.items() == null || snapshot.items().isEmpty()) {
                return List.of();
            }
            List<UUID> variantIds = snapshot.items().stream().map(CartSnapshotItem::variantId).toList();
            return productService.loadActiveProductCardsByVariantIds(variantIds);
        } catch (Exception ex) {
            log.debug("Skipping cart recommendation signal for user {}: {}", userId, ex.getMessage());
            return List.of();
        }
    }

    private List<ProductCardResponse> loadOrderSignalCards(UUID userId) {
        try {
            PagedResponse<BuyerOrderSummaryResponse> orders =
                    buyerOrderService.listOrders(userId, null, PageRequest.of(0, ORDER_SIGNAL_LIMIT));
            if (orders.items() == null || orders.items().isEmpty()) {
                return List.of();
            }
            List<UUID> variantIds = orders.items().stream()
                    .map(BuyerOrderSummaryResponse::orderId)
                    .map(orderId -> buyerOrderService.getOrderDetail(userId, orderId))
                    .flatMap(detail -> detail.items().stream())
                    .map(BuyerOrderItemResponse::variantId)
                    .distinct()
                    .toList();
            return productService.loadActiveProductCardsByVariantIds(variantIds);
        } catch (Exception ex) {
            log.debug("Skipping order recommendation signal for user {}: {}", userId, ex.getMessage());
            return List.of();
        }
    }

    private List<ProductCardResponse> mergeSignalCards(
            List<ProductCardResponse> cartCards, List<ProductCardResponse> orderCards) {
        Map<UUID, ProductCardResponse> cards = new LinkedHashMap<>();
        cartCards.forEach(card -> cards.putIfAbsent(card.id(), card));
        orderCards.forEach(card -> cards.putIfAbsent(card.id(), card));
        return List.copyOf(cards.values());
    }

    private List<ProductCardResponse> semanticCards(String query, int limit) {
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            throw new IllegalStateException("EmbeddingModel bean is not available");
        }
        float[] embedding = embeddingModel.embed(query);
        String vectorLiteral = EmbeddingIndexServiceImpl.toVectorLiteral(embedding);
        List<UUID> candidateIds = productEmbeddingRepository.findSimilarProductIdStrings(vectorLiteral, limit)
                .stream()
                .map(UUID::fromString)
                .toList();
        return productService.loadActiveProductCards(candidateIds);
    }

    private List<ProductCardResponse> filterAndRank(
            List<ProductCardResponse> cards, ChatRecommendRequest request, int limit) {
        return cards.stream()
                .filter(ProductCardResponse::checkoutEligible)
                .filter(card -> request.minPrice() == null || priceOrZero(card).compareTo(request.minPrice()) >= 0)
                .filter(card -> request.maxPrice() == null || priceOrZero(card).compareTo(request.maxPrice()) <= 0)
                .sorted((a, b) -> {
                    int ratingCompare = nullSafeRating(b).compareTo(nullSafeRating(a));
                    if (ratingCompare != 0) {
                        return ratingCompare;
                    }
                    return b.createdAt().compareTo(a.createdAt());
                })
                .limit(limit)
                .toList();
    }

    private String groundedExplanation(String sanitizedMessage, List<ProductCardResponse> cards) {
        ChatClient.Builder builder = chatClientBuilderProvider.getIfAvailable();
        if (builder == null) {
            throw new IllegalStateException("ChatClient.Builder bean is not available");
        }
        return builder.build().prompt()
                .system("""
                        You are a Shopee demo shopping assistant. Explain recommendations only from the product facts.
                        Do not invent price, stock, voucher, shipping, warranty, return policy or availability.
                        If facts are missing, say the app should check the product detail page.
                        """)
                .user(buildGroundedPrompt(sanitizedMessage, cards))
                .call()
                .content();
    }

    String buildGroundedPrompt(String sanitizedMessage, List<ProductCardResponse> cards) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("User shopping intent: ").append(sanitizedMessage).append("\n\n");
        prompt.append("Product facts:\n");
        for (int i = 0; i < cards.size(); i++) {
            ProductCardResponse card = cards.get(i);
            prompt.append(i + 1)
                    .append(". name=").append(safe(card.name()))
                    .append("; brand=").append(safe(card.brand()))
                    .append("; category=").append(safe(card.categoryPath()))
                    .append("; minPrice=").append(card.minPrice())
                    .append("; maxPrice=").append(card.maxPrice())
                    .append("; shop=").append(safe(card.shopName()))
                    .append("; checkoutEligible=").append(card.checkoutEligible())
                    .append('\n');
        }
        prompt.append("\nReturn a short Vietnamese explanation grounded only in these facts.");
        return prompt.toString();
    }

    String sanitizeForAi(String input) {
        if (input == null) {
            return "";
        }
        String withoutEmail = EMAIL_PATTERN.matcher(input).replaceAll("[redacted-email]");
        String withoutPhone = PHONE_PATTERN.matcher(withoutEmail).replaceAll("[redacted-phone]");
        return withoutPhone.replaceAll("\\s+", " ").trim();
    }

    private boolean isShoppingIntent(String message) {
        String lower = message.toLowerCase();
        return lower.contains("buy") || lower.contains("find") || lower.contains("recommend")
                || lower.contains("suggest") || lower.contains("under") || lower.contains("cheap")
                || lower.contains("mua") || lower.contains("tìm") || lower.contains("goi y")
                || lower.contains("gợi ý") || lower.contains("dưới") || lower.contains("rẻ");
    }

    private String buildInterestQuery(List<ProductCardResponse> cards) {
        return cards.stream()
                .limit(5)
                .map(card -> String.join(" ",
                        safe(card.name()), safe(card.brand()), safe(card.categoryPath()), safe(card.shopName())))
                .reduce("", (left, right) -> (left + " " + right).trim());
    }

    private RecommendationResponse responseFromCards(
            List<ProductCardResponse> cards,
            RecommendationReasonCode reason,
            boolean degraded,
            String degradedReason,
            String generatedText) {
        Map<UUID, EnumSet<RecommendationReasonCode>> reasons = new LinkedHashMap<>();
        addCards(reasons, cards, reason);
        return RecommendationResponse.builder()
                .items(toItems(reasons, cards.size()))
                .degraded(degraded)
                .degradedReason(degradedReason)
                .generatedText(generatedText)
                .build();
    }

    private void addCards(
            Map<UUID, EnumSet<RecommendationReasonCode>> reasons,
            List<ProductCardResponse> cards,
            RecommendationReasonCode reason) {
        for (ProductCardResponse card : cards) {
            reasons.computeIfAbsent(card.id(), id -> EnumSet.noneOf(RecommendationReasonCode.class)).add(reason);
        }
    }

    private List<RecommendedProductResponse> toItems(
            Map<UUID, EnumSet<RecommendationReasonCode>> reasonsByProductId, int limit) {
        if (reasonsByProductId.isEmpty()) {
            return List.of();
        }
        List<UUID> productIds = new ArrayList<>(reasonsByProductId.keySet());
        Map<UUID, ProductCardResponse> cards = productService.loadActiveProductCards(productIds).stream()
                .collect(LinkedHashMap::new, (map, card) -> map.put(card.id(), card), LinkedHashMap::putAll);
        return productIds.stream()
                .map(cards::get)
                .filter(java.util.Objects::nonNull)
                .limit(limit)
                .map(card -> RecommendedProductResponse.builder()
                        .product(card)
                        .reasonCodes(List.copyOf(reasonsByProductId.get(card.id())))
                        .build())
                .toList();
    }

    private BigDecimal priceOrZero(ProductCardResponse card) {
        return card.minPrice() != null ? card.minPrice() : BigDecimal.ZERO;
    }

    private BigDecimal nullSafeRating(ProductCardResponse card) {
        return card.shopRating() != null ? card.shopRating() : BigDecimal.ZERO;
    }

    private String safe(String value) {
        return value != null ? value : "";
    }
}
