package com.shopee.monolith.modules.recommendation.controller;

import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.SwaggerResponses;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.recommendation.dto.ChatRecommendRequest;
import com.shopee.monolith.modules.recommendation.dto.RecommendationResponse;
import com.shopee.monolith.modules.recommendation.service.RecommendationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/recommendations")
@RequiredArgsConstructor
@Tag(name = "Recommendations", description = "AI-backed product recommendation APIs with deterministic fallback")
public class RecommendationController {

    private static final int MAX_PAGE_SIZE = 50;

    private final RecommendationService recommendationService;

    @Operation(
            summary = "Home recommendations",
            description = "Returns product cards with reason codes. Anonymous users receive deterministic trending "
                    + "recommendations; authenticated users also use cart signals and pgvector similarity when available. "
                    + "If AI retrieval fails for a user with a prior successful AI recommendation, the last known-good "
                    + "result is served instead (degraded=true) rather than reverting to the trending fallback.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Recommendations returned.")
    @GetMapping("/home")
    public ApiResponse<RecommendationResponse> homeRecommendations(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        UUID userId = claims != null ? claims.userId() : null;
        return ApiResponse.success(recommendationService.homeRecommendations(userId, Math.max(page, 0), cappedSize));
    }

    @Operation(
            summary = "Chat product recommendations",
            description = "Retrieves deterministic product cards using pgvector, then optionally asks Gemini via "
                    + "Spring AI for a grounded explanation. Generated text must not be parsed for commerce data.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Chat recommendations returned.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400", description = "Invalid request.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @PostMapping("/chat")
    public ApiResponse<RecommendationResponse> chatRecommendations(
            @Valid @RequestBody ChatRecommendRequest request,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        UUID userId = claims != null ? claims.userId() : null;
        return ApiResponse.success(recommendationService.chatRecommendations(userId, request));
    }
}
