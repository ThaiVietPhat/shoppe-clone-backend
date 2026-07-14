package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.order.dto.request.RequestReturnRequest;
import com.shopee.monolith.modules.order.dto.request.ResolveReturnRequest;
import com.shopee.monolith.modules.order.dto.response.ReturnResponse;
import com.shopee.monolith.modules.order.model.ReturnStatus;
import org.springframework.data.domain.Pageable;

import java.util.Optional;
import java.util.UUID;

public interface ReturnService {

    ReturnResponse requestReturn(UUID buyerId, UUID orderId, RequestReturnRequest request);

    Optional<ReturnResponse> getReturnForOrder(UUID buyerId, UUID orderId);

    PagedResponse<ReturnResponse> listSellerReturns(UUID sellerId, ReturnStatus status, Pageable pageable);

    ReturnResponse approveReturn(UUID sellerId, UUID returnId, ResolveReturnRequest request);

    ReturnResponse rejectReturn(UUID sellerId, UUID returnId, ResolveReturnRequest request);
}
