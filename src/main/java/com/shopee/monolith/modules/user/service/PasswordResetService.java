package com.shopee.monolith.modules.user.service;

import com.shopee.monolith.modules.user.dto.command.CreatePasswordResetTokenCommand;

import java.time.Instant;
import java.util.UUID;

public interface PasswordResetService {

    void createPasswordResetToken(CreatePasswordResetTokenCommand command);

    UUID resetPassword(String tokenHash, String newPasswordHash, Instant now);
}
