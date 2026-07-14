package com.shopee.monolith.modules.auth.service;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.security.EventPayloadCryptoService;
import com.shopee.monolith.modules.auth.dto.internal.IssuedTokenPair;
import com.shopee.monolith.modules.auth.dto.request.ForgotPasswordRequest;
import com.shopee.monolith.modules.auth.dto.request.LoginRequest;
import com.shopee.monolith.modules.auth.dto.request.ResetPasswordRequest;
import com.shopee.monolith.modules.user.dto.internal.UserAuthenticationData;
import com.shopee.monolith.modules.user.model.Role;
import com.shopee.monolith.modules.user.model.UserStatus;
import com.shopee.monolith.modules.user.service.PasswordResetService;
import com.shopee.monolith.modules.user.service.UserService;
import com.shopee.monolith.modules.user.service.UserVerificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {

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
    private com.shopee.monolith.modules.auth.security.VerificationTokenGenerator verificationTokenGenerator;

    @Mock
    private EventPayloadCryptoService eventPayloadCryptoService;

    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @Mock
    private com.shopee.monolith.modules.auth.config.AuthSecurityProperties securityProperties;

    @Mock
    private java.time.Clock clock;

    @Mock
    private org.springframework.data.redis.core.StringRedisTemplate stringRedisTemplate;

    @Mock
    private org.springframework.data.redis.core.ValueOperations<String, String> valueOperations;

    private AuthServiceImpl authService;

    private static final String DUMMY_HASH = AuthServiceImpl.DUMMY_HASH;

    @BeforeEach
    void setUp() {
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
    void loginActiveUserShouldReturnTokenPairAndDelegateToRefreshTokenService() {
        UUID userId = UUID.randomUUID();
        String email = "test@shopee.com";
        String password = "validPassword";
        String passwordHash = "hashedPassword";
        String mockAccessToken = "mock.jwt.access.token";
        String mockRawRefreshToken = "mockRawRefreshToken";

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(userId)
                .email(email)
                .passwordHash(passwordHash)
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();

        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.of(authData));
        when(passwordEncoder.matches(password, passwordHash)).thenReturn(true);

        IssuedTokenPair expectedPair = IssuedTokenPair.builder()
                .accessToken(mockAccessToken)
                .refreshToken(mockRawRefreshToken)
                .build();
        when(refreshTokenService.issueTokenPair(userId, Role.BUYER)).thenReturn(expectedPair);

        LoginRequest request = LoginRequest.builder()
                .email(email)
                .password(password)
                .build();

        IssuedTokenPair tokenPair = authService.login(request);

        assertNotNull(tokenPair);
        assertEquals(mockAccessToken, tokenPair.accessToken());
        assertEquals(mockRawRefreshToken, tokenPair.refreshToken());

        verify(refreshTokenService).issueTokenPair(userId, Role.BUYER);
    }

    @Test
    void loginWithNonExistentEmailShouldThrowInvalidCredentialsExceptionAndRunPasswordEncoderWithDummyHash() {
        String email = "unknown@shopee.com";
        String password = "anyPassword";
        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.empty());
        when(passwordEncoder.matches(password, DUMMY_HASH)).thenReturn(false);

        LoginRequest request = LoginRequest.builder()
                .email(email)
                .password(password)
                .build();

        AppException exception = assertThrows(AppException.class, () -> authService.login(request));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());

        verify(passwordEncoder).matches(password, DUMMY_HASH);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void loginWithWrongPasswordShouldThrowInvalidCredentialsException() {
        String email = "test@shopee.com";
        String password = "wrongPassword";
        String passwordHash = "hashedPassword";

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordHash)
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();

        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.of(authData));
        when(passwordEncoder.matches(password, passwordHash)).thenReturn(false);

        LoginRequest request = LoginRequest.builder()
                .email(email)
                .password(password)
                .build();

        AppException exception = assertThrows(AppException.class, () -> authService.login(request));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void loginOAuth2UserWithNullPasswordHashShouldThrowInvalidCredentialsExceptionAndRunPasswordEncoderWithDummyHash() {
        String email = "oauth2@shopee.com";
        String password = "somePassword";

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(null)
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();

        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.of(authData));
        when(passwordEncoder.matches(password, DUMMY_HASH)).thenReturn(false);

        LoginRequest request = LoginRequest.builder()
                .email(email)
                .password(password)
                .build();

        AppException exception = assertThrows(AppException.class, () -> authService.login(request));
        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.getErrorCode());

        verify(passwordEncoder).matches(password, DUMMY_HASH);
        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void loginPendingVerificationUserShouldThrowEmailNotVerifiedException() {
        String email = "pending@shopee.com";
        String password = "validPassword";
        String passwordHash = "hashedPassword";

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordHash)
                .role(Role.BUYER)
                .status(UserStatus.PENDING_VERIFICATION)
                .build();

        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.of(authData));
        when(passwordEncoder.matches(password, passwordHash)).thenReturn(true);

        LoginRequest request = LoginRequest.builder()
                .email(email)
                .password(password)
                .build();

        AppException exception = assertThrows(AppException.class, () -> authService.login(request));
        assertEquals(ErrorCode.EMAIL_NOT_VERIFIED, exception.getErrorCode());

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void loginInactiveUserShouldThrowAccountNotActiveException() {
        String email = "inactive@shopee.com";
        String password = "validPassword";
        String passwordHash = "hashedPassword";

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordHash)
                .role(Role.BUYER)
                .status(UserStatus.INACTIVE)
                .build();

        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.of(authData));
        when(passwordEncoder.matches(password, passwordHash)).thenReturn(true);

        LoginRequest request = LoginRequest.builder()
                .email(email)
                .password(password)
                .build();

        AppException exception = assertThrows(AppException.class, () -> authService.login(request));
        assertEquals(ErrorCode.ACCOUNT_NOT_ACTIVE, exception.getErrorCode());

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void loginLockedUserShouldThrowAccountNotActiveException() {
        String email = "locked@shopee.com";
        String password = "validPassword";
        String passwordHash = "hashedPassword";

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(UUID.randomUUID())
                .email(email)
                .passwordHash(passwordHash)
                .role(Role.BUYER)
                .status(UserStatus.LOCKED)
                .build();

        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.of(authData));
        when(passwordEncoder.matches(password, passwordHash)).thenReturn(true);

        LoginRequest request = LoginRequest.builder()
                .email(email)
                .password(password)
                .build();

        AppException exception = assertThrows(AppException.class, () -> authService.login(request));
        assertEquals(ErrorCode.ACCOUNT_NOT_ACTIVE, exception.getErrorCode());

        verifyNoInteractions(refreshTokenService);
    }

    @Test
    void dummyHashShouldBeValidBCryptFormat() {
        assertNotNull(AuthServiceImpl.DUMMY_HASH);
        assertEquals(60, AuthServiceImpl.DUMMY_HASH.length());
        org.junit.jupiter.api.Assertions.assertTrue(
                AuthServiceImpl.DUMMY_HASH.startsWith("$2a$12$") ||
                AuthServiceImpl.DUMMY_HASH.startsWith("$2b$12$") ||
                AuthServiceImpl.DUMMY_HASH.startsWith("$2y$12$")
        );
        org.junit.jupiter.api.Assertions.assertTrue(
                java.util.regex.Pattern.matches("^\\$2[abyd]\\$\\d{2}\\$[./A-Za-z0-9]{53}$", AuthServiceImpl.DUMMY_HASH)
        );
    }

    @Test
    void exchangeOAuth2CodeWithValidCodeShouldReturnTokenPairAndCleanCode() {
        String code = "validCode123";
        UUID userId = UUID.randomUUID();
        String redisVal = userId.toString() + ":BUYER";
        IssuedTokenPair expectedPair = new IssuedTokenPair("accessToken", "refreshToken");

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(userId)
                .email("test@shopee.com")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();

        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth2:code:" + code)).thenReturn(redisVal);
        when(userService.findAuthenticationDataById(userId)).thenReturn(Optional.of(authData));
        when(refreshTokenService.issueTokenPair(userId, Role.BUYER)).thenReturn(expectedPair);

        IssuedTokenPair result = authService.exchangeOAuth2Code(code);

        assertNotNull(result);
        assertEquals("accessToken", result.accessToken());
        assertEquals("refreshToken", result.refreshToken());
    }

    @Test
    void exchangeOAuth2CodeWithExpiredOrInvalidCodeShouldThrowException() {
        String code = "invalidCode";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth2:code:" + code)).thenReturn(null);

        AppException ex = assertThrows(AppException.class, () -> authService.exchangeOAuth2Code(code));
        assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        assertEquals("Token is invalid or expired", ex.getMessage());
    }

    @Test
    void exchangeOAuth2CodeWithInvalidFormatCodeShouldThrowException() {
        String code = "malformedCode";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth2:code:" + code)).thenReturn("userIdNoColonRole");

        AppException ex = assertThrows(AppException.class, () -> authService.exchangeOAuth2Code(code));
        assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        assertEquals("Token is invalid or expired", ex.getMessage());
    }

    @Test
    void exchangeOAuth2CodeWithMalformedUuidShouldThrowException() {
        String code = "malformedUuidCode";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth2:code:" + code)).thenReturn("not-a-uuid:BUYER");

        AppException ex = assertThrows(AppException.class, () -> authService.exchangeOAuth2Code(code));
        assertEquals(ErrorCode.INVALID_TOKEN, ex.getErrorCode());
        assertEquals("Token is invalid or expired", ex.getMessage());
    }

    @Test
    void exchangeOAuth2CodeWithRedisFailureShouldThrowServiceUnavailable() {
        String code = "redisFailCode";
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth2:code:" + code)).thenThrow(new org.springframework.data.redis.RedisSystemException("Connection lost", new RuntimeException()));

        AppException ex = assertThrows(AppException.class, () -> authService.exchangeOAuth2Code(code));
        assertEquals(ErrorCode.SERVICE_UNAVAILABLE, ex.getErrorCode());
    }

    @Test
    void exchangeOAuth2CodeConcurrentlyShouldAllowOnlyOneSuccess() throws Exception {
        String code = "concurrentCode";
        UUID userId = UUID.randomUUID();
        String redisVal = userId.toString() + ":BUYER";
        IssuedTokenPair expectedPair = new IssuedTokenPair("accessToken", "refreshToken");

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(userId)
                .email("test@shopee.com")
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();

        // Stub consecutive calls: first returns val, second returns null
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.getAndDelete("oauth2:code:" + code))
                .thenReturn(redisVal)
                .thenReturn(null);

        when(userService.findAuthenticationDataById(userId)).thenReturn(Optional.of(authData));
        when(refreshTokenService.issueTokenPair(userId, Role.BUYER)).thenReturn(expectedPair);

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(2);
        java.util.concurrent.Future<IssuedTokenPair> f1 = executor.submit(() -> authService.exchangeOAuth2Code(code));
        java.util.concurrent.Future<IssuedTokenPair> f2 = executor.submit(() -> authService.exchangeOAuth2Code(code));

        int successCount = 0;
        int failCount = 0;

        try {
            IssuedTokenPair res1 = f1.get();
            if (res1 != null) {
                successCount++;
            }
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof AppException appEx && appEx.getErrorCode() == ErrorCode.INVALID_TOKEN) {
                failCount++;
            }
        }

        try {
            IssuedTokenPair res2 = f2.get();
            if (res2 != null) {
                successCount++;
            }
        } catch (java.util.concurrent.ExecutionException e) {
            if (e.getCause() instanceof AppException appEx && appEx.getErrorCode() == ErrorCode.INVALID_TOKEN) {
                failCount++;
            }
        }

        executor.shutdown();

        assertEquals(1, successCount);
        assertEquals(1, failCount);
    }

    @Test
    void forgotPasswordWhenUserExistsAndActiveShouldCreateTokenAndPublishEvent() {
        String email = "test@shopee.com";
        UUID userId = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.parse("2026-06-03T12:00:00Z");

        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(userId)
                .email(email)
                .role(Role.BUYER)
                .status(UserStatus.ACTIVE)
                .build();

        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.of(authData));
        when(verificationTokenGenerator.generate()).thenReturn("rawToken");
        when(verificationTokenGenerator.hash("rawToken")).thenReturn("hashedToken");
        when(clock.instant()).thenReturn(now);
        when(securityProperties.getPasswordResetToken())
                .thenReturn(new com.shopee.monolith.modules.auth.config.AuthSecurityProperties.PasswordResetTokenProperties());
        when(eventPayloadCryptoService.encrypt("rawToken")).thenReturn("encryptedToken");

        ForgotPasswordRequest request = ForgotPasswordRequest.builder().email(email).build();

        authService.forgotPassword(request);

        verify(passwordResetService).createPasswordResetToken(any());
        verify(eventPublisher).publishEvent(any(com.shopee.monolith.modules.user.event.PasswordResetRequestedEvent.class));
    }

    @Test
    void forgotPasswordWhenUserNotFoundShouldNotCreateTokenOrPublishEvent() {
        String email = "unknown@shopee.com";
        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.empty());

        ForgotPasswordRequest request = ForgotPasswordRequest.builder().email(email).build();

        authService.forgotPassword(request);

        verify(passwordResetService, never()).createPasswordResetToken(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void forgotPasswordWhenUserLockedShouldNotCreateTokenOrPublishEvent() {
        String email = "locked@shopee.com";
        UserAuthenticationData authData = UserAuthenticationData.builder()
                .id(UUID.randomUUID())
                .email(email)
                .role(Role.BUYER)
                .status(UserStatus.LOCKED)
                .build();
        when(userService.findAuthenticationDataByEmail(email)).thenReturn(Optional.of(authData));

        ForgotPasswordRequest request = ForgotPasswordRequest.builder().email(email).build();

        authService.forgotPassword(request);

        verify(passwordResetService, never()).createPasswordResetToken(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void resetPasswordShouldDelegateToPasswordResetServiceAndRevokeAllSessions() {
        UUID userId = UUID.randomUUID();
        java.time.Instant now = java.time.Instant.parse("2026-06-03T12:00:00Z");

        when(clock.instant()).thenReturn(now);
        when(verificationTokenGenerator.hash("rawToken")).thenReturn("hashedToken");
        when(passwordEncoder.encode("newPassword123")).thenReturn("encodedHash");
        when(passwordResetService.resetPassword("hashedToken", "encodedHash", now)).thenReturn(userId);

        ResetPasswordRequest request = ResetPasswordRequest.builder().token("rawToken").newPassword("newPassword123").build();

        authService.resetPassword(request);

        verify(passwordResetService).resetPassword("hashedToken", "encodedHash", now);
        verify(sessionRevocationService).logoutAll(userId);
    }
}
