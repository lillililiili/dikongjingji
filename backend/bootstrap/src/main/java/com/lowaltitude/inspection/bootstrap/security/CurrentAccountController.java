package com.lowaltitude.inspection.bootstrap.security;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.bootstrap.api.ApiError;
import com.lowaltitude.inspection.identity.domain.AccountStatePort;
import com.lowaltitude.inspection.identity.domain.CurrentIdentity;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets a signed-in account read what it may show about itself. The subject always comes from
 * the validated token; there is no way to ask for another account.
 */
@RestController
public class CurrentAccountController {

    private final IdentityProfilePort profiles;
    private final AccountStatePort accountState;

    public CurrentAccountController(IdentityProfilePort profiles, AccountStatePort accountState) {
        this.profiles = profiles;
        this.accountState = accountState;
    }

    @GetMapping("/api/v1/auth/me")
    public ResponseEntity<?> currentAccount(Authentication authentication, HttpServletRequest request) {
        Subject subject = subject(authentication);
        if (subject == null) {
            return unauthorized(request);
        }
        return profiles.findProfile(subject.id())
                .<ResponseEntity<?>>map(profile -> {
                    CurrentIdentity identity = accountState.currentIdentity(subject.id());
                    return ResponseEntity.ok(new CurrentAccountResponse(
                            profile.displayName(),
                            profile.organizationName(),
                            profile.organizationType(),
                            subject.roles(),
                            // 2.4.0：只给打码手机号；当前身份以数据库为准（令牌里的 identity 只是签发时的）。
                            accountState.maskedPhone(subject.id()).orElse(null),
                            new CurrentAccountResponse.Identity(identity.type(), identity.enterpriseId(),
                                    identity.enterpriseName(), identity.role())));
                })
                // The token is still valid but the account is gone or disabled.
                .orElseGet(() -> unauthorized(request));
    }

    private static Subject subject(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentSubject current) {
            return new Subject(current.subjectId(), current.roles().stream().sorted().toList());
        }
        if (principal instanceof Jwt jwt) {
            try {
                List<String> roles = jwt.getClaimAsStringList("roles");
                return new Subject(
                        UUID.fromString(jwt.getClaimAsString("subjectId")),
                        roles == null ? List.of() : roles.stream().sorted().toList());
            } catch (RuntimeException invalidClaims) {
                return null;
            }
        }
        return null;
    }

    private static ResponseEntity<ApiError> unauthorized(HttpServletRequest request) {
        String requestId = request.getHeader("X-Request-Id");
        return ResponseEntity.status(401).body(new ApiError(
                "COMMON_UNAUTHORIZED",
                "登录状态已失效，请重新登录",
                requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId));
    }

    private record Subject(UUID id, List<String> roles) {
    }
}
