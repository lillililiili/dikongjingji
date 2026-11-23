package com.lowaltitude.inspection.activity.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.activity.application.ops.ActivityOpsService;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.ActivityDraft;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsActivityDeletedView;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsActivityPageView;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.OpsActivityView;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.Operator;
import com.lowaltitude.inspection.activity.application.ops.ActivityOpsViews.SessionDraft;
import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“活动与场次”（S3-1，契约 contracts/openapi-ops-activity.yaml 1.0.0）。只给平台运营；写操作带版本号，
 * 每次改动记一条改动记录（操作人显示名取自身份资料）。
 */
@RestController
public final class OpsActivityController {

    private static final String ACTIVITIES = "/api/v1/ops/activities";
    private static final String ACTIVITY = ACTIVITIES + "/{activityId}";
    private static final String SESSION = ACTIVITY + "/sessions/{sessionId}";

    private final ActivityOpsService service;
    private final OpsActors actors;

    public OpsActivityController(ActivityOpsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(ACTIVITIES)
    public OpsActivityPageView activities(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.activities(keyword, cityCode, type, status, page, size);
    }

    @PostMapping(ACTIVITIES)
    public ResponseEntity<OpsActivityView> create(Authentication authentication,
                                                  @RequestBody ActivityRequest request) {
        Operator operator = operator(authentication);
        return ResponseEntity.status(201).body(service.create(request.draft(), operator));
    }

    @GetMapping(ACTIVITY)
    public OpsActivityView activity(Authentication authentication, @PathVariable UUID activityId) {
        actors.operator(authentication);
        return service.activity(activityId);
    }

    @PutMapping(ACTIVITY)
    public OpsActivityView update(Authentication authentication, @PathVariable UUID activityId,
                                  @RequestBody ActivityRequest request) {
        Operator operator = operator(authentication);
        return service.update(activityId, request.draft(), request.version(), operator);
    }

    @DeleteMapping(ACTIVITY)
    public OpsActivityDeletedView delete(Authentication authentication, @PathVariable UUID activityId,
                                         @RequestParam Integer version) {
        Operator operator = operator(authentication);
        service.delete(activityId, version, operator);
        return new OpsActivityDeletedView(activityId, true);
    }

    @PostMapping(ACTIVITY + "/publish")
    public OpsActivityView publish(Authentication authentication, @PathVariable UUID activityId,
                                   @RequestBody VersionRequest request) {
        Operator operator = operator(authentication);
        return service.publish(activityId, request.version(), operator);
    }

    @PostMapping(ACTIVITY + "/offline")
    public OpsActivityView offline(Authentication authentication, @PathVariable UUID activityId,
                                   @RequestBody VersionRequest request) {
        Operator operator = operator(authentication);
        return service.offline(activityId, request.version(), operator);
    }

    @GetMapping(ACTIVITY + "/changes")
    public List<OpsChangeView> changes(Authentication authentication, @PathVariable UUID activityId) {
        actors.operator(authentication);
        return service.changes(activityId);
    }

    @PostMapping(ACTIVITY + "/sessions")
    public ResponseEntity<OpsActivityView> addSession(Authentication authentication, @PathVariable UUID activityId,
                                                      @RequestBody SessionRequest request) {
        Operator operator = operator(authentication);
        return ResponseEntity.status(201).body(service.addSession(activityId, request.draft(), operator));
    }

    @PutMapping(SESSION)
    public OpsActivityView updateSession(Authentication authentication, @PathVariable UUID activityId,
                                         @PathVariable UUID sessionId, @RequestBody SessionRequest request) {
        Operator operator = operator(authentication);
        return service.updateSession(activityId, sessionId, request.draft(), request.version(), operator);
    }

    @DeleteMapping(SESSION)
    public OpsActivityView deleteSession(Authentication authentication, @PathVariable UUID activityId,
                                         @PathVariable UUID sessionId, @RequestParam Integer version) {
        Operator operator = operator(authentication);
        return service.deleteSession(activityId, sessionId, version, operator);
    }

    @PostMapping(SESSION + "/stop-sale")
    public OpsActivityView stopSale(Authentication authentication, @PathVariable UUID activityId,
                                    @PathVariable UUID sessionId, @RequestBody VersionRequest request) {
        Operator operator = operator(authentication);
        return service.stopSale(activityId, sessionId, request.version(), operator);
    }

    @PostMapping(SESSION + "/resume-sale")
    public OpsActivityView resumeSale(Authentication authentication, @PathVariable UUID activityId,
                                      @PathVariable UUID sessionId, @RequestBody VersionRequest request) {
        Operator operator = operator(authentication);
        return service.resumeSale(activityId, sessionId, request.version(), operator);
    }

    private Operator operator(Authentication authentication) {
        OpsActor actor = actors.operator(authentication);
        return new Operator(actor.accountId(), actor.displayName());
    }

    /** 新建、修改活动共用；version 新建时不用带，修改时必填（页面读到的版本号）。 */
    record ActivityRequest(
            String type,
            String title,
            String subtitle,
            String coverUrl,
            List<String> galleryUrls,
            UUID organizerId,
            UUID coOrganizerId,
            String cityCode,
            String venueName,
            String venueAddress,
            String scheduleNote,
            String priceMode,
            Long priceAmountMinor,
            Integer minAge,
            Integer maxAge,
            String eligibilityNote,
            String seatNote,
            String refundNote,
            String enrollmentNotice,
            Integer version
    ) {

        ActivityDraft draft() {
            return new ActivityDraft(type, title, subtitle, coverUrl, galleryUrls, organizerId, coOrganizerId,
                    cityCode, venueName, venueAddress, scheduleNote, priceMode, priceAmountMinor, minAge, maxAge,
                    eligibilityNote, seatNote, refundNote, enrollmentNotice);
        }
    }

    /** 新增、修改场次共用；version 新增时不用带，修改时必填。 */
    record SessionRequest(
            String label,
            OffsetDateTime startsAt,
            OffsetDateTime endsAt,
            OffsetDateTime checkInOpensAt,
            OffsetDateTime enrollmentClosesAt,
            Integer capacity,
            Integer version
    ) {

        SessionDraft draft() {
            return new SessionDraft(label, startsAt, endsAt, checkInOpensAt, enrollmentClosesAt, capacity);
        }
    }

    /** 上线、下线、停售、恢复售卖：只带页面读到的版本号。 */
    record VersionRequest(Integer version) {
    }
}
