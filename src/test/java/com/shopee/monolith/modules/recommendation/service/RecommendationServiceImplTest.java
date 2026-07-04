package com.shopee.monolith.modules.recommendation.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshot;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderDetailResponse;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderItemResponse;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderSummaryResponse;
import com.shopee.monolith.modules.order.service.BuyerOrderService;
import com.shopee.monolith.modules.product.dto.response.ProductCardResponse;
import com.shopee.monolith.modules.product.entity.ProductStatus;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.recommendation.dto.ChatRecommendRequest;
import com.shopee.monolith.modules.recommendation.dto.RecommendationReasonCode;
import com.shopee.monolith.modules.recommendation.dto.RecommendationResponse;
import com.shopee.monolith.modules.search.repository.ProductEmbeddingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationServiceImplTest {

    private ProductService productService;
    private CartService cartService;
    private BuyerOrderService buyerOrderService;
    private ProductEmbeddingRepository productEmbeddingRepository;
    private ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private ObjectProvider<ChatClient.Builder> chatClientBuilderProvider;
    private RecommendationServiceImpl service;

    private UUID productId;
    private UUID semanticProductId;
    private UUID variantId;
    private ProductCardResponse fallbackCard;
    private ProductCardResponse semanticCard;

    @BeforeEach
    void setUp() {
        productService = mock(ProductService.class);
        cartService = mock(CartService.class);
        buyerOrderService = mock(BuyerOrderService.class);
        productEmbeddingRepository = mock(ProductEmbeddingRepository.class);
        embeddingModelProvider = mock(ObjectProvider.class);
        chatClientBuilderProvider = mock(ObjectProvider.class);
        service = new RecommendationServiceImpl(
                productService,
                cartService,
                buyerOrderService,
                productEmbeddingRepository,
                embeddingModelProvider,
                chatClientBuilderProvider,
                new com.shopee.monolith.common.observability.DemoMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));

        productId = UUID.randomUUID();
        semanticProductId = UUID.randomUUID();
        variantId = UUID.randomUUID();
        fallbackCard = card(productId, "Wireless Mouse", "Logi", new BigDecimal("250000"));
        semanticCard = card(semanticProductId, "Gaming Mouse", "Razer", new BigDecimal("450000"));

        when(productService.listHomepageProducts(anyInt(), anyInt()))
                .thenReturn(PagedResponse.<ProductCardResponse>builder()
                        .items(List.of(fallbackCard))
                        .page(0)
                        .size(20)
                        .totalElements(1)
                        .totalPages(1)
                        .last(true)
                        .build());
        when(productService.loadActiveProductCards(any())).thenAnswer(invocation -> {
            List<UUID> ids = invocation.getArgument(0);
            return ids.stream()
                    .map(id -> id.equals(semanticProductId) ? semanticCard : fallbackCard)
                    .distinct()
                    .toList();
        });
    }

    @Test
    void homeRecommendationsWhenAnonymousShouldReturnTrendingFallback() {
        RecommendationResponse response = service.homeRecommendations(null, 0, 20);

        assertFalse(response.degraded());
        assertNull(response.degradedReason());
        assertEquals(1, response.items().size());
        assertEquals(productId, response.items().get(0).product().id());
        assertEquals(List.of(RecommendationReasonCode.TRENDING), response.items().get(0).reasonCodes());
        verify(embeddingModelProvider, never()).getIfAvailable();
    }

    @Test
    void homeRecommendationsWhenLoggedInShouldUseCartAndSemanticReasons() {
        UUID userId = UUID.randomUUID();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(cartService.getSnapshot(userId)).thenReturn(CartSnapshot.builder()
                .userId(userId)
                .items(List.of(CartSnapshotItem.builder().variantId(variantId).quantity(1).build()))
                .version(1)
                .build());
        when(productService.loadActiveProductCardsByVariantIds(List.of(variantId))).thenReturn(List.of(fallbackCard));
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f, 0.2f});
        when(productEmbeddingRepository.findSimilarProductIdStrings(any(String.class), eq(80)))
                .thenReturn(List.of(semanticProductId.toString()));

        RecommendationResponse response = service.homeRecommendations(userId, 0, 20);

        assertFalse(response.degraded());
        assertEquals(2, response.items().size());
        assertEquals(semanticProductId, response.items().get(0).product().id());
        assertTrue(response.items().get(0).reasonCodes().contains(RecommendationReasonCode.AI_SEMANTIC_MATCH));
        assertTrue(response.items().get(1).reasonCodes().contains(RecommendationReasonCode.SIMILAR_TO_CART));
    }

    @Test
    void homeRecommendationsWhenLoggedInShouldUseOrderSignalReason() {
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(cartService.getSnapshot(userId)).thenReturn(CartSnapshot.builder()
                .userId(userId)
                .items(List.of())
                .version(1)
                .build());
        when(buyerOrderService.listOrders(eq(userId), eq(null), any())).thenReturn(PagedResponse.<BuyerOrderSummaryResponse>builder()
                .items(List.of(BuyerOrderSummaryResponse.builder()
                        .orderId(orderId)
                        .createdAt(Instant.parse("2026-06-02T00:00:00Z"))
                        .build()))
                .page(0)
                .size(5)
                .totalElements(1)
                .totalPages(1)
                .last(true)
                .build());
        when(buyerOrderService.getOrderDetail(userId, orderId)).thenReturn(BuyerOrderDetailResponse.builder()
                .orderId(orderId)
                .items(List.of(BuyerOrderItemResponse.builder()
                        .variantId(variantId)
                        .productName("Wireless Mouse")
                        .build()))
                .build());
        when(productService.loadActiveProductCardsByVariantIds(List.of(variantId))).thenReturn(List.of(fallbackCard));
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f, 0.2f});
        when(productEmbeddingRepository.findSimilarProductIdStrings(any(String.class), eq(80))).thenReturn(List.of());

        RecommendationResponse response = service.homeRecommendations(userId, 0, 20);

        assertFalse(response.degraded());
        assertEquals(1, response.items().size());
        assertTrue(response.items().get(0).reasonCodes().contains(RecommendationReasonCode.SIMILAR_TO_ORDER));
    }

    @Test
    void chatRecommendationsWhenSemanticReturnsEmptyShouldUseTrendingFallbackWithoutExplanation() {
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(embeddingModelProvider.getIfAvailable()).thenReturn(embeddingModel);
        when(embeddingModel.embed(any(String.class))).thenReturn(new float[]{0.1f, 0.2f});
        when(productEmbeddingRepository.findSimilarProductIdStrings(any(String.class), eq(20))).thenReturn(List.of());

        RecommendationResponse response = service.chatRecommendations(null,
                new ChatRecommendRequest("find gaming mouse", null, null, 5));

        assertFalse(response.degraded());
        assertEquals(1, response.items().size());
        assertEquals(List.of(RecommendationReasonCode.TRENDING), response.items().get(0).reasonCodes());
        assertNull(response.generatedText());
        verify(chatClientBuilderProvider, never()).getIfAvailable();
    }

    @Test
    void chatRecommendationsWhenProviderUnavailableShouldReturnDegradedFallback() {
        when(embeddingModelProvider.getIfAvailable()).thenReturn(null);

        RecommendationResponse response = service.chatRecommendations(null,
                new ChatRecommendRequest("gợi ý chuột gaming dưới 500k", null, null, 5));

        assertTrue(response.degraded());
        assertEquals(RecommendationServiceImpl.DEGRADED_REASON_AI, response.degradedReason());
        assertEquals(1, response.items().size());
        assertEquals(productId, response.items().get(0).product().id());
        assertEquals(List.of(RecommendationReasonCode.TRENDING), response.items().get(0).reasonCodes());
        assertNull(response.generatedText());
    }

    @Test
    void promptBuilderShouldUseSanitizedMessageAndProductFactsOnly() {
        String sanitized = service.sanitizeForAi("Call 0987654321 or mail buyer@example.com tìm điện thoại");

        String prompt = service.buildGroundedPrompt(sanitized, List.of(fallbackCard));

        assertFalse(prompt.contains("0987654321"));
        assertFalse(prompt.contains("buyer@example.com"));
        assertTrue(prompt.contains("[redacted-phone]"));
        assertTrue(prompt.contains("[redacted-email]"));
        assertTrue(prompt.contains("Wireless Mouse"));
        assertTrue(prompt.contains("250000"));
    }

    private ProductCardResponse card(UUID id, String name, String brand, BigDecimal price) {
        return ProductCardResponse.builder()
                .id(id)
                .name(name)
                .brand(brand)
                .sellerSku("SKU-" + id)
                .minPrice(price)
                .maxPrice(price)
                .status(ProductStatus.ACTIVE)
                .shopId(UUID.randomUUID())
                .shopName("Demo Shop")
                .shopRating(new BigDecimal("4.8"))
                .categoryPath("Electronics/Accessories")
                .checkoutEligible(true)
                .eligibilityIssues(List.of())
                .createdAt(Instant.parse("2026-06-01T00:00:00Z"))
                .build();
    }
}
