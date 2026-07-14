package com.shopee.monolith.modules.user.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.user.dto.command.CreatePasswordResetTokenCommand;
import com.shopee.monolith.modules.user.entity.PasswordResetToken;
import com.shopee.monolith.modules.user.entity.User;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.repository.PasswordResetTokenRepository;
import com.shopee.monolith.modules.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceImplTest {

    @Mock
    private PasswordResetTokenRepository passwordResetTokenRepository;

    @Mock
    private UserRepository userRepository;

    private PasswordResetServiceImpl passwordResetService;

    private final Instant now = Instant.parse("2026-06-03T12:00:00Z");

    @BeforeEach
    void setUp() {
        passwordResetService = new PasswordResetServiceImpl(passwordResetTokenRepository, userRepository);
    }

    @Test
    void createPasswordResetTokenWhenCommandValidShouldPersistToken() {
        UUID userId = UUID.randomUUID();
        Instant expiresAt = now.plus(Duration.ofMinutes(30));

        passwordResetService.createPasswordResetToken(new CreatePasswordResetTokenCommand(userId, "hash", expiresAt));

        ArgumentCaptor<PasswordResetToken> tokenCaptor = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(passwordResetTokenRepository).save(tokenCaptor.capture());
        PasswordResetToken savedToken = tokenCaptor.getValue();
        assertEquals(userId, savedToken.getUserId());
        assertEquals("hash", savedToken.getTokenHash());
        assertEquals(expiresAt, savedToken.getExpiresAt());
    }

    @Test
    void resetPasswordWhenValidShouldUpdatePasswordAndConsumeToken() {
        UUID userId = UUID.randomUUID();
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(userId)
                .tokenHash("hash")
                .expiresAt(now.plus(Duration.ofMinutes(30)))
                .build();
        User user = User.builder()
                .id(userId)
                .email("test@example.com")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();

        when(passwordResetTokenRepository.findByTokenHashForUpdate("hash")).thenReturn(Optional.of(token));
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        UUID result = passwordResetService.resetPassword("hash", "newHash", now);

        assertEquals(userId, result);
        assertEquals("newHash", user.getPasswordHash());
        assertTrue(token.isConsumed());
        assertEquals(now, token.getConsumedAt());
        verify(userRepository).save(user);
        verify(passwordResetTokenRepository).save(token);
    }

    @Test
    void resetPasswordWhenExpiredShouldThrowExpired() {
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(UUID.randomUUID())
                .tokenHash("hash")
                .expiresAt(now.minus(Duration.ofSeconds(1)))
                .build();
        when(passwordResetTokenRepository.findByTokenHashForUpdate("hash")).thenReturn(Optional.of(token));

        AppException exception = assertThrows(AppException.class,
                () -> passwordResetService.resetPassword("hash", "newHash", now));

        assertEquals(ErrorCode.PASSWORD_RESET_TOKEN_EXPIRED, exception.getErrorCode());
    }

    @Test
    void resetPasswordWhenAlreadyUsedShouldThrowAlreadyUsed() {
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(UUID.randomUUID())
                .tokenHash("hash")
                .expiresAt(now.plus(Duration.ofMinutes(30)))
                .consumedAt(now.minus(Duration.ofMinutes(5)))
                .build();
        when(passwordResetTokenRepository.findByTokenHashForUpdate("hash")).thenReturn(Optional.of(token));

        AppException exception = assertThrows(AppException.class,
                () -> passwordResetService.resetPassword("hash", "newHash", now));

        assertEquals(ErrorCode.PASSWORD_RESET_TOKEN_ALREADY_USED, exception.getErrorCode());
    }

    @Test
    void resetPasswordWhenMissingShouldThrowNotFound() {
        when(passwordResetTokenRepository.findByTokenHashForUpdate("hash")).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> passwordResetService.resetPassword("hash", "newHash", now));

        assertEquals(ErrorCode.PASSWORD_RESET_TOKEN_NOT_FOUND, exception.getErrorCode());
    }

    @Test
    void resetPasswordWhenUserLockedShouldThrowAccountNotActive() {
        UUID userId = UUID.randomUUID();
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(userId)
                .tokenHash("hash")
                .expiresAt(now.plus(Duration.ofMinutes(30)))
                .build();
        User user = User.builder()
                .id(userId)
                .email("locked@example.com")
                .role(Role.BUYER)
                .status(UserStatus.LOCKED)
                .build();

        when(passwordResetTokenRepository.findByTokenHashForUpdate("hash")).thenReturn(Optional.of(token));
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.of(user));

        AppException exception = assertThrows(AppException.class,
                () -> passwordResetService.resetPassword("hash", "newHash", now));

        assertEquals(ErrorCode.ACCOUNT_NOT_ACTIVE, exception.getErrorCode());
    }

    @Test
    void resetPasswordWhenUserMissingShouldThrowUserNotFound() {
        UUID userId = UUID.randomUUID();
        PasswordResetToken token = PasswordResetToken.builder()
                .userId(userId)
                .tokenHash("hash")
                .expiresAt(now.plus(Duration.ofMinutes(30)))
                .build();

        when(passwordResetTokenRepository.findByTokenHashForUpdate("hash")).thenReturn(Optional.of(token));
        when(userRepository.findByIdForUpdate(userId)).thenReturn(Optional.empty());

        AppException exception = assertThrows(AppException.class,
                () -> passwordResetService.resetPassword("hash", "newHash", now));

        assertEquals(ErrorCode.USER_NOT_FOUND, exception.getErrorCode());
    }
}
