package com.lowaltitude.inspection.live.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.live.application.LiveException.Error;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** 从令牌里取账号、角色和机构（直播的控制器共用）。取不到时按没有权限处理。 */
final class LiveSubjects {

    static final String CUSTOMER = "CUSTOMER";
    /** 直播主办（身份迁移 V006）：限定在机构里，只碰本机构的直播间。 */
    static final String LIVE_HOST = "LIVE_HOST";
    /** 平台客服（身份迁移 V004）：隐藏弹幕、强制结束。 */
    static final String PLATFORM_SUPPORT = "PLATFORM_SUPPORT";

    private LiveSubjects() {
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

    /** 客户角色的账号（观众）。 */
    static Subject customer(Authentication authentication) {
        Subject subject = of(authentication);
        if (!subject.roles().contains(CUSTOMER)) {
            throw new LiveException(Error.FORBIDDEN);
        }
        return subject;
    }

    /** 直播主办，并且令牌里有机构。 */
    static Subject host(Authentication authentication) {
        Subject subject = of(authentication);
        if (!subject.roles().contains(LIVE_HOST) || subject.organizationId() == null) {
            throw new LiveException(Error.FORBIDDEN);
        }
        return subject;
    }

    /** 平台客服角色（PLATFORM_SUPPORT）。不看机构。 */
    static Subject platformSupport(Authentication authentication) {
        Subject subject = of(authentication);
        if (!subject.roles().contains(PLATFORM_SUPPORT)) {
            throw new LiveException(Error.FORBIDDEN);
        }
        return subject;
    }
}
