package com.lowaltitude.inspection.course.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/** 从令牌里取客户账号（课程的控制器用）。取不到或没有客户角色时按没有权限处理。 */
final class CourseSubjects {

    static final String CUSTOMER = "CUSTOMER";

    private CourseSubjects() {
    }

    static UUID customer(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        UUID subjectId;
        Set<String> roles;
        if (principal instanceof CurrentSubject current) {
            subjectId = current.subjectId();
            roles = current.roles();
        } else if (principal instanceof Jwt jwt) {
            try {
                List<String> claimed = jwt.getClaimAsStringList("roles");
                subjectId = UUID.fromString(jwt.getClaimAsString("subjectId"));
                roles = claimed == null ? Set.of() : Set.copyOf(claimed);
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        } else {
            throw new AccessDeniedException("authenticated subject is invalid");
        }
        if (!roles.contains(CUSTOMER)) {
            throw new CourseException(CourseException.Error.FORBIDDEN);
        }
        return subjectId;
    }
}
