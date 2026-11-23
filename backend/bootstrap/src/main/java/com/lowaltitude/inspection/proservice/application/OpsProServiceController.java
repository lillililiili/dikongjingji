package com.lowaltitude.inspection.proservice.application;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import com.lowaltitude.inspection.proservice.application.ProServiceOpsService.DetailView;
import com.lowaltitude.inspection.proservice.application.ProServiceOpsService.LineDraft;
import com.lowaltitude.inspection.proservice.application.ProServiceOpsService.PageView;
import com.lowaltitude.inspection.proservice.application.ProServiceOpsService.ServiceDraft;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“专业服务目录”（S3-1，契约 contracts/openapi-ops-proservice.yaml 1.0.0）。只给平台运营；写操作带版本号，
 * 每次改动记一条改动记录。
 */
@RestController
public final class OpsProServiceController {

    private static final String SERVICES = "/api/v1/ops/pro-services";
    private static final String SERVICE = SERVICES + "/{serviceId}";

    private final ProServiceOpsService service;
    private final OpsActors actors;

    public OpsProServiceController(ProServiceOpsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(SERVICES)
    public PageView services(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.services(keyword, type, cityCode, status, page, size);
    }

    @PostMapping(SERVICES)
    public ResponseEntity<DetailView> create(Authentication authentication, @RequestBody ServiceRequest request) {
        OpsActor actor = actors.operator(authentication);
        return ResponseEntity.status(201).body(service.create(request.draft(), actor.accountId(), actor.displayName()));
    }

    @GetMapping(SERVICE)
    public DetailView service(Authentication authentication, @PathVariable UUID serviceId) {
        actors.operator(authentication);
        return service.service(serviceId);
    }

    @PutMapping(SERVICE)
    public DetailView update(Authentication authentication, @PathVariable UUID serviceId,
                             @RequestBody ServiceRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.update(serviceId, request.draft(), request.version(), actor.accountId(), actor.displayName());
    }

    @PostMapping(SERVICE + "/publish")
    public DetailView publish(Authentication authentication, @PathVariable UUID serviceId,
                              @RequestBody VersionRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.publish(serviceId, request.version(), actor.accountId(), actor.displayName());
    }

    @PostMapping(SERVICE + "/offline")
    public DetailView offline(Authentication authentication, @PathVariable UUID serviceId,
                              @RequestBody VersionRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.offline(serviceId, request.version(), actor.accountId(), actor.displayName());
    }

    @GetMapping(SERVICE + "/changes")
    public List<OpsChangeView> changes(Authentication authentication, @PathVariable UUID serviceId) {
        actors.operator(authentication);
        return service.changes(serviceId);
    }

    record VersionRequest(Integer version) {
    }

    record LineRequest(String label, String detail) {
    }

    /** 新建和修改共用；修改时 version 必填（新建时不看）。 */
    record ServiceRequest(
            UUID providerId,
            String type,
            String categoryCode,
            String title,
            String summary,
            String priceMode,
            Long priceAmountMinor,
            String priceUnit,
            String priceNote,
            String coverUrl,
            Integer sortOrder,
            List<String> cityCodes,
            List<String> tags,
            List<String> facets,
            List<String> capabilities,
            List<LineRequest> priceRows,
            List<String> scopes,
            List<LineRequest> outputs,
            List<LineRequest> serviceModes,
            List<LineRequest> terms,
            List<String> steps,
            List<String> guarantees,
            Integer version,
            String warrantyText
    ) {

        ServiceDraft draft() {
            return new ServiceDraft(providerId, type, categoryCode, title, summary, priceMode, priceAmountMinor,
                    priceUnit, priceNote, coverUrl, sortOrder, cityCodes, tags, facets, capabilities,
                    lines(priceRows), scopes, lines(outputs), lines(serviceModes), lines(terms), steps, guarantees,
                    warrantyText);
        }

        private static List<LineDraft> lines(List<LineRequest> requests) {
            return requests == null ? null : requests.stream()
                    .map(request -> request == null ? null : new LineDraft(request.label(), request.detail()))
                    .toList();
        }
    }
}
