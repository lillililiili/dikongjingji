package com.lowaltitude.inspection.activity.application;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 机构工作台的“活动结果”（S5-3，契约 contracts/openapi-activity.yaml 1.4.0）：要服务商操作员角色和令牌里的机构，
 * 只能看、只能录本机构主办或承办的活动。
 */
@RestController
public final class ActivityResultWorkbenchController {

    private final ActivityResultService service;

    public ActivityResultWorkbenchController(ActivityResultService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/provider/activity-sessions")
    public ResultSessionPageView sessions(
            Authentication authentication,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.sessions(ActivitySubjects.operator(authentication).organizationId(), page, size);
    }

    @GetMapping("/api/v1/provider/activity-sessions/{sessionId}/roster")
    public ResultRosterView roster(Authentication authentication, @PathVariable UUID sessionId) {
        return service.roster(ActivitySubjects.operator(authentication).organizationId(), sessionId);
    }

    @PutMapping("/api/v1/provider/activity-tickets/{ticketId}/result")
    public ResultRosterEntryView record(
            Authentication authentication,
            @PathVariable UUID ticketId,
            @RequestBody RecordResultRequest request
    ) {
        ActivitySubjects.Subject operator = ActivitySubjects.operator(authentication);
        return service.record(
                operator.subjectId(),
                operator.organizationId(),
                ticketId,
                new RecordResultCommand(
                        request.completion(), request.scoreText(), request.certificateNote(), request.expectedVersion()));
    }

    record RecordResultRequest(String completion, String scoreText, String certificateNote, Integer expectedVersion) {
    }
}
