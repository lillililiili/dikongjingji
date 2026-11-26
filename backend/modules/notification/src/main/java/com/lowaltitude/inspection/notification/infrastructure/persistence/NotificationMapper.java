package com.lowaltitude.inspection.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.MessageRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 站内信的读写。每条语句都以收件人为条件，全部用绑定参数。 */
public interface NotificationMapper {

    @Insert("""
            INSERT INTO notification_message (
                id, recipient_id, category, event_code, title, body,
                target_type, target_id, target_sub_id, source_key, occurred_at
            )
            VALUES (
                gen_random_uuid(), #{recipientId}, #{category}, #{eventCode}, #{title}, #{body},
                #{targetType}, #{targetId}, #{targetSubId}, #{sourceKey}, #{occurredAt}
            )
            ON CONFLICT (source_key) DO NOTHING
            """)
    int insert(
            @Param("recipientId") UUID recipientId,
            @Param("category") String category,
            @Param("eventCode") String eventCode,
            @Param("title") String title,
            @Param("body") String body,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("targetSubId") UUID targetSubId,
            @Param("sourceKey") String sourceKey,
            @Param("occurredAt") Instant occurredAt
    );

    /**
     * 同一收件人、分类、跳转目标上没有未读的才写（G1 客服消息：一个会话最多一条未读）。
     * 并发时由 uq_notification_message_unread_conversation 兜底，冲突时什么都不写。
     */
    @Insert("""
            INSERT INTO notification_message (
                id, recipient_id, category, event_code, title, body,
                target_type, target_id, target_sub_id, source_key, occurred_at
            )
            SELECT gen_random_uuid(), #{recipientId}, #{category}, #{eventCode}, #{title}, #{body},
                   #{targetType}, #{targetId}, #{targetSubId}, #{sourceKey}, #{occurredAt}
            WHERE NOT EXISTS (
                SELECT 1
                FROM notification_message unread
                WHERE unread.recipient_id = #{recipientId}
                  AND unread.category = #{category}
                  AND unread.target_type = #{targetType}
                  AND unread.target_id = #{targetId}
                  AND unread.status = 'ACTIVE'
                  AND unread.read_at IS NULL
            )
            ON CONFLICT DO NOTHING
            """)
    int insertUnlessUnread(
            @Param("recipientId") UUID recipientId,
            @Param("category") String category,
            @Param("eventCode") String eventCode,
            @Param("title") String title,
            @Param("body") String body,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("targetSubId") UUID targetSubId,
            @Param("sourceKey") String sourceKey,
            @Param("occurredAt") Instant occurredAt
    );

    @Update("""
            UPDATE notification_message
            SET event_code = #{eventCode},
                title = #{title},
                body = #{body},
                occurred_at = GREATEST(occurred_at, #{occurredAt}),
                version = version + 1,
                updated_at = #{occurredAt}
            WHERE recipient_id = #{recipientId}
              AND category = #{category}
              AND target_type = #{targetType}
              AND target_id = #{targetId}
              AND status = 'ACTIVE'
              AND read_at IS NULL
            """)
    int refreshUnread(
            @Param("recipientId") UUID recipientId,
            @Param("category") String category,
            @Param("eventCode") String eventCode,
            @Param("title") String title,
            @Param("body") String body,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("occurredAt") Instant occurredAt
    );

    @Update("""
            UPDATE notification_message
            SET read_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE recipient_id = #{recipientId}
              AND category = #{category}
              AND target_type = #{targetType}
              AND target_id = #{targetId}
              AND status = 'ACTIVE'
              AND read_at IS NULL
            """)
    int markTargetRead(
            @Param("recipientId") UUID recipientId,
            @Param("category") String category,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("at") Instant at
    );

    @Select("""
            SELECT count(*)
            FROM notification_message
            WHERE recipient_id = #{recipientId}
              AND status = 'ACTIVE'
              AND (#{systemNotices} OR category != 'SYSTEM_NOTICE')
            """)
    long count(@Param("recipientId") UUID recipientId, @Param("systemNotices") boolean systemNotices);

    @Select("""
            SELECT count(*)
            FROM notification_message
            WHERE recipient_id = #{recipientId}
              AND status = 'ACTIVE'
              AND (#{systemNotices} OR category != 'SYSTEM_NOTICE')
              AND read_at IS NULL
            """)
    long countUnread(@Param("recipientId") UUID recipientId, @Param("systemNotices") boolean systemNotices);

    @Select("""
            SELECT id,
                   category,
                   event_code,
                   title,
                   body,
                   target_type,
                   target_id,
                   target_sub_id,
                   occurred_at,
                   read_at
            FROM notification_message
            WHERE recipient_id = #{recipientId}
              AND status = 'ACTIVE'
              AND (#{systemNotices} OR category != 'SYSTEM_NOTICE')
            ORDER BY occurred_at DESC, created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MessageRow> findPage(
            @Param("recipientId") UUID recipientId,
            @Param("limit") int limit,
            @Param("offset") int offset,
            @Param("systemNotices") boolean systemNotices
    );

    @Select("""
            SELECT count(*)
            FROM notification_message
            WHERE id = #{id}
              AND recipient_id = #{recipientId}
              AND status = 'ACTIVE'
            """)
    long countOwned(@Param("recipientId") UUID recipientId, @Param("id") UUID id);

    @Update("""
            UPDATE notification_message
            SET read_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id}
              AND recipient_id = #{recipientId}
              AND status = 'ACTIVE'
              AND read_at IS NULL
            """)
    int markRead(@Param("recipientId") UUID recipientId, @Param("id") UUID id, @Param("at") Instant at);

    @Update("""
            UPDATE notification_message
            SET read_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE recipient_id = #{recipientId}
              AND status = 'ACTIVE'
              AND (#{systemNotices} OR category != 'SYSTEM_NOTICE')
              AND read_at IS NULL
            """)
    int markAllRead(@Param("recipientId") UUID recipientId, @Param("at") Instant at, @Param("systemNotices") boolean systemNotices);

    /**
     * 系统通知（S5-9）：把发给全体、发给这个城市、还在有效期内的公告补记成收件人的一条站内信。
     * 每份公告每个账号只记一条（source_key），撤回的公告不再补记；时间用公告的发布时间。
     * cityCode 为空时只补发给全体的。
     */
    @Insert("""
            INSERT INTO notification_message (
                id, recipient_id, category, event_code, title, body,
                target_type, target_id, target_sub_id, source_key, occurred_at
            )
            SELECT gen_random_uuid(), #{recipientId}, 'SYSTEM_NOTICE', 'SYSTEM_NOTICE_PUBLISHED', notice.title,
                   notice.summary, 'SYSTEM_NOTICE', notice.id, NULL,
                   'system-notice:' || notice.id || ':' || CAST(#{recipientId} AS text), notice.published_at
            FROM notification_system_notice notice
            WHERE notice.status = 'PUBLISHED'
              AND notice.published_at <= #{now}
              AND (notice.expires_at IS NULL OR notice.expires_at > #{now})
              AND (notice.audience_scope = 'ALL'
                   OR (notice.audience_scope = 'CITY' AND notice.city_code = #{cityCode}))
            ON CONFLICT (source_key) DO NOTHING
            """)
    int deliverSystemNotices(
            @Param("recipientId") UUID recipientId,
            @Param("cityCode") String cityCode,
            @Param("now") Instant now
    );
}
