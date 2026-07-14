package com.shopee.monolith.modules.auth.controller;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.common.response.ApiResponse;
import com.shopee.monolith.common.response.SwaggerResponses;
import com.shopee.monolith.modules.auth.dto.response.AuthSwaggerResponses;
import com.shopee.monolith.modules.auth.config.AuthSecurityProperties;
import com.shopee.monolith.modules.auth.config.JwtProperties;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.auth.dto.internal.IssuedTokenPair;
import com.shopee.monolith.modules.auth.dto.request.LoginRequest;
import com.shopee.monolith.modules.auth.dto.request.OAuth2ExchangeRequest;
import com.shopee.monolith.modules.auth.dto.response.LoginResponse;
import com.shopee.monolith.modules.auth.service.AuthService;
import com.shopee.monolith.modules.auth.service.RefreshTokenService;
import com.shopee.monolith.modules.auth.service.SessionRevocationService;
import com.shopee.monolith.modules.auth.dto.request.ForgotPasswordRequest;
import com.shopee.monolith.modules.auth.dto.request.RegisterRequest;
import com.shopee.monolith.modules.auth.dto.request.ResetPasswordRequest;
import com.shopee.monolith.modules.auth.dto.request.VerifyRequest;
import com.shopee.monolith.modules.user.dto.response.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Authentication APIs for users (Login, Register, Verify, Refresh, OAuth2, Logout)")
public class AuthController {

    private final AuthService authService;
    private final RefreshTokenService refreshTokenService;
    private final SessionRevocationService revocationService;
    private final AuthSecurityProperties properties;
    private final JwtProperties jwtProperties;

    @Operation(summary = "Get CSRF Token", description = "Retrieves the CSRF token and initializes the XSRF-TOKEN cookie.")
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "CSRF Token initialized successfully.",
            headers = @Header(name = "Set-Cookie", description = "Sets the XSRF-TOKEN cookie containing the CSRF token", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded for CSRF token retrieval.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Security filters or system configuration services are unavailable.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @GetMapping("/csrf")
    public ApiResponse<Void> getCsrfToken(HttpServletRequest request) {
        org.springframework.security.web.csrf.CsrfToken token =
                (org.springframework.security.web.csrf.CsrfToken) request.getAttribute(org.springframework.security.web.csrf.CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        return ApiResponse.success();
    }

    @Operation(summary = "User Login", description = "Authenticates a user with email and password, issuing an access token and setting a secure HttpOnly refresh token cookie.")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token retrieved from the XSRF-TOKEN cookie", schema = @Schema(type = "string"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Login successful.",
            headers = @Header(name = "Set-Cookie", description = "Sets the __Secure-refresh_token cookie containing the refresh token", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = AuthSwaggerResponses.ApiResponseLoginResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Invalid credentials (email or password).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "User email address is not verified, account is suspended/locked, or the CSRF token is invalid.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded for login attempts from this IP.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Rate limiting service or session store database is down (fail-closed).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletResponse response) {
        IssuedTokenPair tokenPair = authService.login(request);
        setRefreshTokenCookie(response, tokenPair.refreshToken(), jwtProperties.getRefreshExpiration());
        return ApiResponse.success(new LoginResponse(tokenPair.accessToken()));
    }

    @Operation(summary = "Exchange OAuth2 Code", description = "Exchanges a redirect one-time code for a JWT access token and sets a secure refresh token cookie.")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token retrieved from the XSRF-TOKEN cookie", schema = @Schema(type = "string"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "OAuth2 code exchange successful.",
            headers = @Header(name = "Set-Cookie", description = "Sets the __Secure-refresh_token cookie containing the refresh token", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = AuthSwaggerResponses.ApiResponseLoginResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid, expired, or already used exchange code.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "CSRF token validation failed, or user account associated with OAuth identity is suspended/locked.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "OAuth identity is already linked to another registered account.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded for OAuth2 code exchange attempts.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Redis or rate limiting service is temporarily unavailable (fail-closed).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/oauth2/exchange")
    public ApiResponse<LoginResponse> exchangeOAuth2Code(@Valid @RequestBody OAuth2ExchangeRequest request, HttpServletResponse response) {
        IssuedTokenPair tokenPair = authService.exchangeOAuth2Code(request.code());
        setRefreshTokenCookie(response, tokenPair.refreshToken(), jwtProperties.getRefreshExpiration());
        return ApiResponse.success(new LoginResponse(tokenPair.accessToken()));
    }

    @Operation(summary = "Register User", description = "Registers a new user account and triggers verification email delivery.")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token retrieved from the XSRF-TOKEN cookie", schema = @Schema(type = "string"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Registration successful. User details returned.",
            content = @Content(schema = @Schema(implementation = AuthSwaggerResponses.ApiResponseUserResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid input details (e.g. invalid email domain, short password).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "CSRF token validation failed.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "409",
            description = "Email address is already registered.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded for registration attempts.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Database connection or rate limiting service is temporarily down (fail-closed).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/register")
    public ApiResponse<UserResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @Operation(summary = "Verify Email address", description = "Consumes a verification token atomically to verify and activate a user's account.")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token retrieved from the XSRF-TOKEN cookie", schema = @Schema(type = "string"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Email verification successful.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid, expired, or already used email verification token.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "CSRF token validation failed.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded for verification attempts.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Database or verification service is down (fail-closed).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/verify")
    public ApiResponse<Void> verify(@Valid @RequestBody VerifyRequest request) {
        authService.verify(request);
        return ApiResponse.success();
    }

    @Operation(summary = "Forgot Password", description = "Initiates a password reset by emailing a reset link if the account exists. Always returns 200 to prevent email enumeration.")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token retrieved from the XSRF-TOKEN cookie", schema = @Schema(type = "string"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Request accepted. A reset email is sent only if the account exists.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "CSRF token validation failed.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded for password reset requests.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/forgot-password")
    public ApiResponse<Void> forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        authService.forgotPassword(request);
        return ApiResponse.success();
    }

    @Operation(summary = "Reset Password", description = "Consumes a password reset token atomically to set a new password and revokes all existing sessions.")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token retrieved from the XSRF-TOKEN cookie", schema = @Schema(type = "string"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Password reset successful. All sessions for this account are revoked.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "400",
            description = "Invalid, expired, or already used password reset token.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "CSRF token validation failed, or the account is locked.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded for password reset requests.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/reset-password")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        authService.resetPassword(request);
        return ApiResponse.success();
    }

    @Operation(summary = "Rotate Access Token", description = "Uses the refresh token cookie to rotate and issue a new access token and a rotated refresh token cookie.")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token retrieved from the XSRF-TOKEN cookie", schema = @Schema(type = "string"))
    @Parameter(name = "__Secure-refresh_token", in = ParameterIn.COOKIE, required = true, description = "Opaque refresh token cookie", schema = @Schema(type = "string"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Token rotation successful.",
            headers = @Header(name = "Set-Cookie", description = "Sets the rotated __Secure-refresh_token cookie", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = AuthSwaggerResponses.ApiResponseLoginResponse.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Invalid, revoked, or expired refresh token. If a token reuse is detected, all tokens in the family are revoked.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "CSRF token validation failed, or user account associated with this token is suspended/locked.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded for token refresh requests.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Redis blacklist or session database is temporarily down (fail-closed).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = getRefreshTokenFromCookiesOrNull(request);
        if (rawToken == null) {
            throw new AppException(ErrorCode.INVALID_TOKEN);
        }
        IssuedTokenPair tokenPair = refreshTokenService.rotate(rawToken);
        setRefreshTokenCookie(response, tokenPair.refreshToken(), jwtProperties.getRefreshExpiration());
        return ApiResponse.success(new LoginResponse(tokenPair.accessToken()));
    }

    @Operation(summary = "Logout Current Session", description = "Invalidates the current session's refresh token and blacklists the current access token. Bearer access token and refresh cookie are both optional at runtime to support idempotent retries.")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token retrieved from the XSRF-TOKEN cookie", schema = @Schema(type = "string"))
    @Parameter(name = "__Secure-refresh_token", in = ParameterIn.COOKIE, required = false, description = "Opaque refresh token cookie to revoke (optional)", schema = @Schema(type = "string"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "Logout successful.",
            headers = @Header(name = "Set-Cookie", description = "Clears the __Secure-refresh_token cookie", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "CSRF token validation failed.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Redis blacklist is temporarily down. Returns 503 to trigger a client retry (fail-closed).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String rawToken = getRefreshTokenFromCookiesOrNull(request);
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccessTokenClaims claims) {
            revocationService.logout(rawToken, claims);
        } else if (rawToken != null) {
            revocationService.logout(rawToken, null);
        }
        clearRefreshTokenCookie(response);
        return ApiResponse.success();
    }

    @Operation(summary = "Logout All Sessions", description = "Invalidates all active sessions and refresh tokens for the authenticated user.")
    @SecurityRequirement(name = "bearerAuth")
    @Parameter(name = "X-XSRF-TOKEN", in = ParameterIn.HEADER, required = true, description = "CSRF token retrieved from the XSRF-TOKEN cookie", schema = @Schema(type = "string"))
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "200",
            description = "All sessions revoked successfully.",
            headers = @Header(name = "Set-Cookie", description = "Clears the __Secure-refresh_token cookie", schema = @Schema(type = "string")),
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "401",
            description = "Authentication required or invalid JWT access token.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "403",
            description = "CSRF token validation failed.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "429",
            description = "Rate limit exceeded for logout requests.",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @io.swagger.v3.oas.annotations.responses.ApiResponse(
            responseCode = "503",
            description = "Session database or revocation service is down (fail-closed).",
            content = @Content(schema = @Schema(implementation = SwaggerResponses.ApiResponseVoid.class))
    )
    @PostMapping("/logout-all")
    public ApiResponse<Void> logoutAll(HttpServletResponse response) {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof AccessTokenClaims claims) {
            revocationService.logoutAll(claims.userId());
        } else {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }
        clearRefreshTokenCookie(response);
        return ApiResponse.success();
    }

    private void setRefreshTokenCookie(HttpServletResponse response, String token, Duration duration) {
        var cookieProperties = properties.getAuthCookie();
        ResponseCookie cookie = ResponseCookie.from(cookieProperties.getName(), token)
                .httpOnly(cookieProperties.isHttpOnly())
                .secure(cookieProperties.isSecure())
                .path(cookieProperties.getPath())
                .maxAge(duration)
                .sameSite(cookieProperties.getSameSite())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearRefreshTokenCookie(HttpServletResponse response) {
        var cookieProperties = properties.getAuthCookie();
        ResponseCookie cookie = ResponseCookie.from(cookieProperties.getName(), "")
                .httpOnly(cookieProperties.isHttpOnly())
                .secure(cookieProperties.isSecure())
                .path(cookieProperties.getPath())
                .maxAge(0)
                .sameSite(cookieProperties.getSameSite())
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String getRefreshTokenFromCookiesOrNull(HttpServletRequest request) {
        var cookieProperties = properties.getAuthCookie();
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie cookie : cookies) {
                if (cookieProperties.getName().equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }
}
