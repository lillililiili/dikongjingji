package com.lowaltitude.inspection.proservice.application;

import java.util.UUID;

import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.proservice.application.ProviderProfileService.OpsDetailView;
import com.lowaltitude.inspection.proservice.application.ProviderProfileService.OpsPageView;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“服务商资料审核”（S4-4，契约 openapi-ops-proservice.yaml 1.1.0）。只给平台运营；审核带版本号，记改动记录。
 */
@RestController
public final class OpsProviderProfileController {

    private static final String PROFILES = "/api/v1/ops/provider-profiles";
    private static final String PROFILE = PROFILES + "/{providerId}";

    private final ProviderProfileService service;
    private final OpsActors actors;

    public OpsProviderProfileController(ProviderProfileService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(PROFILES)
    public OpsPageView profiles(
            Authentication authentication,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.list(status, page, size);
    }

    @GetMapping(PROFILE)
    public OpsDetailView profile(Authentication authentication, @PathVariable UUID providerId) {
        actors.operator(authentication);
        return service.detail(providerId);
    }

    @PostMapping(PROFILE + "/review")
    public OpsDetailView review(
            Authentication authentication,
            @PathVariable UUID providerId,
            @RequestBody ReviewRequest request
    ) {
        OpsActor actor = actors.operator(authentication);
        return service.review(providerId, request.decision(), request.reason(), request.version(), actor.accountId(),
                actor.displayName());
    }

    /** decision：APPROVE 或 REJECT；驳回时 reason 必填。 */
    record ReviewRequest(String decision, String reason, Integer version) {
    }
}
