package com.lowaltitude.inspection.account.application;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 从令牌里取出客户账号。地址、购物车、结算都只给客户角色用；别的角色和取不到账号一律 403。
 */
public final class CustomerAccount {

    private static final String CUSTOMER = "CUSTOMER";

    private CustomerAccount() {
    }

    public static UUID of(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentSubject current) {
            if (!current.roles().contains(CUSTOMER)) {
                throw new AccessDeniedException("customer role is required");
            }
            return current.subjectId();
        }
        if (principal instanceof Jwt jwt) {
            List<String> roles;
            UUID subjectId;
            try {
                roles = jwt.getClaimAsStringList("roles");
                subjectId = UUID.fromString(jwt.getClaimAsString("subjectId"));
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
            if (roles == null || !roles.contains(CUSTOMER)) {
                throw new AccessDeniedException("customer role is required");
            }
            return subjectId;
        }
        throw new AccessDeniedException("authenticated subject is invalid");
    }
}
