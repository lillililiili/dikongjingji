package com.lowaltitude.inspection.ops;

import java.util.UUID;

/** 运营端的操作人：账号 ID 和显示名（写进操作记录，页面上显示“谁改的”）。不含登录名、手机号。 */
public record OpsActor(UUID accountId, String displayName) {
}
