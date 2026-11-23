package com.lowaltitude.inspection.ops;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * 运营端（/api/v1/ops/…，S3-1）的“谁在操作”。所有 ops 控制器都从这里取操作人，不自己解析令牌：
 * <ul>
 *   <li>{@link #operator} 平台运营（PLATFORM_OPERATOR）：城市开通、机构入驻与资质审核，以后的商品、活动、场馆、内容等运营页。</li>
 *   <li>{@link #support} 平台客服（PLATFORM_SUPPORT）：反馈与举报处理。</li>
 * </ul>
 * 角色不对时抛 AccessDeniedException，由全局异常处理给 403 COMMON_FORBIDDEN。安全配置对 /api/v1/ops/** 另外要求
 * 这两个角色之一（SecurityConfiguration），这里再按具体接口收紧。操作人的显示名写进各模块的操作记录（留痕）。
 */
@Component
public class OpsActors {

    private final IdentityProfilePort profiles;

    public OpsActors(IdentityProfilePort profiles) {
        this.profiles = profiles;
    }

    /** 平台运营。 */
    public OpsActor operator(Authentication authentication) {
        return require(authentication, OpsRoles.PLATFORM_OPERATOR);
    }

    /** 平台客服。 */
    public OpsActor support(Authentication authentication) {
        return require(authentication, OpsRoles.PLATFORM_SUPPORT);
    }

    private OpsActor require(Authentication authentication, String role) {
        Subject subject = subject(authentication);
        if (!subject.roles().contains(role)) {
            throw new AccessDeniedException("ops route needs " + role);
        }
        // 账号还在用时取它的显示名；令牌有效但账号已经停用时按没有权限处理。
        String name = profiles.findProfile(subject.id())
                .map(IdentityProfile::displayName)
                .filter(displayName -> !displayName.isBlank())
                .orElseThrow(() -> new AccessDeniedException("operator account is not active"));
        return new OpsActor(subject.id(), name);
    }

    private static Subject subject(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        if (principal instanceof CurrentSubject current) {
            return new Subject(current.subjectId(), current.roles());
        }
        if (principal instanceof Jwt jwt) {
            try {
                List<String> roles = jwt.getClaimAsStringList("roles");
                return new Subject(UUID.fromString(jwt.getClaimAsString("subjectId")),
                        roles == null ? Set.of() : Set.copyOf(roles));
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        }
        throw new AccessDeniedException("authenticated subject is invalid");
    }

    private record Subject(UUID id, Set<String> roles) {
    }
}
