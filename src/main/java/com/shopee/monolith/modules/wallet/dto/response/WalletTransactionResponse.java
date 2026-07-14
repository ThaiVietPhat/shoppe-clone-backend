package com.shopee.monolith.modules.wallet.dto.response;

import com.shopee.monolith.modules.wallet.model.WalletReferenceType;
import com.shopee.monolith.modules.wallet.model.WalletTransactionType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Builder
@Schema(description = "Single wallet ledger entry")
public record WalletTransactionResponse(
        UUID id,
        WalletTransactionType type,
        BigDecimal amount,
        WalletReferenceType referenceType,
        UUID referenceId,
        Instant createdAt
) {}
