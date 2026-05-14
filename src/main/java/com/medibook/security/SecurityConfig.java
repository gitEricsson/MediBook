package com.medibook.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import jakarta.annotation.PostConstruct;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthFilter            jwtAuthFilter;
    private final RateLimitFilter          rateLimitFilter;
    private final SecurityResponseHeaderFilter securityResponseHeaderFilter;
    private final SessionTimeoutFilter     sessionTimeoutFilter;

    @Value("${app.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

    @Value("${app.security.https-redirect:false}")
    private boolean httpsRedirectEnabled;

    @PostConstruct
    public void validateCorsConfig() {
        String[] origins = allowedOrigins.split(",");
        for (String origin : origins) {
            String trimmedOrigin = origin.trim();
            if (trimmedOrigin.startsWith("http://") && httpsRedirectEnabled) {
                log.warn("CORS origin uses insecure HTTP while HTTPS redirect is enabled: {}. "
                        + "Production deployments should use only HTTPS origins.", trimmedOrigin);
            }
        }
    }

    private static final String[] PUBLIC_ENDPOINTS = {
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/2fa/verify",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/email/verify",
            "/api/v1/auth/email/resend",
            // Payment provider webhooks must be accessible without a JWT token
            "/api/v1/payments/webhooks/**",
            // Twilio Conversations webhook — authenticated by X-Twilio-Signature HMAC, not JWT
            "/api/v1/chat/twilio/webhook",
            // MediBook Assistant support widget — auth optional; no PHI access
            "/api/v1/ai/chat",
            // WebSocket upgrade: HTTP auth is not used here.
            // Authentication happens inside JwtHandshakeInterceptor before the WS session opens.
            "/ws/**",
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/api-docs",
            "/api-docs/**",
            "/actuator/health/**",
            "/actuator/prometheus",
            "/health",
            "/health/**",
            "/prometheus",
            "/version",
            "/api/v1/departments",
            "/api/v1/departments/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .headers(headers -> headers
                .contentTypeOptions(contentTypeOptions -> {})
                .frameOptions(frameOptions -> frameOptions.sameOrigin())
                .referrerPolicy(referrerPolicy -> referrerPolicy
                        .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((req, res, e) -> {
                    res.setStatus(HttpStatus.UNAUTHORIZED.value());
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.getWriter().write(
                            "{\"success\":false,\"message\":\"Authentication required\",\"errorCode\":\"UNAUTHORIZED\"}");
                })
                .accessDeniedHandler((req, res, e) -> {
                    res.setStatus(HttpStatus.FORBIDDEN.value());
                    res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                    res.getWriter().write(
                            "{\"success\":false,\"message\":\"Access denied\",\"errorCode\":\"ACCESS_DENIED\"}");
                })
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(PUBLIC_ENDPOINTS).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/metadata/**").permitAll()
                // SUPER_ADMIN is a superset of ADMIN; fine-grained locks via @PreAuthorize per endpoint
                .requestMatchers("/api/v1/admin/**").hasAnyRole("ADMIN", "SUPER_ADMIN")
                // GET doctor search and availability is open to all authenticated users (patients need it to book).
                // Write operations (POST register, PUT update) are further protected by @PreAuthorize in the controller.
                .requestMatchers(HttpMethod.GET, "/api/v1/doctors/**").authenticated()
                .requestMatchers("/api/v1/doctors/**").hasAnyRole("DOCTOR", "ADMIN", "SUPER_ADMIN")
                .anyRequest().authenticated()
            )
            .authenticationProvider(authenticationProvider())
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(sessionTimeoutFilter, JwtAuthFilter.class)
            .addFilterAfter(rateLimitFilter, SessionTimeoutFilter.class)
            .addFilterAfter(securityResponseHeaderFilter, JwtAuthFilter.class);

        return http.build();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        // IMPORTANT: Production deployments should use HTTPS origins only.
        // Allowed origins are configured via app.cors.allowed-origins property.
        // See @PostConstruct validateCorsConfig() for HTTPS enforcement warnings.
        CorsConfiguration configuration = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toList());
        configuration.setAllowedOriginPatterns(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Requested-With", "X-Correlation-Id"));
        configuration.setExposedHeaders(List.of("Authorization", "X-Correlation-Id"));
        configuration.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
