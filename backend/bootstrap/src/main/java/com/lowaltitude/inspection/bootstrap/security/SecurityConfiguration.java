package com.lowaltitude.inspection.bootstrap.security;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.identity.domain.TokenVersionPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties({JwtProperties.class, ApiCorsProperties.class})
public class SecurityConfiguration {

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties properties) {
        return new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(hmacKey(properties)));
    }

    @Bean
    JwtDecoder jwtDecoder(JwtProperties properties, TokenVersionPort tokenVersionPort) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(hmacKey(properties))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new IdentityClaimsJwtValidator(tokenVersionPort)
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            ObjectMapper objectMapper,
            CorsConfigurationSource corsConfigurationSource
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/login", "/actuator/health").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/inspection/services",
                                "/api/v1/inspection/services/*",
                                "/api/v1/inspection/discovery",
                                "/api/v1/platform/weather/current").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/platform/cities").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/activities",
                                "/api/v1/activities/*").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/experiences",
                                "/api/v1/venues",
                                "/api/v1/venues/*",
                                "/api/v1/venues/*/sessions",
                                "/api/v1/venues/*/devices").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/mall/products",
                                "/api/v1/mall/products/*").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/platform/cities/*/search-selections").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) ->
                                writeApiError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        "COMMON_UNAUTHORIZED", "请先登录"))
                        .accessDeniedHandler((request, response, exception) ->
                                writeApiError(response, objectMapper, HttpServletResponse.SC_FORBIDDEN,
                                        "COMMON_FORBIDDEN", "无权限访问"))
                )
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint((request, response, exception) ->
                                writeApiError(response, objectMapper, HttpServletResponse.SC_UNAUTHORIZED,
                                        "COMMON_UNAUTHORIZED", "请先登录"))
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(jwtAuthenticationConverter()))
                );
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(ApiCorsProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                "Authorization",
                "Content-Type",
                "Idempotency-Key",
                "If-Match",
                "X-Request-Id"));
        configuration.setExposedHeaders(List.of("X-Request-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            stringCollectionClaim(jwt, "roles").forEach(role ->
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            stringCollectionClaim(jwt, "permissions").forEach(permission ->
                    authorities.add(new SimpleGrantedAuthority(permission)));
            return authorities;
        });
        return converter;
    }

    private List<String> stringCollectionClaim(Jwt jwt, String claimName) {
        Object claim = jwt.getClaims().get(claimName);
        if (!(claim instanceof Collection<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .toList();
    }

    private SecretKey hmacKey(JwtProperties properties) {
        String encodedSecret = properties.hmacBase64Secret();
        if (encodedSecret == null || encodedSecret.isBlank()) {
            throw new IllegalStateException("security.jwt.hmac-base64-secret must be configured");
        }
        byte[] secret;
        try {
            secret = Base64.getDecoder().decode(encodedSecret);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("security.jwt.hmac-base64-secret must be valid Base64", exception);
        }
        if (secret.length < 32) {
            throw new IllegalStateException("security.jwt.hmac-base64-secret must decode to at least 32 bytes");
        }
        return new SecretKeySpec(secret, "HmacSHA256");
    }

    private void writeApiError(
            HttpServletResponse response,
            ObjectMapper objectMapper,
            int status,
            String code,
            String message
    ) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), new ApiError(code, message, java.util.UUID.randomUUID().toString()));
    }
}
