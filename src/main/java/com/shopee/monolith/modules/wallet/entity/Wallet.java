package com.shopee.monolith.modules.wallet.entity;

import com.shopee.monolith.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "wallets")
@Getter
@SuperBuilder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PROTECTED)
public class Wallet extends BaseEntity {

    @Column(name = "user_id", nullable = false, unique = true)
    private UUID userId;

    @Column(name = "balance", nullable = false, precision = 15, scale = 2)
    @lombok.Builder.Default
    private BigDecimal balance = BigDecimal.ZERO;

    @Version
    @Column(name = "version", nullable = false)
    @lombok.Builder.Default
    private int version = 0;

    /**
     * Intentionally allows the resulting balance to go negative — a return clawback on a seller
     * who already withdrew earnings must still succeed (see WalletServiceImpl.debit). There is no
     * DB-level {@code balance >= 0} CHECK for this reason; only withdrawal is capped to the
     * current balance, enforced in the service layer.
     */
    public void applyDelta(BigDecimal signedAmount) {
        this.balance = this.balance.add(signedAmount);
    }
}
