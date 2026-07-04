package com.shopee.monolith.modules.notification.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.common.response.SwaggerResponses;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.notification.dto.response.NotificationResponse;
import com.shopee.monolith.modules.notification.service.NotificationInboxService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Tag(name = "Notifications", description = "Notification inbox APIs (list, unread count, mark read)")
public class NotificationController {

    private static final int MAX_PAGE_SIZE = 100;

    private final NotificationInboxService inboxService;

    @Operation(
            summary = "List current user's notifications",
            description = "Paged inbox of the authenticated user, newest first.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Notifications returned.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @GetMapping
    public ApiResponse<PagedResponse<NotificationResponse>> listNotifications(
            @RequestParam(defaultValue = "false") boolean unreadOnly,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        int cappedSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        return ApiResponse.success(
                inboxService.listNotifications(claims.userId(), unreadOnly, PageRequest.of(Math.max(page, 0), cappedSize)));
    }

    @Operation(
            summary = "Count unread notifications",
            description = "Number of unread notifications for the authenticated user.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Unread count returned.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Long>> countUnread(@AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(Map.of("unread", inboxService.countUnread(claims.userId())));
    }

    @Operation(
            summary = "Mark one notification read",
            description = "Idempotent: marking an already-read notification is a no-op.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "Notification marked read.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "404", description = "Notification not found or not owned by the caller.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @PostMapping("/{notificationId}/read")
    public ApiResponse<NotificationResponse> markRead(
            @PathVariable UUID notificationId,
            @AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(inboxService.markRead(claims.userId(), notificationId));
    }

    @Operation(
            summary = "Mark all notifications read",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200", description = "All unread notifications marked read.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401", description = "Authentication required.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class)))
    @PostMapping("/read-all")
    public ApiResponse<Map<String, Integer>> markAllRead(@AuthenticationPrincipal AccessTokenClaims claims) {
        requireAuthenticated(claims);
        return ApiResponse.success(Map.of("updated", inboxService.markAllRead(claims.userId())));
    }

    private void requireAuthenticated(AccessTokenClaims claims) {
        if (claims == null) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
    }
}
