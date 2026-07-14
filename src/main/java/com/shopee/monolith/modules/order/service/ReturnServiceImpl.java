package com.shopee.monolith.modules.order.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.inventory.dto.command.RestockInventoryCommand;
import com.shopee.monolith.modules.inventory.service.InventoryService;
import com.shopee.monolith.modules.order.config.ReturnProperties;
import com.shopee.monolith.modules.order.dto.request.RequestReturnRequest;
import com.shopee.monolith.modules.order.dto.request.ResolveReturnRequest;
import com.shopee.monolith.modules.order.dto.response.ReturnResponse;
import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.entity.OrderItem;
import com.shopee.monolith.modules.order.entity.Return;
import com.shopee.monolith.modules.order.entity.ReturnEvidence;
import com.shopee.monolith.modules.order.model.OrderStatus;
import com.shopee.monolith.modules.order.model.ReturnStatus;
import com.shopee.monolith.modules.order.repository.OrderItemRepository;
import com.shopee.monolith.modules.order.repository.OrderRepository;
import com.shopee.monolith.modules.order.repository.ReturnEvidenceRepository;
import com.shopee.monolith.modules.order.repository.ReturnRepository;
import com.shopee.monolith.modules.user.dto.internal.ShopLookupData;
import com.shopee.monolith.modules.user.service.ShopService;
import com.shopee.monolith.modules.wallet.model.WalletReferenceType;
import com.shopee.monolith.modules.wallet.model.WalletTransactionType;
import com.shopee.monolith.modules.wallet.service.WalletService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ReturnServiceImpl implements ReturnService {

    private final ReturnRepository returnRepository;
    private final ReturnEvidenceRepository returnEvidenceRepository;
    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final WalletService walletService;
    private final InventoryService inventoryService;
    private final ShopService shopService;
    private final ReturnProperties returnProperties;
    private final Clock clock;

    @Override
    @Transactional
    public ReturnResponse requestReturn(UUID buyerId, UUID orderId, RequestReturnRequest request) {
        Order order = orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        if (order.getStatus() != OrderStatus.DELIVERED || order.getDeliveredAt() == null) {
            throw new AppException(ErrorCode.RETURN_NOT_ELIGIBLE);
        }

        Instant now = Instant.now(clock);
        Instant windowEnd = order.getDeliveredAt().plus(returnProperties.getWindowDays(), ChronoUnit.DAYS);
        if (now.isAfter(windowEnd)) {
            throw new AppException(ErrorCode.RETURN_WINDOW_EXPIRED);
        }

        Return newReturn = Return.builder()
                .orderId(orderId)
                .buyerId(buyerId)
                .shopId(order.getShopId())
                .reasonCategory(request.reasonCategory())
                .description(request.description())
                .requestedAt(now)
                .build();

        Return saved;
        try {
            saved = returnRepository.saveAndFlush(newReturn);
        } catch (DataIntegrityViolationException e) {
            throw new AppException(ErrorCode.RETURN_ALREADY_EXISTS);
        }

        List<ReturnEvidence> evidence = saveEvidence(saved.getId(), request.evidenceMediaIds());
        return toResponse(saved, evidence);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReturnResponse> getReturnForOrder(UUID buyerId, UUID orderId) {
        orderRepository.findByIdAndBuyerId(orderId, buyerId)
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        return returnRepository.findByOrderId(orderId)
                .map(r -> toResponse(r, returnEvidenceRepository.findAllByReturnId(r.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ReturnResponse> listSellerReturns(UUID sellerId, ReturnStatus status, Pageable pageable) {
        UUID shopId = resolveOwnShopId(sellerId);
        Page<Return> page = status == null
                ? returnRepository.findAllByShopIdOrderByRequestedAtDesc(shopId, pageable)
                : returnRepository.findAllByShopIdAndStatusOrderByRequestedAtDesc(shopId, status, pageable);

        List<UUID> returnIds = page.getContent().stream().map(Return::getId).toList();
        Map<UUID, List<ReturnEvidence>> evidenceByReturnId = returnIds.isEmpty() ? Map.of()
                : returnEvidenceRepository.findAllByReturnIdIn(returnIds).stream()
                        .collect(Collectors.groupingBy(ReturnEvidence::getReturnId));

        List<ReturnResponse> items = page.getContent().stream()
                .map(r -> toResponse(r, evidenceByReturnId.getOrDefault(r.getId(), List.of())))
                .toList();
        return PagedResponse.from(page, items);
    }

    @Override
    @Transactional
    public ReturnResponse approveReturn(UUID sellerId, UUID returnId, ResolveReturnRequest request) {
        UUID shopId = resolveOwnShopId(sellerId);
        Return existingReturn = lockOwnReturn(shopId, returnId);
        if (existingReturn.getStatus() != ReturnStatus.REQUESTED) {
            throw new AppException(ErrorCode.RETURN_INVALID_STATE);
        }

        Order order = orderRepository.findById(existingReturn.getOrderId())
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));

        Instant now = Instant.now(clock);
        BigDecimal refundAmount = order.getTotalAmount();
        existingReturn.approve(sellerId, request.resolutionNote(), refundAmount, now);
        Return approved = returnRepository.save(existingReturn);

        walletService.credit(order.getBuyerId(), refundAmount,
                WalletTransactionType.RETURN_REFUND, WalletReferenceType.RETURN, approved.getId());
        walletService.debit(sellerId, order.getItemsSubtotal(),
                WalletTransactionType.RETURN_CLAWBACK, WalletReferenceType.RETURN, approved.getId());

        List<OrderItem> items = orderItemRepository.findAllByOrderId(order.getId());
        List<RestockInventoryCommand> restockCommands = items.stream()
                .map(item -> new RestockInventoryCommand(item.getVariantId(), item.getQuantity()))
                .toList();
        inventoryService.restock(restockCommands);

        return toResponse(approved, returnEvidenceRepository.findAllByReturnId(approved.getId()));
    }

    @Override
    @Transactional
    public ReturnResponse rejectReturn(UUID sellerId, UUID returnId, ResolveReturnRequest request) {
        UUID shopId = resolveOwnShopId(sellerId);
        Return existingReturn = lockOwnReturn(shopId, returnId);
        if (existingReturn.getStatus() != ReturnStatus.REQUESTED) {
            throw new AppException(ErrorCode.RETURN_INVALID_STATE);
        }

        existingReturn.reject(sellerId, request.resolutionNote(), Instant.now(clock));
        Return rejected = returnRepository.save(existingReturn);
        return toResponse(rejected, returnEvidenceRepository.findAllByReturnId(rejected.getId()));
    }

    private Return lockOwnReturn(UUID shopId, UUID returnId) {
        Return existingReturn = returnRepository.findByIdForUpdate(returnId)
                .orElseThrow(() -> new AppException(ErrorCode.RETURN_NOT_FOUND));
        if (!existingReturn.getShopId().equals(shopId)) {
            throw new AppException(ErrorCode.RETURN_NOT_FOUND);
        }
        return existingReturn;
    }

    private UUID resolveOwnShopId(UUID sellerId) {
        return shopService.findShopLookupDataByOwnerId(sellerId)
                .map(ShopLookupData::id)
                .orElseThrow(() -> new AppException(ErrorCode.SHOP_NOT_FOUND));
    }

    private List<ReturnEvidence> saveEvidence(UUID returnId, List<UUID> mediaIds) {
        if (mediaIds == null || mediaIds.isEmpty()) {
            return List.of();
        }
        List<ReturnEvidence> evidence = mediaIds.stream()
                .<ReturnEvidence>map(mediaId -> ReturnEvidence.builder().returnId(returnId).mediaId(mediaId).build())
                .toList();
        return returnEvidenceRepository.saveAll(evidence);
    }

    private ReturnResponse toResponse(Return r, List<ReturnEvidence> evidence) {
        return ReturnResponse.builder()
                .id(r.getId())
                .orderId(r.getOrderId())
                .buyerId(r.getBuyerId())
                .reasonCategory(r.getReasonCategory())
                .description(r.getDescription())
                .status(r.getStatus())
                .refundAmount(r.getRefundAmount())
                .resolutionNote(r.getResolutionNote())
                .resolvedAt(r.getResolvedAt())
                .requestedAt(r.getRequestedAt())
                .evidenceMediaIds(evidence.stream().map(ReturnEvidence::getMediaId).toList())
                .build();
    }
}
