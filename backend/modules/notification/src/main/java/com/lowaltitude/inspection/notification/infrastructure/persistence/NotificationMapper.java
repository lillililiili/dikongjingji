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

    @Select("""
            SELECT count(*)
            FROM notification_message
            WHERE recipient_id = #{recipientId}
              AND status = 'ACTIVE'
            """)
    long count(@Param("recipientId") UUID recipientId);

    @Select("""
            SELECT count(*)
            FROM notification_message
            WHERE recipient_id = #{recipientId}
              AND status = 'ACTIVE'
              AND read_at IS NULL
            """)
    long countUnread(@Param("recipientId") UUID recipientId);

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
            ORDER BY occurred_at DESC, created_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<MessageRow> findPage(
            @Param("recipientId") UUID recipientId,
            @Param("limit") int limit,
            @Param("offset") int offset
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
              AND read_at IS NULL
            """)
    int markAllRead(@Param("recipientId") UUID recipientId, @Param("at") Instant at);
}
