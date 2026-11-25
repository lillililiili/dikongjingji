package com.lowaltitude.inspection.bootstrap.security;

import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController {

    private static final int LOGIN_NAME_MAX_LENGTH = 80;
    private static final int PASSWORD_MAX_LENGTH = 256;
    private static final int REFRESH_TOKEN_MAX_LENGTH = 128;

    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        if (request.loginName() == null || request.loginName().isBlank()
                || request.loginName().length() > LOGIN_NAME_MAX_LENGTH
                || request.password() == null || request.password().isBlank()
                || request.password().length() > PASSWORD_MAX_LENGTH) {
            return ResponseEntity.badRequest().body(new ApiError(
                    "COMMON_INVALID_ARGUMENT", "请求参数无效", traceId(servletRequest)
            ));
        }
        // 2.7.0 (S5-8): the optional device names this login in 登录设备.
        return authenticationService.authenticate(request.loginName().trim(), request.password(),
                        LoginDeviceRequest.toDevice(request.device()))
                .<ResponseEntity<?>>map(token -> ResponseEntity.ok(response(token)))
                .orElseGet(() -> ResponseEntity.status(401).body(new ApiError(
                        "COMMON_UNAUTHORIZED", "用户名或密码错误", traceId(servletRequest)
                )));
    }

    // A refresh token is the credential here: no access token is needed (it has usually run out).
    // Every failure (unknown, expired, withdrawn, reused, account changed) is the same 401.
    @PostMapping("/api/v1/auth/refresh")
    public ResponseEntity<?> refresh(@RequestBody(required = false) RefreshTokenRequest request,
                                     HttpServletRequest servletRequest) {
        if (!validRefreshToken(request)) {
            return invalidArgument(servletRequest);
        }
        return authenticationService.refresh(request.refreshToken())
                .<ResponseEntity<?>>map(token -> ResponseEntity.ok(response(token)))
                .orElseGet(() -> ResponseEntity.status(401).body(new ApiError(
                        "COMMON_UNAUTHORIZED", "登录已过期，请重新登录", traceId(servletRequest)
                )));
    }

    // Signing out withdraws the login of this refresh token. The answer is 204 whether or not the
    // token was known, so it tells nothing about tokens. Access tokens already issued run out by themselves.
    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<?> logout(@RequestBody(required = false) RefreshTokenRequest request,
                                    HttpServletRequest servletRequest) {
        if (!validRefreshToken(request)) {
            return invalidArgument(servletRequest);
        }
        authenticationService.logout(request.refreshToken());
        return ResponseEntity.noContent().build();
    }

    private static boolean validRefreshToken(RefreshTokenRequest request) {
        return request != null
                && request.refreshToken() != null
                && !request.refreshToken().isBlank()
                && request.refreshToken().length() <= REFRESH_TOKEN_MAX_LENGTH;
    }

    private ResponseEntity<?> invalidArgument(HttpServletRequest servletRequest) {
        return ResponseEntity.badRequest().body(new ApiError(
                "COMMON_INVALID_ARGUMENT", "请求参数无效", traceId(servletRequest)
        ));
    }

    static LoginResponse response(AuthenticationService.LoginToken token) {
        return new LoginResponse(
                token.accessToken(), "Bearer", token.expiresInSeconds(),
                token.refreshToken(), token.refreshExpiresInSeconds());
    }

    private String traceId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
