package com.shopee.monolith.modules.wallet.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record WithdrawRequest(
        @NotNull
        @DecimalMin(value = "0.01", message = "Withdraw amount must be positive")
        BigDecimal amount
) {}
