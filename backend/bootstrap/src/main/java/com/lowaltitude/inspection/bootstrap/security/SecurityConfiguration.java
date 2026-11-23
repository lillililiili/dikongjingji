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
                        // 续期和退出登录凭刷新令牌，不要访问令牌（续期时它通常已过期）。
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/refresh",
                                "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/inspection/portal",
                                "/api/v1/inspection/filters",
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
                                // S5-4：一个体验项目的模板和试用时长，公开只读；体验记录走下面的 authenticated()。
                                "/api/v1/experiences/*",
                                "/api/v1/venues",
                                "/api/v1/venues/*",
                                "/api/v1/venues/*/sessions",
                                "/api/v1/venues/*/devices",
                                // R6-2 先体验：商品能在哪体验，公开只读。
                                "/api/v1/experience/product-experiences").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/mall/products",
                                "/api/v1/mall/products/*",
                                // S5-2 商家主页：公开只读。
                                "/api/v1/mall/merchants/*",
                                // 开发环境回读运营端上传的商品图片（只在配置了本机目录时有这个接口）。
                                "/api/v1/mall/media/products/*").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/home/recommend").permitAll()
                        // 四类专业服务的目录、详情和服务商主页（F2）：公开只读。
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/pro-services",
                                "/api/v1/pro-services/*",
                                "/api/v1/providers/*").permitAll()
                        // 发现页的图文内容和专题（H1）公开只读。
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/content/feed",
                                "/api/v1/content/articles/*",
                                "/api/v1/content/topics/*",
                                // R6-3 相关内容：提到某个对象的图文，公开只读。
                                "/api/v1/content/related",
                                // 1.2.0 视频详情、视频流和评论列表：不登录也能看；带了令牌就按登录的人给“我点过赞”等。
                                // 点赞、关注、发评论、举报和隐藏评论走下面的 authenticated()。
                                "/api/v1/content/videos",
                                "/api/v1/content/videos/*",
                                "/api/v1/content/videos/*/comments").permitAll()
                        // 课程（S5-5）：列表、线上课详情、试听公开只读；“我和这门课”、购买、学习、退款走下面的 authenticated()。
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/courses",
                                "/api/v1/courses/*",
                                "/api/v1/course-trials").permitAll()
                        // 直播（M20）：列表、详情、弹幕增量公开只读；进直播间记一次观看不登录也记（按设备去重）。
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/live/rooms",
                                "/api/v1/live/rooms/*",
                                "/api/v1/live/rooms/*/comments").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/live/rooms/*/views").permitAll()
                        // 直播服务商回调：不登录，靠签名（LiveStreamProvider.verifyCallback）。
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/live/stream-callbacks").permitAll()
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/platform/cities/*/search-selections").permitAll()
                        // 微信支付回调：不登录，靠微信支付公钥验签（TradeWechatNotificationController）。
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/trade/channels/wechat/notifications").permitAll()
                        // 运营端（S3-1）：只给平台运营和平台客服；具体接口再由各 ops 控制器经 OpsActors 收紧到其中一个角色。
                        .requestMatchers("/api/v1/ops/**").hasAnyRole("PLATFORM_OPERATOR", "PLATFORM_SUPPORT")
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
