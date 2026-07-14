package com.shopee.monolith.modules.auth.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.security.EventPayloadCryptoService;
import com.shopee.monolith.modules.auth.config.AuthSecurityProperties;
import com.shopee.monolith.modules.auth.dto.request.RegisterRequest;
import com.shopee.monolith.modules.auth.dto.request.VerifyRequest;
import com.shopee.monolith.modules.auth.security.VerificationTokenGenerator;
import com.shopee.monolith.modules.user.dto.command.CreateVerificationTokenCommand;
import com.shopee.monolith.modules.user.dto.command.RegisterUserCommand;
import com.shopee.monolith.modules.user.dto.response.UserResponse;
import com.shopee.monolith.modules.user.event.UserRegisteredEvent;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.service.PasswordResetService;
import com.shopee.monolith.modules.user.service.UserService;
import com.shopee.monolith.modules.user.service.UserVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplRegistrationTest {

    @Mock
    private UserService userService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenService refreshTokenService;

    @Mock
    private UserVerificationService userVerificationService;

    @Mock
    private PasswordResetService passwordResetService;

    @Mock
    private SessionRevocationService sessionRevocationService;

    @Mock
    private VerificationTokenGenerator verificationTokenGenerator;

    @Mock
    private EventPayloadCryptoService eventPayloadCryptoService;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private com.shopee.monolith.modules.auth.config.AuthSecurityProperties securityProperties;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Mock
    private java.time.Clock clock;

    private AuthServiceImpl authService;

    private final java.time.Instant fixedInstant = java.time.Instant.parse("2026-06-03T12:00:00Z");

    @BeforeEach
    void setUp() {
        org.mockito.Mockito.when(clock.instant()).thenReturn(fixedInstant);
        authService = new AuthServiceImpl(
                userService,
                userVerificationService,
                passwordResetService,
                passwordEncoder,
                refreshTokenService,
                sessionRevocationService,
                verificationTokenGenerator,
                eventPayloadCryptoService,
                eventPublisher,
                securityProperties,
                clock,
                stringRedisTemplate
        );
    }

    @Test
    void registerShouldHashPasswordCreateUserGenerateTokenAndPublishEvent() {
        String email = "test@example.com";
        String password = "mySecurePassword";
        String hashedPassword = "hashed_mySecurePassword";
        UUID userId = UUID.randomUUID();
        String rawToken = "rawOpaqueTokenString";
        String tokenHash = "sha256HashedTokenHex";
        String encryptedToken = "encryptedOpaqueTokenString";

        RegisterRequest request = new RegisterRequest(email, password);

        when(passwordEncoder.encode(password)).thenReturn(hashedPassword);
        
        UserResponse mockUserResponse = UserResponse.builder()
                .id(userId)
                .email(email)
                .role(Role.BUYER)
                .build();
        when(userService.registerUser(any(RegisterUserCommand.class))).thenReturn(mockUserResponse);
        
        when(verificationTokenGenerator.generate()).thenReturn(rawToken);
        when(verificationTokenGenerator.hash(rawToken)).thenReturn(tokenHash);
        when(eventPayloadCryptoService.encrypt(rawToken)).thenReturn(encryptedToken);

        AuthSecurityProperties.VerificationTokenProperties tokenProps = new AuthSecurityProperties.VerificationTokenProperties();
        tokenProps.setTtl(Duration.ofHours(24));
        when(securityProperties.getVerificationToken()).thenReturn(tokenProps);

        UserResponse response = authService.register(request);

        assertNotNull(response);
        assertEquals(userId, response.id());
        assertEquals(email, response.email());

        ArgumentCaptor<CreateVerificationTokenCommand> tokenCaptor =
                ArgumentCaptor.forClass(CreateVerificationTokenCommand.class);
        verify(userVerificationService).createVerificationToken(tokenCaptor.capture());
        CreateVerificationTokenCommand savedToken = tokenCaptor.getValue();
        assertEquals(userId, savedToken.userId());
        assertEquals(tokenHash, savedToken.tokenHash());
        assertEquals(fixedInstant.plus(Duration.ofHours(24)), savedToken.expiresAt());

        // Verify event published
        ArgumentCaptor<UserRegisteredEvent> eventCaptor = ArgumentCaptor.forClass(UserRegisteredEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        UserRegisteredEvent publishedEvent = eventCaptor.getValue();
        assertEquals(userId, publishedEvent.userId());
        assertEquals(email, publishedEvent.email());
        assertEquals(encryptedToken, publishedEvent.encryptedVerificationToken());
    }

    @Test
    void verifySuccessShouldActivateUserAndConsumeToken() {
        String rawToken = "rawOpaqueTokenString";
        String tokenHash = "sha256HashedTokenHex";
        VerifyRequest request = new VerifyRequest(rawToken);

        when(verificationTokenGenerator.hash(rawToken)).thenReturn(tokenHash);

        authService.verify(request);

        verify(userVerificationService).verifyTokenHash(tokenHash, fixedInstant);
    }

    @Test
    void verifyWithExpiredTokenShouldThrowException() {
        String rawToken = "expiredTokenString";
        String tokenHash = "sha256ExpiredTokenHash";

        VerifyRequest request = new VerifyRequest(rawToken);

        when(verificationTokenGenerator.hash(rawToken)).thenReturn(tokenHash);
        org.mockito.Mockito.doThrow(new AppException(ErrorCode.VERIFICATION_TOKEN_EXPIRED))
                .when(userVerificationService).verifyTokenHash(tokenHash, fixedInstant);

        AppException ex = assertThrows(AppException.class, () -> authService.verify(request));
        assertEquals(ErrorCode.VERIFICATION_TOKEN_EXPIRED, ex.getErrorCode());
    }

    @Test
    void verifyWithReusedTokenShouldThrowException() {
        String rawToken = "reusedTokenString";
        String tokenHash = "sha256ReusedTokenHash";

        VerifyRequest request = new VerifyRequest(rawToken);

        when(verificationTokenGenerator.hash(rawToken)).thenReturn(tokenHash);
        org.mockito.Mockito.doThrow(new AppException(ErrorCode.VERIFICATION_TOKEN_REUSED))
                .when(userVerificationService).verifyTokenHash(tokenHash, fixedInstant);

        AppException ex = assertThrows(AppException.class, () -> authService.verify(request));
        assertEquals(ErrorCode.VERIFICATION_TOKEN_REUSED, ex.getErrorCode());
    }

    @Test
    void verifyWithInvalidTokenShouldThrowException() {
        String rawToken = "invalidTokenString";
        String tokenHash = "sha256InvalidTokenHash";

        VerifyRequest request = new VerifyRequest(rawToken);

        when(verificationTokenGenerator.hash(rawToken)).thenReturn(tokenHash);
        org.mockito.Mockito.doThrow(new AppException(ErrorCode.INVALID_TOKEN))
                .when(userVerificationService).verifyTokenHash(tokenHash, fixedInstant);

        AppException ex = assertThrows(AppException.class, () -> authService.verify(request));
        assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
    }
}
