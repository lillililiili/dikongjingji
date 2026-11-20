package com.lowaltitude.inspection.live.application;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

// 对外的形状，字段与契约 contracts/openapi-live.yaml 逐个对应。
// 观众只看到主办机构、讲解项的类型 + ID + 标题快照、弹幕作者的打码名；推流地址只在主办方的接口里。

record LiveHostView(UUID organizationId, String name) {
}

record LiveItemView(int seq, LiveItemType type, UUID refId, String title) {
}

record LiveRoomCardView(
        UUID roomId,
        String title,
        String subtitle,
        String coverUrl,
        LiveHostView host,
        String cityCode,
        LiveRoomStatus status,
        OffsetDateTime scheduledAt,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        long viewerCount,
        long likeCount,
        LiveItemView currentItem,
        boolean hasReplay
) {
}

record LiveRoomPageView(int page, int size, long total, boolean hasMore, List<LiveRoomCardView> items) {
}

/** 直播间详情。playUrl 只在直播中给。 */
record LiveRoomView(
        UUID roomId,
        String title,
        String subtitle,
        String coverUrl,
        LiveHostView host,
        String cityCode,
        LiveRoomStatus status,
        OffsetDateTime scheduledAt,
        OffsetDateTime startedAt,
        OffsetDateTime endedAt,
        long viewerCount,
        long likeCount,
        String speakerName,
        String speakerTitle,
        List<String> topics,
        List<LiveItemView> items,
        Integer currentItemSeq,
        String replayUrl,
        String replayPlatformName,
        String playUrl,
        boolean streamInterrupted
) {
}

/** 主办方看到的直播间：多一个推流地址。 */
record LiveHostRoomView(LiveRoomView room, String pushUrl) {
}

record LiveHostRoomPageView(int page, int size, long total, boolean hasMore, List<LiveHostRoomView> items) {
}

record LiveHostRoomCreated(LiveHostRoomView room, boolean created) {
}

record LiveCommentView(UUID commentId, long seq, LiveCommentSide side, String authorName, String text,
                       OffsetDateTime sentAt) {
}

record LiveCommentSent(LiveCommentView comment, boolean created) {
}

/** 一批弹幕：cursor 是这个直播间现在最新的序号；hiddenSeqs 是最近 100 条里已经隐藏的。 */
record LiveCommentPageView(List<LiveCommentView> items, long cursor, List<Long> hiddenSeqs) {
}

record LiveViewerStateView(UUID roomId, boolean reminded, boolean followingHost) {
}

record LiveViewCountView(long viewerCount, boolean counted) {
}

record LiveLikeView(int accepted, long likeCount) {
}

record LiveFollowView(UUID organizationId, boolean following) {
}

record LiveReportView(UUID commentId, boolean reported) {
}

record LiveModerationView(UUID commentId, long seq, OffsetDateTime hiddenAt) {
}

/** 主办方填的预告。 */
record LiveRoomDraft(
        String title,
        String subtitle,
        String coverUrl,
        String cityCode,
        OffsetDateTime scheduledAt,
        String speakerName,
        String speakerTitle,
        List<String> topics
) {
}

record LiveItemDraft(String type, UUID refId, String title) {
}

/** 开发环境的模拟回调要用的：直播间的流 ID 和主办机构。 */
record LiveStreamTarget(UUID roomId, String streamId, UUID hostOrgId) {
}
