package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.order.dto.internal.OrderItemReviewData;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderDetailResponse;
import com.shopee.monolith.modules.order.dto.response.BuyerOrderSummaryResponse;
import com.shopee.monolith.modules.order.model.OrderStatus;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface BuyerOrderService {

    PagedResponse<BuyerOrderSummaryResponse> listOrders(UUID buyerId, OrderStatus status, Pageable pageable);

    BuyerOrderDetailResponse getOrderDetail(UUID buyerId, UUID orderId);

    /**
     * Cancels a PENDING_PAYMENT order and releases its RESERVED inventory atomically.
     * Any other state is rejected with ORDER_CANNOT_BE_CANCELLED.
     */
    void cancelOrder(UUID buyerId, UUID orderId);

    /**
     * Cross-module lookup for ReviewModule: order item snapshot plus
     * whether the parent order is in a reviewable (DELIVERED/COMPLETED) state.
     */
    Optional<OrderItemReviewData> findOrderItemReviewData(UUID orderItemId);

    /**
     * Cross-module write for ReviewModule: flags an order item as reviewed
     * after a review is successfully submitted for it. Idempotent no-op if not found.
     */
    void markOrderItemReviewed(UUID orderItemId);
}
