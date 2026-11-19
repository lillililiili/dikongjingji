package com.lowaltitude.inspection.rental.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.rental.application.RentalException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** 从令牌里取账号、角色和机构（F3 的两个控制器共用）。取不到时按没有权限处理。 */
final class RentalSubjects {

    static final String CUSTOMER = "CUSTOMER";
    static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";

    private RentalSubjects() {
    }

    record Subject(UUID subjectId, Set<String> roles, UUID organizationId) {
    }

    static Subject of(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentSubject current) {
            return new Subject(current.subjectId(), current.roles(), current.organizationId());
        }
        if (principal instanceof Jwt jwt) {
            try {
                List<String> roles = jwt.getClaimAsStringList("roles");
                String organization = jwt.getClaimAsString("organizationId");
                return new Subject(
                        UUID.fromString(jwt.getClaimAsString("subjectId")),
                        roles == null ? Set.of() : Set.copyOf(roles),
                        organization == null || organization.isBlank() ? null : UUID.fromString(organization));
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        }
        throw new AccessDeniedException("authenticated subject is invalid");
    }

    /** 客户角色的账号。 */
    static Subject customer(Authentication authentication) {
        Subject subject = of(authentication);
        if (!subject.roles().contains(CUSTOMER)) {
            throw new RentalException(Error.FORBIDDEN);
        }
        return subject;
    }

    /** 服务商操作员，并且令牌里有机构。 */
    static Subject providerOperator(Authentication authentication) {
        Subject subject = of(authentication);
        if (!subject.roles().contains(PROVIDER_OPERATOR) || subject.organizationId() == null) {
            throw new RentalException(Error.FORBIDDEN);
        }
        return subject;
    }

    /** If-Match 是 ETag：可能带引号或 W/ 前缀。 */
    static int version(String ifMatch) {
        String value = ifMatch == null ? "" : ifMatch.trim();
        if (value.startsWith("W/")) {
            value = value.substring(2);
        }
        value = value.replace("\"", "");
        try {
            int version = Integer.parseInt(value);
            if (version < 0) {
                throw new IllegalArgumentException("If-Match is invalid");
            }
            return version;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("If-Match is invalid", invalid);
        }
    }
}
