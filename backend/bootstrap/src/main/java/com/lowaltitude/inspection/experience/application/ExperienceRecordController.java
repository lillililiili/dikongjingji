package com.lowaltitude.inspection.experience.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ExperienceRecordException.Error;
import com.lowaltitude.inspection.experience.application.ExperienceRecordService.RecordResult;
import com.lowaltitude.inspection.experience.application.ExperienceRecordService.SimulationResultCommand;
import com.lowaltitude.inspection.identity.domain.CurrentSubject;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 线上体验记录（S5-4，契约 contracts/openapi-experience.yaml 1.4.0）。都要登录并且有客户角色。
 * 账号只从令牌里取，没有任何指定别人的参数；不是自己的记录一律当作不存在。
 * 没登录的人照样能玩仿真，只是不记录：App 把成绩存在本机，登录后再用同一个幂等键补交。
 */
@RestController
public final class ExperienceRecordController {

    private static final String CUSTOMER = "CUSTOMER";

    private final ExperienceRecordService service;

    public ExperienceRecordController(ExperienceRecordService service) {
        this.service = service;
    }

    /** 交一次仿真成绩。第一次 201；同一个幂等键重放 200 和同一条记录。 */
    @PostMapping("/api/v1/experience/records")
    public ResponseEntity<ExperienceRecordView> recordSimulation(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody SimulationResultRequest request
    ) {
        RecordResult result = service.recordSimulation(customer(authentication), idempotencyKey,
                new SimulationResultCommand(
                        request.experienceId(),
                        request.startedAt(),
                        request.endedAt(),
                        request.durationSeconds(),
                        request.levelsCompleted(),
                        request.levelsTotal(),
                        request.mistakes()));
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.record());
    }

    @GetMapping("/api/v1/experience/records")
    public ExperienceRecordPageView records(
            Authentication authentication,
            @RequestParam(required = false) UUID experienceId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        return service.listRecords(customer(authentication), experienceId, page, size);
    }

    @GetMapping("/api/v1/experience/records/{recordId}")
    public ExperienceRecordView record(Authentication authentication, @PathVariable UUID recordId) {
        return service.readRecord(customer(authentication), recordId);
    }

    /** 开始一次软件试用或课程试听。新开始 201；还在试用中的那一条 200。 */
    @PostMapping("/api/v1/experience/trials")
    public ResponseEntity<ExperienceRecordView> startTrial(
            Authentication authentication,
            @RequestBody TrialStartRequest request
    ) {
        RecordResult result = service.startTrial(customer(authentication), request.experienceId());
        return ResponseEntity.status(result.created() ? 201 : 200).body(result.record());
    }

    /** 提前结束试用；已结束、已到期的原样返回。 */
    @PostMapping("/api/v1/experience/records/{recordId}/end")
    public ExperienceRecordView endTrial(Authentication authentication, @PathVariable UUID recordId) {
        return service.endTrial(customer(authentication), recordId);
    }

    // ---- 工具 ----

    private static UUID customer(Authentication authentication) {
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
            throw new ExperienceRecordException(Error.FORBIDDEN);
        }
        return subjectId;
    }

    /**
     * 一次仿真的成绩。分数由服务端按关卡和失误算，不收 App 算的分。
     *
     * @param durationSeconds 在任务页面上的用时（秒），不超过起止时间
     */
    record SimulationResultRequest(
            UUID experienceId,
            OffsetDateTime startedAt,
            OffsetDateTime endedAt,
            Integer durationSeconds,
            Integer levelsCompleted,
            Integer levelsTotal,
            Integer mistakes
    ) {
    }

    record TrialStartRequest(UUID experienceId) {
    }
}
