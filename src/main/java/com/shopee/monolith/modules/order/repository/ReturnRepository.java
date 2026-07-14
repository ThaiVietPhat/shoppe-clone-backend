package com.shopee.monolith.modules.order.repository;

import com.shopee.monolith.modules.order.entity.Return;
import com.shopee.monolith.modules.order.model.ReturnStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReturnRepository extends JpaRepository<Return, UUID> {

    Optional<Return> findByOrderId(UUID orderId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Return r where r.id = :id")
    Optional<Return> findByIdForUpdate(@Param("id") UUID id);

    Page<Return> findAllByShopIdOrderByRequestedAtDesc(UUID shopId, Pageable pageable);

    Page<Return> findAllByShopIdAndStatusOrderByRequestedAtDesc(
            UUID shopId, ReturnStatus status, Pageable pageable);
}
