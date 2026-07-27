package com.lowaltitude.inspection.bootstrap.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.identity.domain.TokenVersionPort;
import com.lowaltitude.inspection.identity.infrastructure.FailClosedIdentityAccessAdapter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@EnableConfigurationProperties(JwtProperties.class)
public class SecurityConfiguration {

    @Bean
    FailClosedIdentityAccessAdapter failClosedIdentityAccessAdapter() {
        return new FailClosedIdentityAccessAdapter();
    }

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
                JwtValidators.createDefault(), new TokenVersionJwtValidator(tokenVersionPort)
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtDecoder jwtDecoder,
            ObjectMapper objectMapper
    ) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/api/v1/auth/login", "/actuator/health").permitAll()
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

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            List<GrantedAuthority> authorities = new ArrayList<>();
            jwt.getClaimAsStringList("roles").forEach(role ->
                    authorities.add(new SimpleGrantedAuthority("ROLE_" + role)));
            jwt.getClaimAsStringList("permissions").forEach(permission ->
                    authorities.add(new SimpleGrantedAuthority(permission)));
            return authorities;
        });
        return converter;
    }

    private SecretKey hmacKey(JwtProperties properties) {
        if (properties.hmacSecret() == null || properties.hmacSecret().isBlank()) {
            throw new IllegalStateException("security.jwt.hmac-secret must be configured");
        }
        return new SecretKeySpec(properties.hmacSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
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
