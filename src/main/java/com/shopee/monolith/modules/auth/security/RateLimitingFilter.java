package com.shopee.monolith.modules.auth.security;

import com.shopee.monolith.common.exception.AppException;
import com.shopee.monolith.common.exception.ErrorCode;
import com.shopee.monolith.modules.auth.config.AuthSecurityProperties;
import com.shopee.monolith.modules.auth.config.AuthSecurityProperties.BucketLimitProperties;
import com.shopee.monolith.modules.auth.dto.internal.AccessTokenClaims;
import com.shopee.monolith.modules.auth.dto.internal.RateLimitResult;
import com.shopee.monolith.modules.auth.service.RateLimitService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class RateLimitingFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimitService;
    private final ClientIpResolver ipResolver;
    private final AuthSecurityProperties properties;
    private final SecurityErrorWriter securityErrorWriter;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        if (!properties.getRateLimit().isEnabled()) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getServletPath();
        if (path == null || path.isEmpty()) {
            path = request.getRequestURI();
        }
        String method = request.getMethod();

        // Bypass health probes and Swagger UI
        if (path != null && (path.equals("/actuator/health")
                || path.equals("/actuator/health/liveness")
                || path.equals("/actuator/health/readiness")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/v3/api-docs"))) {
            filterChain.doFilter(request, response);
            return;
        }

        // Bypass logout endpoint to prevent rate-limit exhaustion from blocking DB revocation
        if (isLogoutEndpoint(path, method)) {
            filterChain.doFilter(request, response);
            return;
        }

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean isAuthenticated = authentication != null
                && authentication.isAuthenticated()
                && authentication.getPrincipal() instanceof AccessTokenClaims;

        String key;
        BucketLimitProperties limitProperties;

        // Resolve rate limit key and limits
        if (path != null && path.equals("/api/auth/login") && "POST".equalsIgnoreCase(method)) {
            String ip = ipResolver.resolveIp(request);
            key = "rate_limit:auth:login:" + ip;
            limitProperties = properties.getRateLimit().getLogin();
        } else if (path != null && path.equals("/api/auth/register") && "POST".equalsIgnoreCase(method)) {
            String ip = ipResolver.resolveIp(request);
            key = "rate_limit:auth:register:" + ip;
            limitProperties = properties.getRateLimit().getRegister();
        } else if (path != null && path.equals("/api/auth/verify") && "POST".equalsIgnoreCase(method)) {
            String ip = ipResolver.resolveIp(request);
            key = "rate_limit:auth:verify:" + ip;
            limitProperties = properties.getRateLimit().getVerify();
        } else if (path != null && path.equals("/api/auth/resend-verification") && "POST".equalsIgnoreCase(method)) {
            String ip = ipResolver.resolveIp(request);
            key = "rate_limit:auth:resend-verification:" + ip;
            limitProperties = properties.getRateLimit().getResendVerification();
        } else if (path != null && (path.equals("/api/auth/forgot-password") || path.equals("/api/auth/reset-password"))
                && "POST".equalsIgnoreCase(method)) {
            String ip = ipResolver.resolveIp(request);
            key = "rate_limit:auth:password-reset:" + ip;
            limitProperties = properties.getRateLimit().getPasswordReset();
        } else if (path != null && path.equals("/api/auth/oauth2/exchange") && "POST".equalsIgnoreCase(method)) {
            String ip = ipResolver.resolveIp(request);
            key = "rate_limit:oauth2:callback:exchange:" + ip;
            limitProperties = properties.getRateLimit().getOauth2Callback();
        } else if (path != null && path.startsWith("/login/oauth2/code/") && "GET".equalsIgnoreCase(method)) {
            String ip = ipResolver.resolveIp(request);
            String provider = path.substring("/login/oauth2/code/".length());
            if (!"google".equalsIgnoreCase(provider) && !"facebook".equalsIgnoreCase(provider)) {
                provider = "unknown";
            }
            key = "rate_limit:oauth2:callback:" + provider + ":" + ip;
            limitProperties = properties.getRateLimit().getOauth2Callback();
        } else if (isWebhookEndpoint(path, method)) {
            // Provider call — never keyed by userId; bucket per provider + IP
            String ip = ipResolver.resolveIp(request);
            String provider = path.substring("/api/payments/webhook/".length());
            key = "rate_limit:webhook:" + provider + ":" + ip;
            limitProperties = properties.getRateLimit().getWebhook();
        } else if (isAuthenticated) {
            AccessTokenClaims claims = (AccessTokenClaims) authentication.getPrincipal();
            key = "rate_limit:user:" + claims.userId().toString();
            limitProperties = properties.getRateLimit().getAuthenticated();
        } else {
            String ip = ipResolver.resolveIp(request);
            key = "rate_limit:ip:" + ip;
            limitProperties = properties.getRateLimit().getAnonymous();
        }

        try {
            RateLimitResult result = rateLimitService.consume(key, limitProperties);
            if (!result.allowed()) {
                securityErrorWriter.writeError(response, ErrorCode.RATE_LIMIT_EXCEEDED);
                return;
            }
        } catch (AppException e) {
            // Redis connection failure or other Service Unavailable issues
            if (isLogoutEndpoint(path, method)) {
                // Fail-open for logout to let session revocation attempt DB removal
                log.warn("Redis rate limiter down during logout. Failing open to allow DB revocation.");
            } else if (isAuthenticated || isAbuseControlEndpoint(path, method) || isCsrfEndpoint(path, method)
                    || isWebhookEndpoint(path, method)) {
                // Fail-closed
                securityErrorWriter.writeError(response, e.getErrorCode());
                return;
            } else {
                // Fail-open for normal public routes
                log.warn("Redis rate limiter down for public route {}. Failing open.", path);
            }
        }

        filterChain.doFilter(request, response);
    }

    private boolean isAbuseControlEndpoint(String path, String method) {
        if (path == null) {
            return false;
        }
        return (path.equals("/api/auth/login") && "POST".equalsIgnoreCase(method))
                || (path.equals("/api/auth/register") && "POST".equalsIgnoreCase(method))
                || (path.equals("/api/auth/verify") && "POST".equalsIgnoreCase(method))
                || (path.equals("/api/auth/resend-verification") && "POST".equalsIgnoreCase(method))
                || (path.equals("/api/auth/forgot-password") && "POST".equalsIgnoreCase(method))
                || (path.equals("/api/auth/reset-password") && "POST".equalsIgnoreCase(method))
                || (path.equals("/api/auth/oauth2/exchange") && "POST".equalsIgnoreCase(method))
                || (path.startsWith("/login/oauth2/code/") && "GET".equalsIgnoreCase(method));
    }

    private boolean isCsrfEndpoint(String path, String method) {
        return path != null && path.equals("/api/auth/csrf") && "GET".equalsIgnoreCase(method);
    }

    private boolean isWebhookEndpoint(String path, String method) {
        return path != null && path.startsWith("/api/payments/webhook/") && "POST".equalsIgnoreCase(method);
    }

    private boolean isLogoutEndpoint(String path, String method) {
        return path != null && path.equals("/api/auth/logout") && "POST".equalsIgnoreCase(method);
    }
}
