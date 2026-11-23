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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 候补（S5-3，契约 contracts/openapi-activity.yaml 1.4.0）。都要登录、要客户角色；
 * 账号只从令牌里取，不是自己的候补一律当作不存在。
 */
@RestController
public final class ActivityWaitlistController {

    private final ActivityWaitlistService service;

    public ActivityWaitlistController(ActivityWaitlistService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/activity-waitlists")
    public ResponseEntity<WaitlistView> join(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody JoinWaitlistRequest request
    ) {
        WaitlistResult result = service.join(
                ActivitySubjects.customer(authentication),
                idempotencyKey,
                new JoinWaitlistCommand(
                        request.activityId(),
                        request.sessionId(),
                        request.participantIds(),
                        Boolean.TRUE.equals(request.noticeAgreed())));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.waitlist());
    }

    @GetMapping("/api/v1/activity-waitlists")
    public WaitlistPageView list(
            Authentication authentication,
            @RequestParam(defaultValue = "false") boolean active,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.list(ActivitySubjects.customer(authentication), active, page, size);
    }

    @GetMapping("/api/v1/activity-waitlists/{waitlistId}")
    public WaitlistView read(Authentication authentication, @PathVariable UUID waitlistId) {
        return service.read(ActivitySubjects.customer(authentication), waitlistId);
    }

    @PostMapping("/api/v1/activity-waitlists/{waitlistId}/abandon")
    public WaitlistView abandon(Authentication authentication, @PathVariable UUID waitlistId) {
        return service.abandon(ActivitySubjects.customer(authentication), waitlistId);
    }

    @PostMapping("/api/v1/activity-waitlists/{waitlistId}/confirm")
    public WaitlistView confirm(
            Authentication authentication,
            @PathVariable UUID waitlistId,
            @RequestBody(required = false) ConfirmWaitlistRequest request
    ) {
        return service.confirm(
                ActivitySubjects.customer(authentication),
                waitlistId,
                request == null ? null : request.expectedAmountMinor());
    }

    record JoinWaitlistRequest(UUID activityId, UUID sessionId, List<UUID> participantIds, Boolean noticeAgreed) {
    }

    record ConfirmWaitlistRequest(Long expectedAmountMinor) {
    }
}
