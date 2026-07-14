package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshot;
import com.shopee.monolith.modules.cart.dto.internal.CartSnapshotItem;
import com.shopee.monolith.modules.cart.service.CartService;
import com.shopee.monolith.modules.order.dto.request.CheckoutRequest;
import com.shopee.monolith.modules.order.dto.response.CheckoutResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopee.monolith.modules.order.entity.IdempotencyKey;
import com.shopee.monolith.modules.order.model.IdempotencyStatus;
import com.shopee.monolith.modules.order.repository.IdempotencyKeyRepository;
import com.shopee.monolith.modules.user.dto.response.AddressResponse;
import com.shopee.monolith.modules.user.service.AddressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OrderServiceImpl implements OrderService {

    private final CheckoutProcessor checkoutProcessor;
    private final CartService cartService;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final AddressService addressService;
    private final ObjectMapper objectMapper;

    @Override
    public CheckoutResponse checkout(UUID buyerId, CheckoutRequest request, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new AppException(ErrorCode.IDEMPOTENCY_KEY_MISSING);
        }

        UUID keyId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(Duration.ofDays(1));
        String requestBodyHash = computeRequestBodyHash(request);

        IdempotencyKey completedKey = null;
        CheckoutResponse cachedResponse = null;
        Optional<IdempotencyKey> existingKeyOpt = idempotencyKeyRepository.findByActorIdAndOperationAndIdempotencyKey(buyerId, "CHECKOUT", idempotencyKey);
        if (existingKeyOpt.isPresent()) {
            IdempotencyKey existing = existingKeyOpt.get();
            if (existing.getExpiresAt().isAfter(Instant.now())
                    && existing.getStatus() == IdempotencyStatus.COMPLETED) {
                if (!requestBodyMatches(existing, requestBodyHash) && !isLegacyBackfilledKey(existing)) {
                    throw new AppException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                }
                try {
                    completedKey = existing;
                    cachedResponse = objectMapper.readValue(existing.getResponseBody(), CheckoutResponse.class);
                } catch (Exception e) {
                    log.error("Failed to deserialize cached checkout response in pre-check", e);
                    throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
                }
            }
        }

        // Proceed with checkout - Read selected items snapshot from Redis OUTSIDE the DB transaction
        CartSnapshot cartSnapshot = cartService.getSelectedSnapshot(buyerId);
        if (cartSnapshot == null || cartSnapshot.items().isEmpty()) {
            if (cachedResponse != null) {
                return cachedResponse;
            }
            throw new AppException(ErrorCode.CART_SELECTED_EMPTY);
        }

        String requestHash = computeRequestHash(request, cartSnapshot);
        if (completedKey != null) {
            if (!completedKey.getRequestHash().equals(requestHash)) {
                throw new AppException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return cachedResponse;
        }

        // Resolve address details OUTSIDE DB transaction.
        // resolveCheckoutAddress verifies user is ACTIVE before returning the address.
        AddressResponse address = addressService.resolveCheckoutAddress(buyerId, request.addressId());

        if (address.wardCode() == null || address.wardCode().isBlank() ||
                address.districtCode() == null || address.districtCode().isBlank() ||
                address.provinceCode() == null || address.provinceCode().isBlank()) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }

        // Enter short DB transaction via CheckoutProcessor
        long cartVersion = cartSnapshot.version();
        CheckoutResponse response = checkoutProcessor.processCheckout(
                buyerId,
                address,
                idempotencyKey,
                requestHash,
                requestBodyHash,
                keyId,
                expiresAt,
                cartSnapshot.items(),
                request.voucherCode(),
                () -> cartService.clearSnapshotIfVersionUnchanged(buyerId, cartVersion)
        );

        return response;
    }

    private boolean requestBodyMatches(IdempotencyKey key, String requestBodyHash) {
        return key.getRequestBodyHash().equals(requestBodyHash);
    }

    private boolean isLegacyBackfilledKey(IdempotencyKey key) {
        return key.getRequestBodyHash().equals(key.getRequestHash());
    }

    private String computeRequestBodyHash(CheckoutRequest request) {
        String canonical = "address=" + (request.addressId() != null ? request.addressId() : "null")
                + "|voucher=" + (request.voucherCode() != null ? request.voucherCode() : "null");
        return sha256Hex(canonical);
    }

    private String computeRequestHash(CheckoutRequest request, CartSnapshot cartSnapshot) {
        String cartItems = cartSnapshot.items().stream()
                .sorted(Comparator.comparing(item -> item.variantId().toString()))
                .map(item -> item.variantId() + ":" + item.quantity())
                .collect(Collectors.joining(","));
        String canonical = "bodyHash=" + computeRequestBodyHash(request)
                + "|cartVersion=" + cartSnapshot.version()
                + "|items=" + cartItems;
        return sha256Hex(canonical);
    }

    private String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            log.error("Failed to compute request hash", e);
            throw new AppException(ErrorCode.INTERNAL_SERVER_ERROR);
        }
    }

    public record CartItemWithDetails(
            CartSnapshotItem cartItem,
            com.shopee.monolith.modules.product.dto.internal.VariantLookupData variant,
            com.shopee.monolith.modules.product.dto.internal.ProductLookupData product
    ) {}
}
