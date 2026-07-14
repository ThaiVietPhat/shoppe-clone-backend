package com.shopee.monolith.modules.user.event;

import java.util.UUID;

public record PasswordResetRequestedEvent(
        UUID userId,
        String email,
        String encryptedResetToken
) {
    @Override
    public String toString() {
        return "PasswordResetRequestedEvent[userId=" + userId + ", email=" + email + ", encryptedResetToken=[REDACTED]]";
    }
}
