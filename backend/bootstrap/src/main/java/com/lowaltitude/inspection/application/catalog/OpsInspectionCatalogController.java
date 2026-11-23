package com.lowaltitude.inspection.application.catalog;

import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.CategoryDraft;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.CategoryView;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ItemDraft;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ServiceDetailView;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ServiceDraft;
import com.lowaltitude.inspection.application.catalog.InspectionCatalogOpsService.ServicePageView;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“巡检服务目录”（S3-1，契约 contracts/openapi-ops-inspection-catalog.yaml 1.0.0）。只给平台运营；
 * 写操作带页面读到的版本号，每次改动记一条改动记录（操作人显示名、时间、改了什么）。
 */
@RestController
public final class OpsInspectionCatalogController {

    private static final String CATEGORIES = "/api/v1/ops/inspection/categories";
    private static final String CATEGORY = CATEGORIES + "/{categoryCode}";
    private static final String SERVICES = "/api/v1/ops/inspection/services";
    private static final String SERVICE = SERVICES + "/{serviceId}";

    private final InspectionCatalogOpsService service;
    private final OpsActors actors;

    public OpsInspectionCatalogController(InspectionCatalogOpsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    // ---- 类目 ----

    @GetMapping(CATEGORIES)
    public List<CategoryView> categories(Authentication authentication) {
        actors.operator(authentication);
        return service.categories();
    }

    @PostMapping(CATEGORIES)
    public ResponseEntity<CategoryView> createCategory(Authentication authentication,
                                                       @RequestBody CategoryCreateRequest request) {
        OpsActor actor = actors.operator(authentication);
        return ResponseEntity.status(201).body(service.createCategory(new CategoryDraft(request.code(),
                        request.name(), request.subtitle(), request.parentCode(), request.sortOrder()),
                actor.accountId(), actor.displayName()));
    }

    @PutMapping(CATEGORY)
    public CategoryView updateCategory(Authentication authentication, @PathVariable String categoryCode,
                                       @RequestBody CategoryUpdateRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.updateCategory(categoryCode, request.name(), request.subtitle(), request.sortOrder(),
                request.version(), actor.accountId(), actor.displayName());
    }

    @PutMapping(CATEGORY + "/status")
    public CategoryView changeCategoryStatus(Authentication authentication, @PathVariable String categoryCode,
                                             @RequestBody CategoryStatusRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.changeCategoryStatus(categoryCode, request.status(), request.version(), actor.accountId(),
                actor.displayName());
    }

    @GetMapping(CATEGORY + "/changes")
    public List<OpsChangeView> categoryChanges(Authentication authentication, @PathVariable String categoryCode) {
        actors.operator(authentication);
        return service.categoryChanges(categoryCode);
    }

    // ---- 服务 ----

    @GetMapping(SERVICES)
    public ServicePageView services(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryCode,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.services(keyword, categoryCode, cityCode, status, page, size);
    }

    @PostMapping(SERVICES)
    public ResponseEntity<ServiceDetailView> createService(Authentication authentication,
                                                           @RequestBody ServiceRequest request) {
        OpsActor actor = actors.operator(authentication);
        return ResponseEntity.status(201).body(service.createService(request.draft(), actor.accountId(),
                actor.displayName()));
    }

    @GetMapping(SERVICE)
    public ServiceDetailView service(Authentication authentication, @PathVariable UUID serviceId) {
        actors.operator(authentication);
        return service.service(serviceId);
    }

    @PutMapping(SERVICE)
    public ServiceDetailView updateService(Authentication authentication, @PathVariable UUID serviceId,
                                           @RequestBody ServiceRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.updateService(serviceId, request.draft(), request.version(), actor.accountId(),
                actor.displayName());
    }

    @PostMapping(SERVICE + "/publish")
    public ServiceDetailView publish(Authentication authentication, @PathVariable UUID serviceId,
                                     @RequestBody VersionRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.publish(serviceId, request.version(), actor.accountId(), actor.displayName());
    }

    @PostMapping(SERVICE + "/offline")
    public ServiceDetailView offline(Authentication authentication, @PathVariable UUID serviceId,
                                     @RequestBody VersionRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.offline(serviceId, request.version(), actor.accountId(), actor.displayName());
    }

    @GetMapping(SERVICE + "/changes")
    public List<OpsChangeView> serviceChanges(Authentication authentication, @PathVariable UUID serviceId) {
        actors.operator(authentication);
        return service.serviceChanges(serviceId);
    }

    record CategoryCreateRequest(String code, String name, String subtitle, String parentCode, Integer sortOrder) {
    }

    record CategoryUpdateRequest(String name, String subtitle, Integer sortOrder, Integer version) {
    }

    record CategoryStatusRequest(String status, Integer version) {
    }

    record VersionRequest(Integer version) {
    }

    record ItemRequest(String code, String title, String description, String iconKey) {

        ItemDraft draft() {
            return new ItemDraft(code, title, description, iconKey);
        }
    }

    /** 新建和修改共用；修改时 version 必填（新建时不看）。 */
    record ServiceRequest(
            UUID providerId,
            String title,
            String summary,
            String categoryCode,
            String coverUrl,
            String priceMode,
            Long startingAmountMinor,
            String unitCode,
            String unitLabel,
            String priceNote,
            String cancellationNote,
            String availability,
            String primaryAction,
            Boolean includesReport,
            List<String> serviceMethods,
            List<String> cityCodes,
            List<ItemRequest> bookingItems,
            List<ItemRequest> priceFactors,
            List<ItemRequest> capabilities,
            List<ItemRequest> processSteps,
            List<ItemRequest> deliverables,
            List<ItemRequest> guarantees,
            Integer version
    ) {

        ServiceDraft draft() {
            return new ServiceDraft(providerId, title, summary, categoryCode, coverUrl, priceMode,
                    startingAmountMinor, unitCode, unitLabel, priceNote, cancellationNote, availability,
                    primaryAction, includesReport, serviceMethods, cityCodes, items(bookingItems),
                    items(priceFactors), items(capabilities), items(processSteps), items(deliverables),
                    items(guarantees));
        }

        private static List<ItemDraft> items(List<ItemRequest> requests) {
            return requests == null ? null : requests.stream()
                    .map(request -> request == null ? null : request.draft())
                    .toList();
        }
    }
}
