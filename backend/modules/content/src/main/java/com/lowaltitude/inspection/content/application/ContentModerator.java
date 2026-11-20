package com.lowaltitude.inspection.content.application;

import java.util.UUID;

/**
 * 隐藏评论的人：平台客服（platformSupport），或服务商、商家的操作员（providerOrganizationId 是令牌里的机构，
 * 只能管本机构视频下的评论）。
 */
public record ContentModerator(UUID accountId, boolean platformSupport, UUID providerOrganizationId) {
}
