package com.lowaltitude.inspection.application.city;

import java.util.List;

import com.lowaltitude.inspection.application.city.CityOperationsService.OpsCityPageView;
import com.lowaltitude.inspection.application.city.CityOperationsService.OpsCityStatusChangeView;
import com.lowaltitude.inspection.application.city.CityOperationsService.OpsCityView;
import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“城市开通”（S3-1，契约 contracts/openapi-ops-city.yaml 1.0.0）。只给平台运营；改状态要带页面读到的版本号，
 * 每次改动记一条（操作人、时间、说明），页面上能看到。
 */
@RestController
public final class OpsCityController {

    private static final String CITIES = "/api/v1/ops/cities";

    private final CityOperationsService service;
    private final OpsActors actors;

    public OpsCityController(CityOperationsService service, OpsActors actors) {
        this.service = service;
        this.actors = actors;
    }

    @GetMapping(CITIES)
    public OpsCityPageView cities(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String serviceStatus,
            @RequestParam(required = false) String provinceCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.cities(keyword, serviceStatus, provinceCode, page, size);
    }

    @PutMapping(CITIES + "/{cityCode}/service-status")
    public OpsCityView changeStatus(
            Authentication authentication,
            @PathVariable String cityCode,
            @RequestBody ChangeStatusRequest request
    ) {
        OpsActor actor = actors.operator(authentication);
        return service.changeStatus(cityCode, request.serviceStatus(), request.note(), request.version(),
                actor.accountId(), actor.displayName());
    }

    @GetMapping(CITIES + "/{cityCode}/service-status-changes")
    public List<OpsCityStatusChangeView> changes(Authentication authentication, @PathVariable String cityCode) {
        actors.operator(authentication);
        return service.changes(cityCode);
    }

    /**
     * @param serviceStatus OPEN / PREPARING / CLOSED
     * @param note          为什么改（1–200 字），写进改动记录
     * @param version       页面读到的版本号
     */
    record ChangeStatusRequest(String serviceStatus, String note, Integer version) {
    }
}
