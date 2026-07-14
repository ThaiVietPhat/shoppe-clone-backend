package com.shopee.monolith.modules.user.dto.command;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

@Builder
public record CreatePasswordResetTokenCommand(
        UUID userId,
        String tokenHash,
        Instant expiresAt
) {}
