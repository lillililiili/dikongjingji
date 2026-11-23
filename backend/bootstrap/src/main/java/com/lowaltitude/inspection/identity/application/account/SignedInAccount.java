package com.lowaltitude.inspection.identity.application.account;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 从令牌里取登录账号和登录会话（sid）。账号只来自令牌，请求里不接受任何指定账号的参数。
 * 设备、手机号、注销、导出给任何登录账号；企业相关接口只给客户角色（{@link #customer}）。
 */
public record SignedInAccount(UUID subjectId, UUID sessionId, List<String> roles) {

    private static final String CUSTOMER = "CUSTOMER";

    public static SignedInAccount of(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentSubject current) {
            return new SignedInAccount(current.subjectId(), null, List.copyOf(current.roles()));
        }
        if (principal instanceof Jwt jwt) {
            try {
                List<String> roles = jwt.getClaimAsStringList("roles");
                String sid = jwt.getClaimAsString("sid");
                return new SignedInAccount(UUID.fromString(jwt.getClaimAsString("subjectId")),
                        sid == null ? null : UUID.fromString(sid), roles == null ? List.of() : List.copyOf(roles));
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        }
        throw new AccessDeniedException("authenticated subject is invalid");
    }

    public static SignedInAccount customer(Authentication authentication) {
        SignedInAccount account = of(authentication);
        if (!account.roles().contains(CUSTOMER)) {
            throw new AccessDeniedException("customer role is required");
        }
        return account;
    }
}
