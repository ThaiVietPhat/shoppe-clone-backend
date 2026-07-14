package com.shopee.monolith.modules.wishlist.repository;

import com.shopee.monolith.modules.wishlist.entity.WishlistItem;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface WishlistItemRepository extends JpaRepository<WishlistItem, UUID> {

    Page<WishlistItem> findAllByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    List<WishlistItem> findAllByUserIdAndProductIdIn(UUID userId, Collection<UUID> productIds);

    Optional<WishlistItem> findByUserIdAndProductId(UUID userId, UUID productId);

    void deleteByUserIdAndProductId(UUID userId, UUID productId);
}
