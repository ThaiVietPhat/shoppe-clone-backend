package com.shopee.monolith.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Centralized error codes for the entire application.
 * Each module adds its own codes here — keeps error handling in one place.
 * Format: HTTP status + human-readable message.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ==================== Common ====================
    INTERNAL_SERVER_ERROR(500, "Internal server error"),
    INVALID_REQUEST(400, "Invalid request"),
    UNAUTHORIZED(401, "Authentication required"),
    FORBIDDEN(403, "Access denied"),
    NOT_FOUND(404, "Resource not found"),
    CONFLICT(409, "Resource already exists"),
    SERVICE_UNAVAILABLE(503, "Service temporarily unavailable"),
    RATE_LIMIT_EXCEEDED(429, "Too many requests — please try again later"),

    // ==================== Auth ====================
    INVALID_CREDENTIALS(401, "Invalid email or password"),
    INVALID_TOKEN(401, "Token is invalid or expired"),
    TOKEN_REUSE_DETECTED(401, "Security violation detected — please log in again"),
    EMAIL_NOT_VERIFIED(403, "Please verify your email before logging in"),
    ACCOUNT_NOT_ACTIVE(403, "Account is not active"),
    VERIFICATION_TOKEN_EXPIRED(400, "Verification token has expired"),
    VERIFICATION_TOKEN_REUSED(400, "Verification token has already been used"),
    OAUTH_IDENTITY_ALREADY_LINKED(409, "OAuth identity is already linked to another user"),
    PASSWORD_RESET_TOKEN_NOT_FOUND(400, "Password reset token is invalid"),
    PASSWORD_RESET_TOKEN_EXPIRED(400, "Password reset token has expired"),
    PASSWORD_RESET_TOKEN_ALREADY_USED(400, "Password reset token has already been used"),

    // ==================== User ====================
    USER_NOT_FOUND(404, "User not found"),
    EMAIL_ALREADY_EXISTS(409, "Email is already registered"),
    ADDRESS_NOT_FOUND(404, "Address not found"),
    USER_ALREADY_BANNED(409, "User is already banned"),
    USER_ALREADY_ACTIVE(409, "User is already active"),

    // ==================== Shop ====================
    SHOP_NOT_FOUND(404, "Shop not found"),
    SHOP_ALREADY_EXISTS(409, "User already owns a shop"),
    SHOP_OWNER_REQUIRED(403, "Only the shop owner can perform this action"),
    SHOP_ALREADY_SUSPENDED(409, "Shop is already suspended"),
    SHOP_ALREADY_ACTIVE(409, "Shop is already active"),

    // ==================== Product ====================
    PRODUCT_NOT_FOUND(404, "Product not found"),
    VARIANT_NOT_FOUND(404, "Product variant not found"),
    CATEGORY_NOT_FOUND(404, "Category not found"),
    INVALID_PRODUCT_PRICE(400, "Invalid product price"),
    SKU_ALREADY_EXISTS(409, "SKU already exists"),
    PRODUCT_CANNOT_BE_PUBLISHED(409, "Product cannot be published in its current state"),
    PRODUCT_HAS_NO_ACTIVE_VARIANT(422, "Product must have at least one active variant with a positive price to be published"),

    // ==================== Inventory ====================
    INSUFFICIENT_STOCK(409, "Insufficient stock available"),
    INVENTORY_NOT_FOUND(404, "Inventory not found"),
    INVENTORY_ALREADY_EXISTS(409, "Inventory already exists for this variant"),
    INVALID_STOCK_QUANTITY(400, "Stock quantity must be non-negative"),

    // ==================== Order ====================
    ORDER_NOT_FOUND(404, "Order not found"),
    ORDER_CANNOT_BE_CANCELLED(409, "Order cannot be cancelled in its current state"),
    ORDER_FULFILLMENT_INVALID_STATE(409, "Order cannot transition fulfillment state from its current state"),
    IDEMPOTENCY_KEY_MISSING(400, "Idempotency-Key header is required"),
    CHECKOUT_NOT_FOUND(404, "Checkout session not found"),
    IDEMPOTENCY_KEY_CONFLICT(409, "Idempotency key conflict: request payload mismatch"),
    IDEMPOTENCY_REQUEST_PROCESSING(409, "An identical request is currently processing"),

    // ==================== Payment ====================
    PAYMENT_NOT_FOUND(404, "Payment not found"),
    INVALID_WEBHOOK_SIGNATURE(400, "Webhook signature verification failed"),
    PAYMENT_ATTEMPT_IN_PROGRESS(409, "Another payment attempt is already in progress for this checkout session"),
    CHECKOUT_SESSION_NOT_PAYABLE(409, "Checkout session is not payable in its current state"),

    // ==================== Voucher ====================
    VOUCHER_NOT_FOUND(404, "Voucher not found"),
    VOUCHER_EXPIRED(409, "Voucher has expired"),
    VOUCHER_USAGE_LIMIT_REACHED(409, "Voucher usage limit has been reached"),
    VOUCHER_NOT_ACTIVE(409, "Voucher is not active"),
    VOUCHER_MIN_ORDER_NOT_MET(409, "Order does not meet the voucher's minimum order amount"),
    VOUCHER_CODE_ALREADY_EXISTS(409, "Voucher code already exists"),
    VOUCHER_INVALID_DATE_RANGE(400, "Voucher expiry date must be after the start date"),

    // ==================== Cart ====================
    CART_EMPTY(400, "Cart is empty"),
    CART_SELECTED_EMPTY(400, "No items selected for checkout"),
    ADDRESS_INVALID(400, "No valid shipping address found"),

    // ==================== Review ====================
    REVIEW_NOT_FOUND(404, "Review not found"),
    REVIEW_ALREADY_EXISTS(409, "This order item has already been reviewed"),
    ORDER_NOT_REVIEWABLE(409, "Order must be delivered or completed before reviewing"),

    // ==================== Notification ====================
    NOTIFICATION_NOT_FOUND(404, "Notification not found"),

    // ==================== Chat ====================
    CHAT_ROOM_NOT_FOUND(404, "Chat room not found"),
    CHAT_ROOM_ACCESS_DENIED(403, "You are not a participant of this chat room"),

    // ==================== Media ====================
    INVALID_FILE_TYPE(400, "File type is not allowed"),
    FILE_TOO_LARGE(400, "File size exceeds the maximum allowed limit"),
    MEDIA_NOT_FOUND(404, "Media asset not found"),
    MEDIA_OWNERSHIP_VIOLATION(403, "Media asset does not belong to this shop"),

    // ==================== Moderation ====================
    REPORT_NOT_FOUND(404, "Report not found"),
    REPORT_ALREADY_RESOLVED(409, "Report has already been resolved");

    private final int httpStatus;
    private final String message;
}
