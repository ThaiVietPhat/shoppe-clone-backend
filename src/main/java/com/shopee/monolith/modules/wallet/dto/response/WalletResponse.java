package com.shopee.monolith.modules.wallet.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.math.BigDecimal;
import java.util.UUID;

@Builder
@Schema(description = "Wallet balance summary")
public record WalletResponse(
        UUID walletId,
        BigDecimal balance
) {}
