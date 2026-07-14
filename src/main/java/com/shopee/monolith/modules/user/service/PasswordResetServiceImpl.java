package com.shopee.monolith.modules.user.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.user.dto.command.CreatePasswordResetTokenCommand;
import com.shopee.monolith.modules.user.entity.PasswordResetToken;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.PasswordResetTokenRepository;
import com.shopee.monolith.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    private final PasswordResetTokenRepository passwordResetTokenRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public void createPasswordResetToken(CreatePasswordResetTokenCommand command) {
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(command.userId())
                .tokenHash(command.tokenHash())
                .expiresAt(command.expiresAt())
                .build();
        passwordResetTokenRepository.save(token);
    }

    @Override
    @Transactional
    public UUID resetPassword(String tokenHash, String newPasswordHash, Instant now) {
        PasswordResetToken token = passwordResetTokenRepository.findByTokenHashForUpdate(tokenHash)
                .orElseThrow(() -> new AppException(ErrorCode.PASSWORD_RESET_TOKEN_NOT_FOUND));

        if (token.isConsumed()) {
            throw new AppException(ErrorCode.PASSWORD_RESET_TOKEN_ALREADY_USED);
        }
        if (token.isExpired(now)) {
            throw new AppException(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED);
        }

        User user = userRepository.findByIdForUpdate(token.getUserId())
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        if (user.getStatus() == UserStatus.LOCKED) {
            throw new AppException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }

        user.updatePassword(newPasswordHash);
        token.consume(now);

        userRepository.save(user);
        passwordResetTokenRepository.save(token);

        return user.getId();
    }
}
