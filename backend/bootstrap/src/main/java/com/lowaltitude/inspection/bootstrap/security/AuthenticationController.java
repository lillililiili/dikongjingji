package com.lowaltitude.inspection.bootstrap.security;

import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class AuthenticationController {

    private final AuthenticationService authenticationService;

    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @PostMapping("/api/v1/auth/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletRequest servletRequest) {
        if (request.loginName() == null || request.password() == null) {
            return ResponseEntity.badRequest().body(new ApiError(
                    "COMMON_INVALID_ARGUMENT", "请求参数无效", traceId(servletRequest)
            ));
        }
        return authenticationService.authenticate(request.loginName(), request.password())
                .<ResponseEntity<?>>map(token -> ResponseEntity.ok(Map.of(
                        "accessToken", token.accessToken(),
                        "tokenType", "Bearer",
                        "expiresInSeconds", token.expiresInSeconds()
                )))
                .orElseGet(() -> ResponseEntity.status(401).body(new ApiError(
                        "COMMON_UNAUTHORIZED", "请先登录", traceId(servletRequest)
                )));
    }

    private String traceId(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId;
    }

    public record LoginRequest(String loginName, String password) {
    }
}
