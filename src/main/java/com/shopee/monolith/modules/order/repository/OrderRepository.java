package com.shopee.monolith.modules.order.repository;

import com.shopee.monolith.modules.order.entity.Order;
import com.shopee.monolith.modules.order.model.FulfillmentStatus;
import com.shopee.monolith.modules.order.model.OrderStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OrderRepository extends JpaRepository<Order, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.checkoutSessionId = :checkoutSessionId order by o.id asc")
    List<Order> findAllByCheckoutSessionIdForUpdate(@Param("checkoutSessionId") UUID checkoutSessionId);

    List<Order> findAllByCheckoutSessionIdOrderByIdAsc(UUID checkoutSessionId);

    Page<Order> findAllByBuyerIdOrderByCreatedAtDesc(UUID buyerId, Pageable pageable);

    Page<Order> findAllByBuyerIdAndStatusOrderByCreatedAtDesc(UUID buyerId, OrderStatus status, Pageable pageable);

    Optional<Order> findByIdAndBuyerId(UUID id, UUID buyerId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") UUID id);

    // ==================== Seller-scoped queries ====================

    Page<Order> findAllByShopIdOrderByCreatedAtDesc(UUID shopId, Pageable pageable);

    Page<Order> findAllByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(
            UUID shopId, FulfillmentStatus fulfillmentStatus, Pageable pageable);

    Optional<Order> findByIdAndShopId(UUID id, UUID shopId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id and o.shopId = :shopId")
    Optional<Order> findByIdAndShopIdForUpdate(@Param("id") UUID id, @Param("shopId") UUID shopId);

    List<Order> findTop5ByShopIdAndFulfillmentStatusOrderByCreatedAtDesc(
            UUID shopId, FulfillmentStatus fulfillmentStatus);

    @Query("select o.fulfillmentStatus, count(o) from Order o where o.shopId = :shopId group by o.fulfillmentStatus")
    List<Object[]> countByShopIdGroupByFulfillmentStatus(@Param("shopId") UUID shopId);

    @Query("select o.paymentStatus, count(o) from Order o where o.shopId = :shopId group by o.paymentStatus")
    List<Object[]> countByShopIdGroupByPaymentStatus(@Param("shopId") UUID shopId);
}
