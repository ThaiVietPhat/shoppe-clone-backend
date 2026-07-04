package com.shopee.monolith.modules.review.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.PagedResponse;
import com.shopee.monolith.modules.order.dto.internal.OrderItemReviewData;
import com.shopee.monolith.modules.order.service.BuyerOrderService;
import com.shopee.monolith.modules.product.dto.internal.VariantLookupData;
import com.shopee.monolith.modules.product.service.ProductService;
import com.shopee.monolith.modules.review.dto.request.CreateReviewRequest;
import com.shopee.monolith.modules.review.dto.request.UpdateReviewRequest;
import com.shopee.monolith.modules.review.dto.response.ProductReviewListResponse;
import com.shopee.monolith.modules.review.dto.response.ReviewResponse;
import com.shopee.monolith.modules.review.entity.Review;
import com.shopee.monolith.modules.review.event.ReviewSubmittedEvent;
import com.shopee.monolith.modules.review.mapper.ReviewMapper;
import com.shopee.monolith.modules.review.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReviewServiceImpl implements ReviewService {

    private final ReviewRepository reviewRepository;
    private final BuyerOrderService buyerOrderService;
    private final ProductService productService;
    private final ReviewMapper reviewMapper;
    private final ApplicationEventPublisher eventPublisher;

    @Override
    @Transactional
    public ReviewResponse createReview(UUID buyerId, CreateReviewRequest request) {
        OrderItemReviewData item = buyerOrderService.findOrderItemReviewData(request.orderItemId())
                .orElseThrow(() -> new AppException(ErrorCode.ORDER_NOT_FOUND));
        if (!item.buyerId().equals(buyerId)) {
            throw new AppException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!item.reviewable()) {
            throw new AppException(ErrorCode.ORDER_NOT_REVIEWABLE);
        }
        if (reviewRepository.existsByOrderItemId(item.orderItemId())) {
            throw new AppException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }

        UUID productId = productService.findVariantLookupDataById(item.variantId())
                .map(VariantLookupData::productId)
                .orElseThrow(() -> new AppException(ErrorCode.PRODUCT_NOT_FOUND));

        Review review = Review.builder()
                .orderItemId(item.orderItemId())
                .orderId(item.orderId())
                .productId(productId)
                .buyerId(buyerId)
                .shopId(item.shopId())
                .rating(request.rating())
                .comment(request.comment())
                .build();
        try {
            review = reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            // Concurrent create on the same order item lost the unique-constraint race
            throw new AppException(ErrorCode.REVIEW_ALREADY_EXISTS);
        }

        buyerOrderService.markOrderItemReviewed(item.orderItemId());
        eventPublisher.publishEvent(new ReviewSubmittedEvent(productId));
        log.info("Buyer {} reviewed order item {} (product {})", buyerId, item.orderItemId(), productId);
        return reviewMapper.toResponse(review);
    }

    @Override
    @Transactional
    public ReviewResponse updateReview(UUID buyerId, UUID reviewId, UpdateReviewRequest request) {
        Review review = reviewRepository.findById(reviewId)
                .orElseThrow(() -> new AppException(ErrorCode.REVIEW_NOT_FOUND));
        if (!review.getBuyerId().equals(buyerId)) {
            throw new AppException(ErrorCode.REVIEW_NOT_FOUND);
        }
        review.update(request.rating(), request.comment());
        review = reviewRepository.save(review);

        eventPublisher.publishEvent(new ReviewSubmittedEvent(review.getProductId()));
        return reviewMapper.toResponse(review);
    }

    @Override
    @Transactional(readOnly = true)
    public ProductReviewListResponse listProductReviews(UUID productId, Pageable pageable) {
        Page<Review> page = reviewRepository.findAllByProductIdOrderByCreatedAtDesc(productId, pageable);
        List<ReviewResponse> items = page.getContent().stream().map(reviewMapper::toResponse).toList();

        RatingSummary summary = aggregateRating(productId);
        return ProductReviewListResponse.builder()
                .ratingAvg(summary.avg())
                .ratingCount(summary.count())
                .reviews(PagedResponse.from(page, items))
                .build();
    }

    @Override
    @Transactional(readOnly = true)
    public PagedResponse<ReviewResponse> listMyReviews(UUID buyerId, Pageable pageable) {
        Page<Review> page = reviewRepository.findAllByBuyerIdOrderByCreatedAtDesc(buyerId, pageable);
        return PagedResponse.from(page, page.getContent().stream().map(reviewMapper::toResponse).toList());
    }

    /**
     * Recomputes [avg, count] from the reviews table.
     * Also used by the after-commit rating refresh listener.
     */
    @Transactional(readOnly = true)
    public RatingSummary aggregateRating(UUID productId) {
        List<Object[]> rows = reviewRepository.aggregateRatingByProductId(productId);
        if (rows.isEmpty() || rows.get(0)[0] == null) {
            return new RatingSummary(BigDecimal.ZERO, 0);
        }
        Object[] row = rows.get(0);
        BigDecimal avg = new BigDecimal(row[0].toString()).setScale(2, RoundingMode.HALF_UP);
        long count = ((Number) row[1]).longValue();
        return new RatingSummary(avg, count);
    }

    public record RatingSummary(BigDecimal avg, long count) {
    }
}
