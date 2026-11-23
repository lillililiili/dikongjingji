package com.lowaltitude.inspection.activity.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * S5-3 新接口取当前账号（和 {@link ActivityEnrollmentController} 的做法一样）：账号、角色、机构只从令牌里取。
 * 客户接口要 CUSTOMER；机构工作台要 PROVIDER_OPERATOR 和令牌里的机构。
 */
final class ActivitySubjects {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";

    private ActivitySubjects() {
    }

    static UUID customer(Authentication authentication) {
        Subject subject = subject(authentication);
        if (!subject.roles().contains(CUSTOMER)) {
            throw new ActivityEnrollmentException(Error.FORBIDDEN);
        }
        return subject.subjectId();
    }

    static Subject operator(Authentication authentication) {
        Subject subject = subject(authentication);
        if (!subject.roles().contains(PROVIDER_OPERATOR) || subject.organizationId() == null) {
            throw new ActivityEnrollmentException(Error.FORBIDDEN);
        }
        return subject;
    }

    private static Subject subject(Authentication authentication) {
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

    record Subject(UUID subjectId, Set<String> roles, UUID organizationId) {
    }
}
