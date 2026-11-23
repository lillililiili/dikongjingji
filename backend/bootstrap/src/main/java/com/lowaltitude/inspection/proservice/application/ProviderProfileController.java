package com.lowaltitude.inspection.proservice.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import com.lowaltitude.inspection.ops.common.OpsException;
import com.lowaltitude.inspection.proservice.application.ProviderProfileService.ProfileDraft;
import com.lowaltitude.inspection.proservice.application.ProviderProfileService.ReviewView;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 服务商工作台的“服务商资料”（S4-4，契约 openapi-proservice.yaml 1.2.0）：读本机构的主页资料和审核进度、提交新的一份去审核。
 * 要服务商操作员角色和令牌里的机构，机构要有在用的服务商资料；机构只从令牌里取。
 */
@RestController
public final class ProviderProfileController {

    static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";

    private final ProviderProfileService service;
    private final IdentityProfilePort profiles;

    public ProviderProfileController(ProviderProfileService service, IdentityProfilePort profiles) {
        this.service = service;
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/provider/profile")
    public ReviewView mine(Authentication authentication) {
        return service.mine(operator(authentication).organizationId());
    }

    @PutMapping("/api/v1/provider/profile")
    public ReviewView submit(Authentication authentication, @RequestBody ProfileRequest request) {
        Operator operator = operator(authentication);
        String name = profiles.findProfile(operator.accountId()).map(IdentityProfile::displayName)
                .filter(value -> !value.isBlank()).orElse("服务商操作员");
        return service.submit(operator.organizationId(), operator.accountId(), name, new ProfileDraft(
                request.legalEntity(), request.serviceModes(), request.authorizationUntil(), request.deliverables(),
                request.localTeamLabel(), request.version()));
    }

    record Operator(UUID accountId, UUID organizationId) {
    }

    private static Operator operator(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        UUID account;
        UUID organization;
        Set<String> roles;
        if (principal instanceof CurrentSubject current) {
            account = current.subjectId();
            organization = current.organizationId();
            roles = current.roles();
        } else if (principal instanceof Jwt jwt) {
            try {
                List<String> claimed = jwt.getClaimAsStringList("roles");
                String org = jwt.getClaimAsString("organizationId");
                account = UUID.fromString(jwt.getClaimAsString("subjectId"));
                organization = org == null || org.isBlank() ? null : UUID.fromString(org);
                roles = claimed == null ? Set.of() : Set.copyOf(claimed);
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        } else {
            throw new AccessDeniedException("authenticated subject is invalid");
        }
        if (!roles.contains(PROVIDER_OPERATOR) || organization == null) {
            throw new OpsException(ProviderProfileService.ProfileError.NOT_PROVIDER);
        }
        return new Operator(account, organization);
    }

    /** authorizationUntil：YYYY-MM-DD，不早于今天（北京时间）；version：读到的版本，还没提交过是 0。 */
    record ProfileRequest(
            String legalEntity,
            String serviceModes,
            String authorizationUntil,
            List<String> deliverables,
            String localTeamLabel,
            Integer version
    ) {
    }
}
