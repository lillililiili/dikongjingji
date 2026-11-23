package com.lowaltitude.inspection.checkin.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 核销工作台（S3-3，契约 contracts/openapi-checkin-workbench.yaml 1.0.0）。都要登录，要服务商操作员角色和令牌里的机构；
 * 操作员和机构只从令牌里取，记录只有操作员本人能读、能处理。
 */
@RestController
public final class CheckinWorkbenchController {

    private static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";

    private final CheckinWorkbench workbench;

    public CheckinWorkbenchController(CheckinWorkbench workbench) {
        this.workbench = workbench;
    }

    @GetMapping("/api/v1/checkin-workbench")
    public CheckinWorkbenchView workbench(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        Operator operator = operator(authentication);
        return workbench.workbench(operator.organizationId(), date);
    }

    @GetMapping("/api/v1/checkin-workbench/records")
    public CheckinRecordListView records(
            Authentication authentication,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date
    ) {
        Operator operator = operator(authentication);
        return workbench.records(operator.subjectId(), operator.organizationId(), date);
    }

    @GetMapping("/api/v1/checkin-workbench/records/{kind}/{recordId}")
    public CheckinRecordView record(
            Authentication authentication,
            @PathVariable CheckinKind kind,
            @PathVariable UUID recordId
    ) {
        Operator operator = operator(authentication);
        return workbench.record(operator.subjectId(), operator.organizationId(), kind, recordId);
    }

    @PostMapping("/api/v1/checkin-workbench/verifications")
    public CheckinRecordView verify(Authentication authentication, @RequestBody VerifyRequest request) {
        Operator operator = operator(authentication);
        return workbench.verify(
                operator.subjectId(),
                operator.organizationId(),
                request.code(),
                request.method(),
                request.sessionKind(),
                request.sessionId());
    }

    @PostMapping("/api/v1/checkin-workbench/records/{kind}/{recordId}/resolution")
    public CheckinRecordView resolve(
            Authentication authentication,
            @PathVariable CheckinKind kind,
            @PathVariable UUID recordId,
            @RequestBody ResolveRequest request
    ) {
        Operator operator = operator(authentication);
        return workbench.resolve(
                operator.subjectId(),
                operator.organizationId(),
                kind,
                recordId,
                request.action(),
                Boolean.TRUE.equals(request.identityChecked()),
                request.note());
    }

    private static Operator operator(Authentication authentication) {
        Object principal = authentication == null ? null : authentication.getPrincipal();
        UUID subjectId;
        Set<String> roles;
        UUID organizationId;
        if (principal instanceof CurrentSubject current) {
            subjectId = current.subjectId();
            roles = current.roles();
            organizationId = current.organizationId();
        } else if (principal instanceof Jwt jwt) {
            try {
                List<String> claimed = jwt.getClaimAsStringList("roles");
                String organization = jwt.getClaimAsString("organizationId");
                subjectId = UUID.fromString(jwt.getClaimAsString("subjectId"));
                roles = claimed == null ? Set.of() : Set.copyOf(claimed);
                organizationId = organization == null || organization.isBlank() ? null : UUID.fromString(organization);
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        } else {
            throw new AccessDeniedException("authenticated subject is invalid");
        }
        if (!roles.contains(PROVIDER_OPERATOR) || organizationId == null) {
            throw new AccessDeniedException("check-in needs a provider operator of an organization");
        }
        return new Operator(subjectId, organizationId);
    }

    private record Operator(UUID subjectId, UUID organizationId) {
    }

    record VerifyRequest(String code, CheckinMethod method, CheckinKind sessionKind, UUID sessionId) {
    }

    record ResolveRequest(CheckinAction action, Boolean identityChecked, String note) {
    }
}
