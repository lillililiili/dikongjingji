package com.lowaltitude.inspection.support.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 对外的形状，字段与契约 contracts/openapi-support.yaml 逐个对应。
// 客户看到的对方只有类型、机构 ID 和名称；对方看到的客户只有显示名。两边都看不到手机号、地址和对方操作员是谁。

/** 会话的另一方。organizationId 在平台客服时为 null。 */
record SupportCounterpartView(SupportCounterpartKind kind, UUID organizationId, String name) {
}

/** 列表里的最后一条消息：preview 是文字的前 60 个字（换行换成空格）或卡片标题。 */
record SupportLastMessageView(SupportSenderSide senderSide, SupportMessageKind kind, String preview,
                              OffsetDateTime sentAt) {
}

/** 客户看到的会话。unreadCount 是客户还没读的对方消息条数。 */
record SupportConversationView(
        UUID conversationId,
        SupportCounterpartView counterpart,
        SupportLastMessageView lastMessage,
        int unreadCount,
        OffsetDateTime createdAt
) {
}

record SupportConversationPageView(int page, int size, long total, boolean hasMore, long unreadCount,
                                   List<SupportConversationView> items) {
}

record SupportConversationOpened(SupportConversationView conversation, boolean created) {
}

/** 对方看到的客户：只有显示名。 */
record SupportCustomerView(String name) {
}

/** 对方（商家、服务商的操作员，平台客服）看到的会话。unreadCount 是这一边还没读的客户消息条数。 */
record SupportDeskConversationView(
        UUID conversationId,
        SupportCounterpartKind counterpartKind,
        SupportCustomerView customer,
        SupportLastMessageView lastMessage,
        int unreadCount,
        OffsetDateTime createdAt
) {
}

record SupportDeskConversationPageView(int page, int size, long total, boolean hasMore, long unreadCount,
                                       List<SupportDeskConversationView> items) {
}

record SupportCardView(SupportCardType type, UUID refId, String title, String subtitle) {
}

/** 一条消息。mine：是不是看的这一边发的。text 和 card 只有一个。 */
record SupportMessageView(
        UUID messageId,
        int seq,
        SupportSenderSide senderSide,
        boolean mine,
        SupportMessageKind kind,
        String text,
        SupportCardView card,
        OffsetDateTime sentAt
) {
}

/** 客户的一页消息：按时间先后；hasMore 时用 nextBefore 再往前取。 */
record SupportMessagePageView(SupportConversationView conversation, List<SupportMessageView> items,
                              boolean hasMore, Integer nextBefore) {
}

record SupportDeskMessagePageView(SupportDeskConversationView conversation, List<SupportMessageView> items,
                                  boolean hasMore, Integer nextBefore) {
}

record SupportMessageSent(SupportMessageView message, boolean created) {
}

record SupportFeedbackView(UUID feedbackId, SupportFeedbackType type, String text, OffsetDateTime createdAt) {
}

record SupportFeedbackCreated(SupportFeedbackView feedback, boolean created) {
}

/** 要发的一条消息：text 和 card 只给一个。 */
record SupportMessageDraft(String text, SupportCardDraft card) {
}

record SupportCardDraft(String type, UUID refId, String title, String subtitle) {
}

// ---- 运营端处理反馈（S3-1，契约 contracts/openapi-ops-feedback.yaml） ----

/**
 * 举报的对象：VIDEO_COMMENT_REPORT 时 id 是评论、parentId 是视频；LIVE_COMMENT_REPORT 时 id 是弹幕、parentId 是直播间。
 * 运营端拿它调内容、直播模块已有的受权隐藏接口。
 */
record SupportReportTargetView(UUID id, UUID parentId) {
}

/** 提交人：只给显示名（账号停用了为 null），不给登录名、手机号。 */
record SupportFeedbackReporterView(String displayName) {
}

/** 处理结果：说明、处理人显示名和时间。 */
record SupportFeedbackHandlingView(String note, String handlerName, OffsetDateTime handledAt) {
}

record OpsFeedbackView(
        UUID feedbackId,
        SupportFeedbackType type,
        SupportFeedbackSource source,
        String text,
        SupportReportTargetView target,
        SupportFeedbackReporterView reporter,
        SupportFeedbackHandleStatus handleStatus,
        SupportFeedbackHandlingView handling,
        int version,
        OffsetDateTime createdAt
) {
}

/** pendingCount 是全部待处理的条数（不受筛选影响），给菜单角标用。 */
record OpsFeedbackPageView(int page, int size, long total, boolean hasMore, long pendingCount,
                           List<OpsFeedbackView> items) {
}
