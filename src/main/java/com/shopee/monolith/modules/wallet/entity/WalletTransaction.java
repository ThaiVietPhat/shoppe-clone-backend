package com.shopee.monolith.modules.wallet.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import com.shopee.monolith.modules.wallet.model.WalletReferenceType;
import com.shopee.monolith.modules.wallet.model.WalletTransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Append-only ledger row. Idempotency guard lives on the DB unique constraint
 * (reference_type, reference_id, type) — a duplicate credit/debit attempt for the same business
 * event fails to insert here and is treated as a no-op, mirroring InventoryReservation/VoucherUsage.
 */
@Entity
@Table(name = "wallet_transactions")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class WalletTransaction extends BaseEntity {

    @Column(name = "wallet_id", nullable = false)
    private UUID walletId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 30)
    private WalletTransactionType type;

    /** Signed: positive for credit, negative for debit. */
    @Column(name = "amount", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "reference_type", nullable = false, length = 30)
    private WalletReferenceType referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;
}
