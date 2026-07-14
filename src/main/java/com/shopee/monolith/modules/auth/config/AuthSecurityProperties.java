package com.shopee.monolith.modules.auth.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Configuration
@ConfigurationProperties(prefix = "app.security")
@Validated
@Getter
@Setter
public class AuthSecurityProperties {

    @Valid
    @NotNull
    private AuthCookieProperties authCookie = new AuthCookieProperties();

    @Valid
    @NotNull
    private CorsProperties cors = new CorsProperties();

    @Valid
    @NotNull
    private CsrfProperties csrf = new CsrfProperties();

    @Valid
    @NotNull
    private VerificationTokenProperties verificationToken = new VerificationTokenProperties();

    @Valid
    @NotNull
    private PasswordResetTokenProperties passwordResetToken = new PasswordResetTokenProperties();

    @Valid
    @NotNull
    private EventCryptoProperties eventCrypto = new EventCryptoProperties();

    @Valid
    @NotNull
    private OAuth2Properties oauth2 = new OAuth2Properties();

    @Valid
    @NotNull
    private RateLimitProperties rateLimit = new RateLimitProperties();

    private List<String> trustedProxies = List.of();

    @Getter
    @Setter
    public static class AuthCookieProperties {
        @NotBlank
        private String name = "__Secure-refresh_token";
        private boolean secure = true;
        private boolean httpOnly = true;
        @NotBlank
        private String sameSite = "Lax";
        @NotBlank
        private String path = "/api/auth";
    }

    @Getter
    @Setter
    public static class CorsProperties {
        @NotEmpty
        private List<String> allowedOrigins = List.of("http://localhost:3000");
        private boolean allowCredentials = true;
    }

    @Getter
    @Setter
    public static class CsrfProperties {
        @NotBlank
        private String cookieName = "XSRF-TOKEN";
        @NotBlank
        private String headerName = "X-XSRF-TOKEN";
    }

    @jakarta.validation.constraints.AssertTrue(message = "Secure attribute must be true for __Secure- cookies")
    public boolean isSecureCookieValid() {
        if (authCookie != null && authCookie.getName() != null && authCookie.getName().startsWith("__Secure-")) {
            return authCookie.isSecure();
        }
        return true;
    }

    @jakarta.validation.constraints.AssertTrue(message = "SameSite=None requires Secure=true")
    public boolean isSameSiteSecureValid() {
        if (authCookie != null && "None".equalsIgnoreCase(authCookie.getSameSite())) {
            return authCookie.isSecure();
        }
        return true;
    }

    @jakarta.validation.constraints.AssertTrue(message = "CORS allowed origins must be valid URIs and cannot contain path or wildcards if allowCredentials is true")
    public boolean isCorsAllowedOriginsValid() {
        if (cors == null || cors.getAllowedOrigins() == null) {
            return true;
        }
        for (String origin : cors.getAllowedOrigins()) {
            if (origin == null || origin.isBlank()) {
                return false;
            }
            if (cors.isAllowCredentials() && origin.contains("*")) {
                return false;
            }
            try {
                java.net.URI uri = new java.net.URI(origin);
                String scheme = uri.getScheme();
                if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                    return false;
                }
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    return false;
                }
                String path = uri.getRawPath();
                if (path != null && !path.isEmpty()) {
                    return false;
                }
                if (uri.getRawQuery() != null && !uri.getRawQuery().isEmpty()) {
                    return false;
                }
                if (uri.getRawFragment() != null && !uri.getRawFragment().isEmpty()) {
                    return false;
                }
                if (uri.getUserInfo() != null && !uri.getUserInfo().isEmpty()) {
                    return false;
                }
            } catch (java.net.URISyntaxException e) {
                return false;
            }
        }
        return true;
    }

    @jakarta.validation.constraints.AssertTrue(message = "SameSite attribute must be Lax, Strict, or None")
    public boolean isSameSiteValid() {
        if (authCookie != null && authCookie.getSameSite() != null) {
            String sameSite = authCookie.getSameSite();
            return "Lax".equalsIgnoreCase(sameSite) || "Strict".equalsIgnoreCase(sameSite) || "None".equalsIgnoreCase(sameSite);
        }
        return true;
    }

    @jakarta.validation.constraints.AssertTrue(message = "Verification token TTL must be positive")
    public boolean isVerificationTokenTtlValid() {
        return verificationToken != null && verificationToken.getTtl() != null
                && !verificationToken.getTtl().isNegative() && !verificationToken.getTtl().isZero();
    }

    @jakarta.validation.constraints.AssertTrue(message = "Password reset token TTL must be positive")
    public boolean isPasswordResetTokenTtlValid() {
        return passwordResetToken != null && passwordResetToken.getTtl() != null
                && !passwordResetToken.getTtl().isNegative() && !passwordResetToken.getTtl().isZero();
    }

    @jakarta.validation.constraints.AssertTrue(message = "Event active crypto secret must be at least 32 bytes")
    public boolean isEventCryptoActiveSecretValid() {
        return eventCrypto != null && eventCrypto.getActiveSecret() != null
                && eventCrypto.getActiveSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32;
    }

    @jakarta.validation.constraints.AssertTrue(message = "Event previous crypto secret must be at least 32 bytes if previous key is configured")
    public boolean isEventCryptoPreviousSecretValid() {
        if (eventCrypto == null) {
            return true;
        }
        boolean hasPrevId = eventCrypto.getPreviousKeyId() != null && !eventCrypto.getPreviousKeyId().isBlank();
        boolean hasPrevSec = eventCrypto.getPreviousSecret() != null && !eventCrypto.getPreviousSecret().isBlank();
        if (hasPrevId || hasPrevSec) {
            if (eventCrypto.getPreviousKeyId() == null || eventCrypto.getPreviousKeyId().isBlank() ||
                    eventCrypto.getPreviousSecret() == null || eventCrypto.getPreviousSecret().isBlank()) {
                return false;
            }
            return eventCrypto.getPreviousSecret().getBytes(java.nio.charset.StandardCharsets.UTF_8).length >= 32;
        }
        return true;
    }

    @jakarta.validation.constraints.AssertTrue(message = "Event active key ID and previous key ID must not be identical")
    public boolean isEventCryptoKeyIdsDifferent() {
        if (eventCrypto == null) {
            return true;
        }
        String activeId = eventCrypto.getActiveKeyId();
        String prevId = eventCrypto.getPreviousKeyId();
        if (activeId == null || activeId.isBlank() || prevId == null || prevId.isBlank()) {
            return true;
        }
        return !activeId.equals(prevId);
    }

    @Getter
    @Setter
    public static class VerificationTokenProperties {
        @NotNull
        private java.time.Duration ttl = java.time.Duration.ofHours(24);
    }

    @Getter
    @Setter
    public static class PasswordResetTokenProperties {
        @NotNull
        private java.time.Duration ttl = java.time.Duration.ofMinutes(30);
    }

    @Getter
    @Setter
    public static class EventCryptoProperties {
        @NotBlank
        private String activeKeyId = "crypto-v1";
        @NotBlank
        private String activeSecret;

        private String previousKeyId;
        private String previousSecret;
    }

    @Getter
    @Setter
    public static class OAuth2Properties {
        @NotNull
        private java.time.Duration exchangeCodeTtl = java.time.Duration.ofSeconds(60);
    }

    @jakarta.validation.constraints.AssertTrue(message = "OAuth2 exchange code TTL must be positive")
    public boolean isOAuth2ExchangeCodeTtlValid() {
        return oauth2 != null && oauth2.getExchangeCodeTtl() != null
                && !oauth2.getExchangeCodeTtl().isNegative() && !oauth2.getExchangeCodeTtl().isZero();
    }

    @jakarta.validation.constraints.AssertTrue(message = "Rate limit windows must be positive")
    public boolean isRateLimitWindowsValid() {
        if (rateLimit == null) {
            return true;
        }
        return isBucketLimitWindowValid(rateLimit.getAnonymous())
                && isBucketLimitWindowValid(rateLimit.getAuthenticated())
                && isBucketLimitWindowValid(rateLimit.getLogin())
                && isBucketLimitWindowValid(rateLimit.getRegister())
                && isBucketLimitWindowValid(rateLimit.getVerify())
                && isBucketLimitWindowValid(rateLimit.getOauth2Callback())
                && isBucketLimitWindowValid(rateLimit.getResendVerification())
                && isBucketLimitWindowValid(rateLimit.getPasswordReset());
    }

    private boolean isBucketLimitWindowValid(BucketLimitProperties limit) {
        return limit != null && limit.getWindow() != null
                && !limit.getWindow().isNegative() && !limit.getWindow().isZero();
    }

    @jakarta.validation.constraints.AssertTrue(message = "Trusted proxies must be valid CIDR expressions")
    public boolean isTrustedProxiesValid() {
        if (trustedProxies == null) {
            return true;
        }
        for (String proxy : trustedProxies) {
            if (proxy == null || proxy.isBlank()) {
                return false;
            }
            try {
                new org.springframework.security.web.util.matcher.IpAddressMatcher(proxy);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return true;
    }

    @Getter
    @Setter
    public static class RateLimitProperties {
        private boolean enabled = true;

        @Valid
        @NotNull
        private BucketLimitProperties anonymous = new BucketLimitProperties(100, java.time.Duration.ofMinutes(1));

        @Valid
        @NotNull
        private BucketLimitProperties authenticated = new BucketLimitProperties(300, java.time.Duration.ofMinutes(1));

        @Valid
        @NotNull
        private BucketLimitProperties login = new BucketLimitProperties(10, java.time.Duration.ofMinutes(1));

        @Valid
        @NotNull
        private BucketLimitProperties register = new BucketLimitProperties(5, java.time.Duration.ofMinutes(1));

        @Valid
        @NotNull
        private BucketLimitProperties verify = new BucketLimitProperties(20, java.time.Duration.ofMinutes(1));

        @Valid
        @NotNull
        private BucketLimitProperties oauth2Callback = new BucketLimitProperties(30, java.time.Duration.ofMinutes(1));

        @Valid
        @NotNull
        private BucketLimitProperties resendVerification = new BucketLimitProperties(3, java.time.Duration.ofMinutes(1));

        @Valid
        @NotNull
        private BucketLimitProperties passwordReset = new BucketLimitProperties(3, java.time.Duration.ofMinutes(1));

        @Valid
        @NotNull
        private BucketLimitProperties webhook = new BucketLimitProperties(120, java.time.Duration.ofMinutes(1));
    }

    @Getter
    @Setter
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class BucketLimitProperties {
        @jakarta.validation.constraints.Min(1)
        private int capacity;

        @NotNull
        private java.time.Duration window;
    }
}
