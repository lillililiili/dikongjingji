package com.lowaltitude.inspection.live.application;

import java.util.UUID;

/**
 * 运营端“直播间”的主办机构一行（契约 openapi-ops-live.yaml 的 OpsLiveHost）：开通了直播主办的经营机构（身份模块），
 * 机构里有直播主办角色的在用账号数，和它的直播间数（直播模块）。
 */
public record OpsLiveHostView(UUID organizationId, String organizationName, String cityCode, long hostAccountCount,
                              long roomCount, long liveRoomCount, long scheduledRoomCount) {
}
