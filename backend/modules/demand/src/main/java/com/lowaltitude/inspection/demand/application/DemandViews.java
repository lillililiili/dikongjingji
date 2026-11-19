package com.lowaltitude.inspection.demand.application;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 对外的形状，字段与契约 contracts/openapi-demand.yaml 逐个对应。

/** 需求对客户的状态（落库的状态，契约 DemandStatus）。 */
enum DemandStatus {
    DRAFT,
    OPEN,
    QUOTED,
    CHOSEN,
    CLOSED
}

enum DemandCloseReason {
    NO_QUOTES,
    CANCELLED
}

enum DemandQuoteScope {
    MATCH,
    INVITE
}

/** 发布身份：记在客户组织名下，或个人。 */
enum DemandPublisherKind {
    ORGANIZATION,
    PERSONAL
}

/** 报价的状态（契约 DemandQuoteStatus）。EXPIRED 是算出来的：有效、但过了有效期。 */
enum DemandQuoteStatus {
    ACTIVE,
    EXPIRED,
    WITHDRAWN,
    CHOSEN,
    NOT_CHOSEN,
    CLOSED
}

/** 报价比选的排序。 */
enum DemandQuoteSort {
    ALL,
    PRICE,
    DURATION
}

/** 发布身份：organizationName 只在记在组织名下时有。 */
record DemandPublisherView(DemandPublisherKind kind, String organizationName) {
}

record DemandDeliverableView(DemandDeliverable code, String label) {
}

record DemandProviderView(UUID providerId, String name, String certificationLabel, String description) {
}

record DemandProviderListView(List<DemandProviderView> items) {
}

/** 选定的报价摘要（列表“已选定 某某 · 方案 v2 · ¥18,600”）。 */
record DemandChosenView(UUID quoteId, int quoteVersion, String providerName, long amountMinor, OffsetDateTime chosenAt) {
}

record DemandView(
        UUID demandId,
        DemandStatus status,
        DemandCloseReason closeReason,
        DemandType type,
        String title,
        DemandPublisherView publisher,
        String cityCode,
        String cityName,
        String address,
        String scope,
        LocalDate dueDate,
        Long budgetMinMinor,
        Long budgetMaxMinor,
        List<DemandDeliverableView> deliverables,
        String note,
        DemandQuoteScope quoteScope,
        List<DemandProviderView> invitedProviders,
        OffsetDateTime submittedAt,
        OffsetDateTime quoteDeadline,
        boolean quotingOpen,
        int quoteCount,
        DemandChosenView chosen,
        boolean editable,
        boolean cancellable,
        boolean deletable,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        int version
) {
}

record DemandPageView(int page, int size, long total, boolean hasMore, List<DemandView> items) {
}

record DemandQuoteProviderView(UUID providerId, String name, String certificationLabel) {
}

/** 客户看到的一份报价（最新一版）。 */
record DemandQuoteView(
        UUID quoteId,
        DemandQuoteProviderView provider,
        int version,
        DemandQuoteStatus status,
        long amountMinor,
        String method,
        String schedule,
        int durationDays,
        String deliverables,
        OffsetDateTime validUntil,
        OffsetDateTime submittedAt,
        OffsetDateTime revisedAt,
        boolean choosable
) {
}

record DemandQuoteListView(
        UUID demandId,
        DemandStatus demandStatus,
        int demandVersion,
        DemandQuoteSort sort,
        List<DemandQuoteView> items
) {
}

/** 服务商自己的报价。 */
record ProviderQuoteView(
        UUID quoteId,
        int version,
        DemandQuoteStatus status,
        long amountMinor,
        String method,
        String schedule,
        int durationDays,
        String deliverables,
        OffsetDateTime validUntil,
        OffsetDateTime submittedAt,
        OffsetDateTime revisedAt
) {
}

/**
 * 服务商看到的需求：只有需求内容，没有发布人的账号、组织和联系方式。
 * invited 表示是邀请了它（否则是平台匹配看到的）。
 */
record ProviderDemandView(
        UUID demandId,
        DemandStatus status,
        DemandType type,
        String title,
        String cityCode,
        String cityName,
        String address,
        String scope,
        LocalDate dueDate,
        Long budgetMinMinor,
        Long budgetMaxMinor,
        List<DemandDeliverableView> deliverables,
        String note,
        boolean invited,
        OffsetDateTime submittedAt,
        OffsetDateTime quoteDeadline,
        boolean quotingOpen,
        ProviderQuoteView myQuote
) {
}

record ProviderDemandPageView(int page, int size, long total, boolean hasMore, List<ProviderDemandView> items) {
}

/** 客户填的需求内容（新建、改草稿）。草稿可以不完整，提交时再校验必填项。 */
record DemandDraft(
        String type,
        String cityCode,
        String address,
        String scope,
        String dueDate,
        Long budgetMinMinor,
        Long budgetMaxMinor,
        List<String> deliverables,
        String note,
        String quoteScope,
        List<UUID> invitedProviderIds
) {
}

/** 服务商的报价内容。 */
record QuoteDraft(
        Long amountMinor,
        String method,
        String schedule,
        Integer durationDays,
        String deliverables,
        OffsetDateTime validUntil
) {
}

/** created 为 false 表示同一个幂等键重放，返回的是第一次建的需求。 */
record DemandCreated(DemandView demand, boolean created) {
}

/** 发布身份：账号的默认组织是客户组织时给出组织 ID 和名称，否则都为空（个人）。 */
record DemandPublisher(UUID organizationId, String organizationName) {

    static DemandPublisher personal() {
        return new DemandPublisher(null, null);
    }
}
