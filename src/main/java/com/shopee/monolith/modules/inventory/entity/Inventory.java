package com.shopee.monolith.modules.inventory.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.util.UUID;

@Entity
@Table(name = "inventories")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Inventory extends BaseEntity {

    @Column(name = "variant_id", unique = true, nullable = false)
    private UUID variantId;

    @Column(name = "available_stock", nullable = false)
    private int availableStock;

    @Column(name = "reserved_stock", nullable = false)
    private int reservedStock;

    /**
     * Directly sets available stock (admin/seller stock adjustment).
     * Invariant: quantity must be non-negative (enforced by DB CHECK and caller validation).
     */
    public void updateAvailableStock(int quantity) {
        this.availableStock = quantity;
    }

    /**
     * Reserves stock for an order: deducts from available, adds to reserved.
     * Caller must hold a pessimistic write lock on this row.
     */
    public void reserve(int quantity) {
        if (this.availableStock < quantity) {
            throw new AppException(ErrorCode.INSUFFICIENT_STOCK);
        }
        this.availableStock -= quantity;
        this.reservedStock += quantity;
    }

    /**
     * Confirms reservation after payment success: deducts from reserved (stock is sold).
     * Caller must hold a pessimistic write lock on this row.
     */
    public void confirm(int quantity) {
        if (this.reservedStock < quantity) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        this.reservedStock -= quantity;
    }

    /**
     * Releases reservation after payment failure/timeout: returns stock to available.
     * Caller must hold a pessimistic write lock on this row.
     */
    public void release(int quantity) {
        if (this.reservedStock < quantity) {
            throw new AppException(ErrorCode.INVALID_REQUEST);
        }
        this.reservedStock -= quantity;
        this.availableStock += quantity;
    }

    /**
     * Restocks previously-sold, already-confirmed units after an approved return — unlike
     * {@link #release}, this never touches reservedStock, since that reservation was already
     * consumed by {@link #confirm} at checkout time; there's nothing left to "release" here.
     * Caller must hold a pessimistic write lock on this row.
     */
    public void restock(int quantity) {
        this.availableStock += quantity;
    }
}
