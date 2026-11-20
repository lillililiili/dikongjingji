package com.lowaltitude.inspection.content.application;

import java.util.UUID;

/**
 * 看视频、点赞、评论的账号。providerOrganizationId 只在服务商、商家的操作员身上有（令牌里的机构），
 * 用来认出“作者”的评论；客户为 null。
 */
public record ContentActor(UUID accountId, UUID providerOrganizationId) {

    public static ContentActor customer(UUID accountId) {
        return new ContentActor(accountId, null);
    }
}
