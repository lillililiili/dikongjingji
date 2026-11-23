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
        return authenticationService.authenticate(request.loginName().trim(), request.password(),
                        LoginDeviceRequest.toDevice(request.device()))
                .<ResponseEntity<?>>map(token -> ResponseEntity.ok(new LoginResponse(
                        token.accessToken(), "Bearer", token.expiresInSeconds()
                )))
                .orElseGet(() -> ResponseEntity.status(401).body(new ApiError(
                        "COMMON_UNAUTHORIZED", "用户名或密码错误", traceId(servletRequest)
                )));
    }

    private String traceId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }
}
