package com.shopee.monolith.modules.auth.config;

import com.shopee.monolith.modules.auth.security.BlacklistFilter;
import com.shopee.monolith.modules.auth.security.CustomOAuth2UserService;
import com.shopee.monolith.modules.auth.security.RateLimitingFilter;
import com.shopee.monolith.modules.auth.security.JwtAuthenticationFilter;
import com.shopee.monolith.modules.auth.security.OAuth2AuthenticationFailureHandler;
import com.shopee.monolith.modules.auth.security.OAuth2AuthenticationSuccessHandler;
import com.shopee.monolith.modules.auth.security.RestAccessDeniedHandler;
import com.shopee.monolith.modules.auth.security.RestAuthenticationEntryPoint;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.session.NullAuthenticatedSessionStrategy;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.http.HttpMethod;

import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;
    private final BlacklistFilter blacklistFilter;
    private final RateLimitingFilter rateLimitingFilter;
    private final RestAuthenticationEntryPoint restAuthenticationEntryPoint;
    private final RestAccessDeniedHandler restAccessDeniedHandler;
    private final AuthSecurityProperties properties;
    private final CustomOAuth2UserService customOAuth2UserService;
    private final OAuth2AuthenticationSuccessHandler oAuth2AuthenticationSuccessHandler;
    private final OAuth2AuthenticationFailureHandler oAuth2AuthenticationFailureHandler;
    private final ClientRegistrationRepository clientRegistrationRepository;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> {
                    CookieCsrfTokenRepository tokenRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
                    tokenRepository.setCookieName(properties.getCsrf().getCookieName());
                    tokenRepository.setHeaderName(properties.getCsrf().getHeaderName());
                    csrf
                            .csrfTokenRepository(tokenRepository)
                            .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                            // Stateless JWT auth means SecurityContextRepository never "contains" a context, so
                            // SessionManagementFilter treats every authenticated request as a brand-new login and
                            // fires CsrfConfigurer's default CsrfAuthenticationStrategy, which deletes the
                            // XSRF-TOKEN cookie on every authenticated request without reissuing one in the same
                            // response (causes sporadic 403 on the next CSRF-protected /api/auth/* call, e.g.
                            // refresh/logout/login). There is no real session to fixate, so disable it here —
                            // SessionManagementConfigurer#sessionAuthenticationStrategy does NOT work for this
                            // because CsrfConfigurer always ADDS its own strategy to that composite regardless.
                            .sessionAuthenticationStrategy(new NullAuthenticatedSessionStrategy())
                            .ignoringRequestMatchers(request -> !request.getRequestURI().startsWith("/api/auth"))
                            .ignoringRequestMatchers("/api/auth/oauth2/exchange");
                })
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable())
                .oauth2Login(oauth2 -> oauth2
                        .authorizationEndpoint(endpoint -> endpoint.authorizationRequestResolver(oauth2AuthorizationRequestResolver()))
                        .userInfoEndpoint(userInfo -> userInfo.userService(customOAuth2UserService))
                        .successHandler(oAuth2AuthenticationSuccessHandler)
                        .failureHandler(oAuth2AuthenticationFailureHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/auth/csrf",
                                "/api/auth/register",
                                "/api/auth/verify",
                                "/api/auth/forgot-password",
                                "/api/auth/reset-password",
                                "/api/auth/oauth2/exchange",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml",
                                "/swagger-ui/**",
                                "/swagger-ui.html"
                        ).permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/shops/me").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/shops/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/categories/*/products").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/homepage").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/shops/*/products").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/search/products").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/search/products/semantic").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/recommendations/home").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/recommendations/chat").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/payments/webhook/vnpay").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/payments/return/vnpay").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products/*/reviews").permitAll()
                        // WebSocket handshake is public; JWT auth happens at STOMP CONNECT
                        .requestMatchers("/ws/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/media/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/media/files/*").permitAll()
                        .requestMatchers(
                                "/actuator/health",
                                "/actuator/health/liveness",
                                "/actuator/health/readiness"
                        ).permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(restAuthenticationEntryPoint)
                        .accessDeniedHandler(restAccessDeniedHandler)
                )
                .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(blacklistFilter, JwtAuthenticationFilter.class)
                .addFilterAfter(rateLimitingFilter, BlacklistFilter.class);

        return http.build();
    }

    @Bean
    public OAuth2AuthorizationRequestResolver oauth2AuthorizationRequestResolver() {
        DefaultOAuth2AuthorizationRequestResolver resolver = new DefaultOAuth2AuthorizationRequestResolver(
                clientRegistrationRepository, "/oauth2/authorization");
        // Force Google's account chooser so login doesn't silently reuse whichever Google
        // account already has an active session in the browser.
        resolver.setAuthorizationRequestCustomizer(customizer ->
                customizer.additionalParameters(params -> params.put("prompt", "select_account")));
        return resolver;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.getCors().getAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key", properties.getCsrf().getHeaderName()));
        configuration.setExposedHeaders(List.of("Authorization"));
        configuration.setAllowCredentials(properties.getCors().isAllowCredentials());

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
