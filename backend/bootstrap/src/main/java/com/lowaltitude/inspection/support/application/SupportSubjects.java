package com.lowaltitude.inspection.support.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.support.application.SupportException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** 从令牌里取账号、角色和机构（G1 客服会话的控制器共用）。取不到时按没有权限处理。 */
final class SupportSubjects {

    static final String CUSTOMER = "CUSTOMER";
    static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";
    /** 平台客服（身份迁移 V004）：只用来读和回复对方是平台客服的会话。 */
    static final String PLATFORM_SUPPORT = "PLATFORM_SUPPORT";

    private SupportSubjects() {
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
            throw new SupportException(Error.FORBIDDEN);
        }
        return subject;
    }

    /** 服务商操作员，并且令牌里有机构。 */
    static Subject providerOperator(Authentication authentication) {
        Subject subject = of(authentication);
        if (!subject.roles().contains(PROVIDER_OPERATOR) || subject.organizationId() == null) {
            throw new SupportException(Error.FORBIDDEN);
        }
        return subject;
    }

    /** 平台客服角色（PLATFORM_SUPPORT）。不看机构。 */
    static Subject platformSupport(Authentication authentication) {
        Subject subject = of(authentication);
        if (!subject.roles().contains(PLATFORM_SUPPORT)) {
            throw new SupportException(Error.FORBIDDEN);
        }
        return subject;
    }
}
