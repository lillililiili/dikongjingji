package com.lowaltitude.inspection.trade.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * 售后（S4-2）和商家资料（S5-2）的控制器从令牌里取账号、角色和机构；取不到或角色不对时 403。
 * 机构只从令牌里取，别的机构的单子一律当作不存在。
 */
public final class TradeAftersaleSubjects {

    static final String CUSTOMER = "CUSTOMER";
    static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";

    private TradeAftersaleSubjects() {
    }

    public record Subject(UUID subjectId, Set<String> roles, UUID organizationId) {
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

    /** 客户账号。 */
    public static UUID customer(Authentication authentication) {
        Subject subject = of(authentication);
        if (!subject.roles().contains(CUSTOMER)) {
            throw new AccessDeniedException("customer role is required");
        }
        return subject.subjectId();
    }

    /** 机构操作员，并且令牌里有机构。 */
    public static Subject providerOperator(Authentication authentication) {
        Subject subject = of(authentication);
        if (!subject.roles().contains(PROVIDER_OPERATOR) || subject.organizationId() == null) {
            throw new AccessDeniedException("provider operator of an organization is required");
        }
        return subject;
    }
}
