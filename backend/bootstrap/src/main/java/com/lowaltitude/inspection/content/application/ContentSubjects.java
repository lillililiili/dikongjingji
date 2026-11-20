package com.lowaltitude.inspection.content.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 从令牌里取视频互动（1.2.0）的账号、角色和机构。公开读取的接口没带令牌时是“没有人”；写接口由安全配置要求登录。
 */
final class ContentSubjects {

    static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";
    static final String PLATFORM_SUPPORT = "PLATFORM_SUPPORT";

    private ContentSubjects() {
    }

    private record Subject(UUID subjectId, Set<String> roles, UUID organizationId) {
    }

    /** 看的人：没登录（匿名）为 null。 */
    static ContentActor viewer(Authentication authentication) {
        Subject subject = subject(authentication);
        return subject == null ? null : actor(subject);
    }

    /** 写操作的账号：取不到时按没有登录处理（401）。 */
    static ContentActor actor(Authentication authentication) {
        Subject subject = subject(authentication);
        if (subject == null) {
            throw new ContentException(ContentException.Error.UNAUTHORIZED);
        }
        return actor(subject);
    }

    /** 隐藏评论的人：平台客服，或令牌里带机构的服务商、商家操作员；都不是时 403。 */
    static ContentModerator moderator(Authentication authentication) {
        Subject subject = subject(authentication);
        if (subject == null) {
            throw new ContentException(ContentException.Error.UNAUTHORIZED);
        }
        boolean platform = subject.roles().contains(PLATFORM_SUPPORT);
        UUID organization = subject.roles().contains(PROVIDER_OPERATOR) ? subject.organizationId() : null;
        if (!platform && organization == null) {
            throw new ContentException(ContentException.Error.FORBIDDEN);
        }
        return new ContentModerator(subject.subjectId(), platform, organization);
    }

    private static ContentActor actor(Subject subject) {
        return new ContentActor(subject.subjectId(),
                subject.roles().contains(PROVIDER_OPERATOR) ? subject.organizationId() : null);
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
        // 没带令牌：匿名访问公开接口。
        return null;
    }
}
