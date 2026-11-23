package com.lowaltitude.inspection.experience.application;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.experience.application.ops.VenueOpsService;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.DeviceDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.InfoItemDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueDetailView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenuePageView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueSessionView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.OpsVenueSessionsGeneratedView;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.ProjectDraft;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.SessionPlan;
import com.lowaltitude.inspection.experience.application.ops.VenueOpsViews.VenueDraft;
import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“场馆与设备”（S3-1，契约 contracts/openapi-ops-venue.yaml 1.0.0）：只给平台运营。场馆、项目、设备的改动带
 * 页面读到的版本号；新建带幂等键（第一次 201，同一个键再提交 200 返回第一次建的）；每个写操作都记一条改动记录。
 */
@RestController
public final class OpsVenueController {

    private static final String VENUES = "/api/v1/ops/venues";
    private static final String VENUE = VENUES + "/{venueId}";

    private final VenueOpsService service;
    private final OpsActors actors;

    public OpsVenueController(VenueOpsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    // ---- 场馆 ----

    @GetMapping(VENUES)
    public OpsVenuePageView venues(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.venues(keyword, cityCode, status, page, size);
    }

    @PostMapping(VENUES)
    public ResponseEntity<OpsVenueDetailView> create(
            Authentication authentication,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody VenueRequest request
    ) {
        VenueOpsService.Created created = service.createVenue(idempotencyKey, request.draft(),
                operator(authentication));
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.venue());
    }

    @GetMapping(VENUE)
    public OpsVenueDetailView venue(Authentication authentication, @PathVariable UUID venueId) {
        actors.operator(authentication);
        return service.venue(venueId);
    }

    @PutMapping(VENUE)
    public OpsVenueDetailView update(Authentication authentication, @PathVariable UUID venueId,
                                     @RequestBody VenueRequest request) {
        return service.updateVenue(venueId, request.draft(), request.version(), operator(authentication));
    }

    @PostMapping(VENUE + "/publish")
    public OpsVenueDetailView publish(Authentication authentication, @PathVariable UUID venueId,
                                      @RequestBody TransitionRequest request) {
        return service.publish(venueId, request.version(), request.note(), operator(authentication));
    }

    @PostMapping(VENUE + "/offline")
    public OpsVenueDetailView offline(Authentication authentication, @PathVariable UUID venueId,
                                      @RequestBody TransitionRequest request) {
        return service.offline(venueId, request.version(), request.note(), operator(authentication));
    }

    @GetMapping(VENUE + "/changes")
    public List<OpsChangeView> changes(Authentication authentication, @PathVariable UUID venueId) {
        actors.operator(authentication);
        return service.changes(venueId);
    }

    // ---- 项目与场次 ----

    @PostMapping(VENUE + "/projects")
    public ResponseEntity<OpsVenueDetailView> createProject(
            Authentication authentication,
            @PathVariable UUID venueId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody ProjectRequest request
    ) {
        VenueOpsService.Created created = service.createProject(venueId, idempotencyKey, request.draft(),
                operator(authentication));
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.venue());
    }

    @PutMapping(VENUE + "/projects/{projectId}")
    public OpsVenueDetailView updateProject(Authentication authentication, @PathVariable UUID venueId,
                                            @PathVariable UUID projectId, @RequestBody ProjectRequest request) {
        return service.updateProject(venueId, projectId, request.draft(), request.version(),
                operator(authentication));
    }

    @PutMapping(VENUE + "/projects/{projectId}/status")
    public OpsVenueDetailView projectStatus(Authentication authentication, @PathVariable UUID venueId,
                                            @PathVariable UUID projectId, @RequestBody StatusRequest request) {
        return service.changeProjectStatus(venueId, projectId, request.status(), request.version(),
                operator(authentication));
    }

    @GetMapping(VENUE + "/projects/{projectId}/sessions")
    public List<OpsVenueSessionView> sessions(
            Authentication authentication,
            @PathVariable UUID venueId,
            @PathVariable UUID projectId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        actors.operator(authentication);
        return service.sessions(venueId, projectId, from, to);
    }

    @PostMapping(VENUE + "/projects/{projectId}/sessions/generate")
    public OpsVenueSessionsGeneratedView generate(Authentication authentication, @PathVariable UUID venueId,
                                                  @PathVariable UUID projectId,
                                                  @RequestBody GenerateSessionsRequest request) {
        return service.generateSessions(venueId, projectId, new SessionPlan(request.fromDate(), request.toDate(),
                request.weekdays(), request.startTimes(), request.capacity()), operator(authentication));
    }

    @PostMapping(VENUE + "/sessions/{sessionId}/stop-sale")
    public OpsVenueSessionView stopSale(Authentication authentication, @PathVariable UUID venueId,
                                        @PathVariable UUID sessionId, @RequestBody TransitionRequest request) {
        return service.stopSale(venueId, sessionId, request.version(), operator(authentication));
    }

    @PostMapping(VENUE + "/sessions/{sessionId}/resume-sale")
    public OpsVenueSessionView resumeSale(Authentication authentication, @PathVariable UUID venueId,
                                          @PathVariable UUID sessionId, @RequestBody TransitionRequest request) {
        return service.resumeSale(venueId, sessionId, request.version(), operator(authentication));
    }

    @PostMapping(VENUE + "/sessions/{sessionId}/cancel")
    public OpsVenueSessionView cancelSession(Authentication authentication, @PathVariable UUID venueId,
                                             @PathVariable UUID sessionId, @RequestBody TransitionRequest request) {
        return service.cancelSession(venueId, sessionId, request.version(), operator(authentication));
    }

    // ---- 馆内设备 ----

    @PostMapping(VENUE + "/devices")
    public ResponseEntity<OpsVenueDetailView> createDevice(
            Authentication authentication,
            @PathVariable UUID venueId,
            @RequestHeader("Idempotency-Key") UUID idempotencyKey,
            @RequestBody DeviceRequest request
    ) {
        VenueOpsService.Created created = service.createDevice(venueId, idempotencyKey, request.draft(),
                operator(authentication));
        return ResponseEntity.status(created.created() ? 201 : 200).body(created.venue());
    }

    @PutMapping(VENUE + "/devices/{deviceId}")
    public OpsVenueDetailView updateDevice(Authentication authentication, @PathVariable UUID venueId,
                                           @PathVariable UUID deviceId, @RequestBody DeviceRequest request) {
        return service.updateDevice(venueId, deviceId, request.draft(), request.version(), operator(authentication));
    }

    @PutMapping(VENUE + "/devices/{deviceId}/status")
    public OpsVenueDetailView deviceStatus(Authentication authentication, @PathVariable UUID venueId,
                                           @PathVariable UUID deviceId, @RequestBody StatusRequest request) {
        return service.changeDeviceStatus(venueId, deviceId, request.status(), request.version(),
                operator(authentication));
    }

    private VenueOpsService.Operator operator(Authentication authentication) {
        OpsActor actor = actors.operator(authentication);
        return new VenueOpsService.Operator(actor.accountId(), actor.displayName());
    }

    // ---- 请求体 ----

    /** 新建和修改共用；修改时 version 必填。 */
    record VenueRequest(UUID operatorId, String name, String cityCode, String coverUrl, String address, String phone,
                        String opensAt, String closesAt, String closedNote, Integer checkinOpensMinutes,
                        Integer lateEntryMinutes, Integer sortOrder, List<InfoItemRequest> infoItems,
                        Integer version) {

        VenueDraft draft() {
            return new VenueDraft(operatorId, name, cityCode, coverUrl, address, phone, opensAt, closesAt,
                    closedNote, checkinOpensMinutes, lateEntryMinutes, sortOrder, infoItems == null ? null
                    : infoItems.stream().map(item -> item == null ? null
                            : new InfoItemDraft(item.label(), item.content())).toList());
        }
    }

    record InfoItemRequest(String label, String content) {
    }

    record ProjectRequest(String name, Integer durationMinutes, Long priceAmountMinor, Integer minAge,
                          Integer capacity, String limitNote, Integer sortOrder, Integer version) {

        ProjectDraft draft() {
            return new ProjectDraft(name, durationMinutes, priceAmountMinor, minAge, capacity, limitNote, sortOrder);
        }
    }

    record DeviceRequest(String category, String name, String summary, String imageUrl, String trialMode,
                         UUID productId, Integer sortOrder, Integer version) {

        DeviceDraft draft() {
            return new DeviceDraft(category, name, summary, imageUrl, trialMode, productId, sortOrder);
        }
    }

    record StatusRequest(String status, Integer version) {
    }

    /** 上线、下线、停售、恢复售卖、取消场次：带版本号，说明选填（写进改动记录，场次操作不用说明）。 */
    record TransitionRequest(Integer version, String note) {
    }

    record GenerateSessionsRequest(String fromDate, String toDate, List<String> weekdays, List<String> startTimes,
                                   Integer capacity) {
    }
}
