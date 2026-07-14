package com.shopee.monolith.modules.auth.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.auth.config.AuthSecurityProperties;
import com.shopee.monolith.modules.auth.dto.internal.IssuedTokenPair;
import com.shopee.monolith.modules.auth.dto.request.LoginRequest;
import com.shopee.monolith.modules.auth.dto.request.RegisterRequest;
import com.shopee.monolith.common.security.EventPayloadCryptoService;
import com.shopee.monolith.modules.auth.dto.request.ForgotPasswordRequest;
import com.shopee.monolith.modules.auth.dto.request.ResetPasswordRequest;
import com.shopee.monolith.modules.auth.dto.request.VerifyRequest;
import com.shopee.monolith.modules.auth.security.VerificationTokenGenerator;
import com.shopee.monolith.modules.user.dto.command.CreatePasswordResetTokenCommand;
import com.shopee.monolith.modules.user.dto.command.CreateVerificationTokenCommand;
import com.shopee.monolith.modules.user.dto.command.RegisterUserCommand;
import com.shopee.monolith.modules.user.dto.internal.UserAuthenticationData;
import com.shopee.monolith.modules.user.dto.response.UserResponse;
import com.shopee.monolith.modules.user.event.PasswordResetRequestedEvent;
import com.shopee.monolith.modules.user.event.UserRegisteredEvent;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.service.PasswordResetService;
import com.shopee.monolith.modules.user.service.UserService;
import com.shopee.monolith.modules.user.service.UserVerificationService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final UserService userService;
    private final UserVerificationService userVerificationService;
    private final PasswordResetService passwordResetService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final SessionRevocationService sessionRevocationService;
    private final VerificationTokenGenerator verificationTokenGenerator;
    private final EventPayloadCryptoService eventPayloadCryptoService;
    private final ApplicationEventPublisher eventPublisher;
    private final AuthSecurityProperties securityProperties;
    private final Clock clock;
    private final StringRedisTemplate stringRedisTemplate;

    // Dummy BCrypt hash of cost 12 to run timing-equivalent check for non-existent users
    static final String DUMMY_HASH = "$2a$12$6yGZ/X4sF.FhPUp1p.2KFeZpG.0u4hW1.c.4zY5P6q7r8s9t0u1v2";

    @Override
    public IssuedTokenPair login(LoginRequest request) {
        Optional<UserAuthenticationData> userAuthDataOpt = userService.findAuthenticationDataByEmail(request.email());

        String passwordHash = userAuthDataOpt.map(UserAuthenticationData::passwordHash).orElse(null);
        boolean userExists = userAuthDataOpt.isPresent() && passwordHash != null;

        String hashToMatch = userExists ? passwordHash : DUMMY_HASH;
        boolean passwordMatches = passwordEncoder.matches(request.password(), hashToMatch);

        if (!userExists || !passwordMatches) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        UserAuthenticationData userAuthData = userAuthDataOpt.get();
        validateUserStatus(userAuthData.status());

        return refreshTokenService.issueTokenPair(userAuthData.id(), userAuthData.role());
    }

    @Override
    @Transactional
    public UserResponse register(RegisterRequest request) {
        String hashedPassword = passwordEncoder.encode(request.password());
        UserResponse userResponse = userService.registerUser(new RegisterUserCommand(request.email(), hashedPassword));

        // Generate opaque token and save its hash
        String rawToken = verificationTokenGenerator.generate();
        String tokenHash = verificationTokenGenerator.hash(rawToken);

        Instant expiresAt = Instant.now(clock).plus(securityProperties.getVerificationToken().getTtl());

        userVerificationService.createVerificationToken(new CreateVerificationTokenCommand(
                userResponse.id(),
                tokenHash,
                expiresAt
        ));

        // Encrypt the raw token for transmission via event (safe for Modulith event log)
        String encryptedToken = eventPayloadCryptoService.encrypt(rawToken);

        // Publish event with the encrypted token
        eventPublisher.publishEvent(new UserRegisteredEvent(userResponse.id(), request.email(), encryptedToken));

        return userResponse;
    }

    @Override
    @Transactional
    public void verify(VerifyRequest request) {
        Instant now = Instant.now(clock);
        String tokenHash = verificationTokenGenerator.hash(request.token());

        userVerificationService.verifyTokenHash(tokenHash, now);
    }

    private void validateUserStatus(UserStatus status) {
        if (status == UserStatus.PENDING_VERIFICATION) {
            throw new AppException(ErrorCode.EMAIL_NOT_VERIFIED);
        }
        if (status != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.ACCOUNT_NOT_ACTIVE);
        }
    }

    @Override
    public IssuedTokenPair exchangeOAuth2Code(String code) {
        String key = "oauth2:code:" + code;
        String value;
        try {
            value = stringRedisTemplate.opsForValue().getAndDelete(key);
        } catch (Exception e) {
            throw new AppException(ErrorCode.SERVICE_UNAVAILABLE);
        }
        if (value == null) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }

        int colonIdx = value.indexOf(':');
        if (colonIdx == -1) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        String userIdStr = value.substring(0, colonIdx);

        UUID userId;
        try {
            userId = UUID.fromString(userIdStr);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        UserAuthenticationData userAuthData = userService.findAuthenticationDataById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));

        validateUserStatus(userAuthData.status());

        return refreshTokenService.issueTokenPair(userId, userAuthData.role());
    }

    @Override
    @Transactional
    public void forgotPassword(ForgotPasswordRequest request) {
        // Always succeeds from the caller's perspective regardless of whether the email
        // exists — only the found-user branch does any work, preventing enumeration.
        userService.findAuthenticationDataByEmail(request.email()).ifPresent(userAuthData -> {
            if (userAuthData.status() == UserStatus.LOCKED) {
                return;
            }

            String rawToken = verificationTokenGenerator.generate();
            String tokenHash = verificationTokenGenerator.hash(rawToken);
            Instant expiresAt = Instant.now(clock).plus(securityProperties.getPasswordResetToken().getTtl());

            passwordResetService.createPasswordResetToken(new CreatePasswordResetTokenCommand(
                    userAuthData.id(),
                    tokenHash,
                    expiresAt
            ));

            String encryptedToken = eventPayloadCryptoService.encrypt(rawToken);
            eventPublisher.publishEvent(new PasswordResetRequestedEvent(userAuthData.id(), request.email(), encryptedToken));
        });
    }

    @Override
    @Transactional
    public void resetPassword(ResetPasswordRequest request) {
        Instant now = Instant.now(clock);
        String tokenHash = verificationTokenGenerator.hash(request.token());
        String newPasswordHash = passwordEncoder.encode(request.newPassword());

        UUID userId = passwordResetService.resetPassword(tokenHash, newPasswordHash, now);

        sessionRevocationService.logoutAll(userId);
    }
}
