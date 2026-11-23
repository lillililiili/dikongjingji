package com.lowaltitude.inspection.live.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.lowaltitude.inspection.identity.application.OrganizationOnboardingService;
import com.lowaltitude.inspection.identity.application.OrganizationOnboardingService.LiveHostOrganizationView;
import com.lowaltitude.inspection.live.application.LiveOpsService.HostRoomCounts;
import com.lowaltitude.inspection.live.application.LiveOpsService.OpsLiveRoomPageView;
import com.lowaltitude.inspection.live.application.LiveOpsService.OpsLiveRoomView;
import com.lowaltitude.inspection.ops.OpsActor;
import com.lowaltitude.inspection.ops.OpsActors;
import com.lowaltitude.inspection.ops.common.OpsChangeView;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运营端“直播间”（S3-1 第三部分，契约 contracts/openapi-ops-live.yaml 1.0.0）：只给平台运营。
 * 主办机构列表把身份模块（谁开通了直播主办）和直播模块（各自的直播间数）拼起来；开通、收回在“机构与资质”里给账号改角色。
 */
@RestController
public final class OpsLiveController {

    private static final String ROOMS = "/api/v1/ops/live/rooms";

    private final LiveOpsService service;
    private final OrganizationOnboardingService organizations;
    private final OpsActors actors;

    public OpsLiveController(LiveOpsService service, OrganizationOnboardingService organizations, OpsActors actors) {
        this.service = service;
        this.organizations = organizations;
        this.actors = actors;
    }

    @GetMapping("/api/v1/ops/live/hosts")
    public List<OpsLiveHostView> hosts(Authentication authentication) {
        actors.operator(authentication);
        List<LiveHostOrganizationView> hosts = organizations.liveHostOrganizations();
        Map<UUID, HostRoomCounts> counts = service.roomCounts(hosts.stream()
                .map(LiveHostOrganizationView::organizationId).toList());
        return hosts.stream().map(host -> {
            HostRoomCounts count = counts.getOrDefault(host.organizationId(), new HostRoomCounts(0, 0, 0));
            return new OpsLiveHostView(host.organizationId(), host.organizationName(), host.cityCode(),
                    host.hostAccountCount(), count.roomCount(), count.liveCount(), count.scheduledCount());
        }).toList();
    }

    @GetMapping(ROOMS)
    public OpsLiveRoomPageView rooms(
            Authentication authentication,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String cityCode,
            @RequestParam(required = false) UUID hostOrganizationId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        actors.operator(authentication);
        return service.rooms(keyword, status, cityCode, hostOrganizationId, page, size);
    }

    @GetMapping(ROOMS + "/{roomId}")
    public OpsLiveRoomView room(Authentication authentication, @PathVariable UUID roomId) {
        actors.operator(authentication);
        return service.room(roomId);
    }

    @PostMapping(ROOMS + "/{roomId}/end")
    public OpsLiveRoomView end(Authentication authentication, @PathVariable UUID roomId,
                               @RequestBody EndRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.forceEnd(roomId, request.note(), request.version(), actor.accountId(), actor.displayName());
    }

    @PutMapping(ROOMS + "/{roomId}/replay")
    public OpsLiveRoomView replay(Authentication authentication, @PathVariable UUID roomId,
                                  @RequestBody ReplayRequest request) {
        OpsActor actor = actors.operator(authentication);
        return service.setReplay(roomId, request.replayUrl(), request.replayPlatformName(), request.version(),
                actor.accountId(), actor.displayName());
    }

    @GetMapping(ROOMS + "/{roomId}/changes")
    public List<OpsChangeView> changes(Authentication authentication, @PathVariable UUID roomId) {
        actors.operator(authentication);
        return service.changes(roomId);
    }

    /**
     * @param note    为什么强制结束（1–200 字），写进改动记录
     * @param version 页面读到的版本号
     */
    record EndRequest(String note, Integer version) {
    }

    /**
     * @param replayUrl          受控 https 地址；和平台名称一起为空表示去掉回看
     * @param replayPlatformName 回看所在平台的名称（1–20 字）
     * @param version            页面读到的版本号
     */
    record ReplayRequest(String replayUrl, String replayPlatformName, Integer version) {
    }
}
