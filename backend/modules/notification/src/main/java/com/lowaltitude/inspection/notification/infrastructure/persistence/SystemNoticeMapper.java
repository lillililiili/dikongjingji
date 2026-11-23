package com.lowaltitude.inspection.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.SystemNoticeRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 系统通知（S5-9）。全部用绑定参数。公告不属于哪个账号，权限在接口上（平台运营发布和撤回，详情公开只读）。 */
public interface SystemNoticeMapper {

    String COLUMNS = """
            SELECT id, title, summary, CAST(content AS text) AS content, about_agreement, audience_scope, city_code,
                   published_at, expires_at, status, withdrawn_at
            FROM notification_system_notice
            """;

    /** 同一个发布幂等键只登记一次。 */
    @Insert("""
            INSERT INTO notification_system_notice (
                id, title, summary, content, about_agreement, audience_scope, city_code,
                published_by, published_at, expires_at, publish_key, created_at, updated_at
            )
            VALUES (
                #{id}, #{title}, #{summary}, CAST(#{content} AS jsonb), #{aboutAgreement}, #{audienceScope}, #{cityCode},
                #{publishedBy}, #{publishedAt}, #{expiresAt}, #{publishKey}, #{publishedAt}, #{publishedAt}
            )
            ON CONFLICT (publish_key) DO NOTHING
            """)
    int insert(
            @Param("id") UUID id,
            @Param("title") String title,
            @Param("summary") String summary,
            @Param("content") String content,
            @Param("aboutAgreement") String aboutAgreement,
            @Param("audienceScope") String audienceScope,
            @Param("cityCode") String cityCode,
            @Param("publishedBy") UUID publishedBy,
            @Param("publishedAt") Instant publishedAt,
            @Param("expiresAt") Instant expiresAt,
            @Param("publishKey") String publishKey
    );

    @Select(COLUMNS + "WHERE publish_key = #{publishKey}")
    SystemNoticeRow findByPublishKey(@Param("publishKey") String publishKey);

    @Select(COLUMNS + "WHERE id = #{id}")
    SystemNoticeRow find(@Param("id") UUID id);

    /** 某份协议最近一次、还在有效期内、发给全体的更新公告（协议页“有更新”）。 */
    @Select(COLUMNS + """
            WHERE about_agreement = #{agreement}
              AND status = 'PUBLISHED'
              AND audience_scope = 'ALL'
              AND published_at <= #{now}
              AND (expires_at IS NULL OR expires_at > #{now})
            ORDER BY published_at DESC, id DESC
            LIMIT 1
            """)
    SystemNoticeRow latestAbout(@Param("agreement") String agreement, @Param("now") Instant now);

    @Select(COLUMNS + """
            ORDER BY published_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<SystemNoticeRow> page(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT count(*) FROM notification_system_notice")
    long count();

    @Update("""
            UPDATE notification_system_notice
            SET status = 'WITHDRAWN',
                withdrawn_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{id}
              AND status = 'PUBLISHED'
            """)
    int withdraw(@Param("id") UUID id, @Param("at") Instant at);

    /** 撤回后，已经补记给各账号的那一条也从“消息”里拿掉（标为 DELETED，不物理删除）。 */
    @Update("""
            UPDATE notification_message
            SET status = 'DELETED',
                version = version + 1,
                updated_at = #{at}
            WHERE category = 'SYSTEM_NOTICE'
              AND target_type = 'SYSTEM_NOTICE'
              AND target_id = #{id}
              AND status = 'ACTIVE'
            """)
    int hideDelivered(@Param("id") UUID id, @Param("at") Instant at);
}
