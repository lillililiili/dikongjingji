package com.lowaltitude.inspection.activity.application;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ActivityEnrollmentException.Error;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import com.lowaltitude.inspection.identity.domain.IdentityProfile;
import com.lowaltitude.inspection.identity.domain.IdentityProfilePort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 参与人、报名和核销（契约 contracts/openapi-activity.yaml 1.2.0；1.2.0 加了收费报名）。都要登录。
 * 账号只从令牌里取，没有任何指定别人的参数；不是自己的参与人、报名单一律当作不存在。
 * 参与人和报名要客户角色；核销要服务商操作员角色，并且只能核销本机构主办或承办的活动。
 */
@RestController
public final class ActivityEnrollmentController {

    private static final String CUSTOMER = "CUSTOMER";
    private static final String PROVIDER_OPERATOR = "PROVIDER_OPERATOR";

    private final ActivityEnrollmentService service;
    private final IdentityProfilePort profiles;

    public ActivityEnrollmentController(
            ActivityEnrollmentService service,
            IdentityProfilePort profiles
    ) {
        this.service = service;
        this.profiles = profiles;
    }

    @GetMapping("/api/v1/activity-participants")
    public ParticipantListView participants(
            Authentication authentication,
            @RequestParam(required = false) UUID sessionId
    ) {
        UUID accountId = customer(authentication);
        // “本人”第一次出现时用账号显示名；读不到资料就由服务给一个默认称呼。
        String accountName = profiles.findProfile(accountId)
                .map(IdentityProfile::displayName)
                .orElse(null);
        return service.listParticipants(accountId, accountName, sessionId);
    }

    @PostMapping("/api/v1/activity-participants")
    public ResponseEntity<ParticipantView> addChild(
            Authentication authentication,
            @RequestBody AddChildRequest request
    ) {
        ParticipantView created = service.addChild(
                customer(authentication),
                request.name(),
                request.birthMonth(),
                Boolean.TRUE.equals(request.guardianConfirmed()));
        return ResponseEntity.status(201).body(created);
    }

    // 改名用 PUT：uni-app 的请求方法在小程序上没有 PATCH。
    @PutMapping("/api/v1/activity-participants/{participantId}")
    public ParticipantView rename(
            Authentication authentication,
            @PathVariable UUID participantId,
            @RequestBody RenameParticipantRequest request
    ) {
        return service.renameParticipant(customer(authentication), participantId, request.name());
    }

    @PutMapping("/api/v1/activity-participants/{participantId}/photo-use")
    public ParticipantView setPhotoUse(
            Authentication authentication,
            @PathVariable UUID participantId,
            @RequestBody PhotoUseRequest request
    ) {
        if (request.authorized() == null) {
            throw new IllegalArgumentException("authorized is required");
        }
        return service.setPhotoUse(customer(authentication), participantId, request.authorized());
    }

    @DeleteMapping("/api/v1/activity-participants/{participantId}")
    public ResponseEntity<Void> deleteChild(
            Authentication authentication,
            @PathVariable UUID participantId
    ) {
        service.deleteChild(customer(authentication), participantId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/v1/activity-enrollments")
    public ResponseEntity<EnrollmentView> enroll(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody EnrollRequest request
    ) {
        EnrollmentResult result = service.enroll(
                customer(authentication),
                idempotencyKey,
                new EnrollCommand(
                        request.activityId(),
                        request.sessionId(),
                        request.participantIds(),
                        Boolean.TRUE.equals(request.noticeAgreed()),
                        request.expectedAmountMinor()));
        // 第一次创建返回 201；同一个幂等键重放返回 200 和同一张报名单。
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.enrollment());
    }

    @GetMapping("/api/v1/activity-enrollments")
    public EnrollmentPageView enrollments(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listEnrollments(customer(authentication), status, page, size);
    }

    @GetMapping("/api/v1/activity-enrollments/{enrollmentId}")
    public EnrollmentView enrollment(
            Authentication authentication,
            @PathVariable UUID enrollmentId
    ) {
        return service.readEnrollment(customer(authentication), enrollmentId);
    }

    /** 待付款的整张取消（关交易订单，名额在关闭后释放）；已付款的按凭证取消并原路退款。 */
    @PostMapping("/api/v1/activity-enrollments/{enrollmentId}/cancel")
    public EnrollmentView cancel(
            Authentication authentication,
            @PathVariable UUID enrollmentId,
            @RequestBody(required = false) CancelEnrollmentRequest request
    ) {
        return service.cancel(
                customer(authentication),
                enrollmentId,
                request == null ? null : request.ticketIds());
    }

    @PostMapping("/api/v1/activity-checkins")
    public CheckinView checkIn(
            Authentication authentication,
            @RequestBody CheckinRequest request
    ) {
        Subject subject = subject(authentication);
        if (!subject.roles().contains(PROVIDER_OPERATOR) || subject.organizationId() == null) {
            throw new ActivityEnrollmentException(Error.FORBIDDEN);
        }
        return service.checkIn(subject.subjectId(), subject.organizationId(), request.ticketCode());
    }

    private static UUID customer(Authentication authentication) {
        Subject subject = subject(authentication);
        if (!subject.roles().contains(CUSTOMER)) {
            throw new ActivityEnrollmentException(Error.FORBIDDEN);
        }
        return subject.subjectId();
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
                        organization == null || organization.isBlank()
                                ? null
                                : UUID.fromString(organization));
            } catch (RuntimeException invalidClaim) {
                throw new AccessDeniedException("authenticated subject is invalid", invalidClaim);
            }
        }
        throw new AccessDeniedException("authenticated subject is invalid");
    }

    private record Subject(UUID subjectId, Set<String> roles, UUID organizationId) {
    }

    record AddChildRequest(String name, String birthMonth, Boolean guardianConfirmed) {
    }

    record RenameParticipantRequest(String name) {
    }

    record PhotoUseRequest(Boolean authorized) {
    }

    /** expectedAmountMinor：页面看到的应付金额（分），收费活动必填。 */
    record EnrollRequest(
            UUID activityId,
            UUID sessionId,
            List<UUID> participantIds,
            Boolean noticeAgreed,
            Long expectedAmountMinor
    ) {
    }

    record CancelEnrollmentRequest(List<UUID> ticketIds) {
    }

    record CheckinRequest(String ticketCode) {
    }
}
