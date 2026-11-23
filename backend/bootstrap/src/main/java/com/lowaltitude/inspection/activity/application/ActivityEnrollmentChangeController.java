package com.lowaltitude.inspection.activity.application;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * 团队报名、改期和退改详情（S5-3，契约 contracts/openapi-activity.yaml 1.4.0）。都要登录、要客户角色；
 * 账号只从令牌里取，不是自己的报名单一律当作不存在。取消时的申请原因加在原来的取消接口上
 * （{@link ActivityEnrollmentController}）。
 */
@RestController
public final class ActivityEnrollmentChangeController {

    private final ActivityEnrollmentService service;

    public ActivityEnrollmentChangeController(ActivityEnrollmentService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/activity-team-enrollments/check")
    public TeamCheckView checkTeam(Authentication authentication, @RequestBody TeamCheckRequest request) {
        return service.checkTeam(
                ActivitySubjects.customer(authentication), request.sessionId(), members(request.members()));
    }

    @PostMapping("/api/v1/activity-team-enrollments")
    public ResponseEntity<EnrollmentView> enrollTeam(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody TeamEnrollRequest request
    ) {
        EnrollmentResult result = service.enrollTeam(
                ActivitySubjects.customer(authentication),
                idempotencyKey,
                new TeamEnrollCommand(
                        request.activityId(),
                        request.sessionId(),
                        request.teamName(),
                        request.leaderName(),
                        request.leaderPhone(),
                        members(request.members()),
                        Boolean.TRUE.equals(request.noticeAgreed()),
                        Boolean.TRUE.equals(request.guardianConsent()),
                        request.expectedAmountMinor()));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.enrollment());
    }

    @PostMapping("/api/v1/activity-enrollments/{enrollmentId}/reschedule")
    public EnrollmentView reschedule(
            Authentication authentication,
            @PathVariable UUID enrollmentId,
            @RequestBody RescheduleRequest request
    ) {
        return service.reschedule(
                ActivitySubjects.customer(authentication), enrollmentId, request.targetSessionId(), request.reason());
    }

    @GetMapping("/api/v1/activity-enrollments/{enrollmentId}/changes/{changeId}")
    public ChangeDetailView change(
            Authentication authentication,
            @PathVariable UUID enrollmentId,
            @PathVariable UUID changeId
    ) {
        return service.readChange(ActivitySubjects.customer(authentication), enrollmentId, changeId);
    }

    private static List<TeamMemberInput> members(List<TeamMemberRequest> members) {
        if (members == null) {
            return null;
        }
        return members.stream()
                .map(member -> member == null
                        ? null
                        : new TeamMemberInput(member.participantId(), member.name(), member.birthMonth()))
                .toList();
    }

    record TeamMemberRequest(UUID participantId, String name, String birthMonth) {
    }

    record TeamCheckRequest(UUID sessionId, List<TeamMemberRequest> members) {
    }

    record TeamEnrollRequest(
            UUID activityId,
            UUID sessionId,
            String teamName,
            String leaderName,
            String leaderPhone,
            List<TeamMemberRequest> members,
            Boolean noticeAgreed,
            Boolean guardianConsent,
            Long expectedAmountMinor
    ) {
    }

    record RescheduleRequest(UUID targetSessionId, String reason) {
    }
}
