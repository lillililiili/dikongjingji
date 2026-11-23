package com.lowaltitude.inspection.notification.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.lowaltitude.inspection.notification.application.NotificationRows.FakePushDeliveryRow;
import com.lowaltitude.inspection.notification.application.NotificationRows.PreferenceRow;
import com.lowaltitude.inspection.notification.application.NotificationRows.PushDeviceRow;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 通知偏好、设备推送标识和本机模拟推送的记录（S5-9）。全部用绑定参数；账号自己的读写都以账号为条件。 */
public interface PushDeviceMapper {

    @Select("""
            SELECT order_service_push, activity_push, discover_dot
            FROM notification_preference
            WHERE account_id = #{accountId}
            """)
    PreferenceRow findPreference(@Param("accountId") UUID accountId);

    @Insert("""
            INSERT INTO notification_preference (account_id, order_service_push, activity_push, discover_dot,
                                                 created_at, updated_at)
            VALUES (#{accountId}, #{orderServicePush}, #{activityPush}, #{discoverDot}, #{at}, #{at})
            ON CONFLICT (account_id) DO UPDATE
            SET order_service_push = EXCLUDED.order_service_push,
                activity_push = EXCLUDED.activity_push,
                discover_dot = EXCLUDED.discover_dot,
                version = notification_preference.version + 1,
                updated_at = EXCLUDED.updated_at
            """)
    int savePreference(
            @Param("accountId") UUID accountId,
            @Param("orderServicePush") boolean orderServicePush,
            @Param("activityPush") boolean activityPush,
            @Param("discoverDot") boolean discoverDot,
            @Param("at") Instant at
    );

    /** 同一台设备（平台 + 标识）只有一行：换账号登录后归到最后上报的账号，城市跟着最后一次上报。 */
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
            INSERT INTO notification_push_device (account_id, platform, push_client_id, city_code, last_reported_at,
                                                  created_at, updated_at)
            VALUES (#{accountId}, #{platform}, #{pushClientId}, #{cityCode}, #{at}, #{at}, #{at})
            ON CONFLICT (platform, push_client_id) DO UPDATE
            SET account_id = EXCLUDED.account_id,
                city_code = EXCLUDED.city_code,
                status = 'ACTIVE',
                last_reported_at = EXCLUDED.last_reported_at,
                version = notification_push_device.version + 1,
                updated_at = EXCLUDED.updated_at
            RETURNING id
            """)
    UUID registerDevice(
            @Param("accountId") UUID accountId,
            @Param("platform") String platform,
            @Param("pushClientId") String pushClientId,
            @Param("cityCode") String cityCode,
            @Param("at") Instant at
    );

    /** 退出登录：只撤这个账号名下的这台设备。 */
    @Update("""
            UPDATE notification_push_device
            SET status = 'REVOKED',
                version = version + 1,
                updated_at = #{at}
            WHERE account_id = #{accountId}
              AND platform = #{platform}
              AND push_client_id = #{pushClientId}
              AND status = 'ACTIVE'
            """)
    int revokeDevice(
            @Param("accountId") UUID accountId,
            @Param("platform") String platform,
            @Param("pushClientId") String pushClientId,
            @Param("at") Instant at
    );

    /** 一个账号最近上报过的设备（半年内、最多 10 台）。 */
    @Select("""
            SELECT id, account_id, platform, push_client_id
            FROM notification_push_device
            WHERE account_id = #{accountId}
              AND status = 'ACTIVE'
              AND last_reported_at > #{since}
            ORDER BY last_reported_at DESC, id DESC
            LIMIT 10
            """)
    List<PushDeviceRow> activeDevicesOf(@Param("accountId") UUID accountId, @Param("since") Instant since);

    /** 系统通知的收件设备，按 ID 翻页；cityCode 为空时是全部设备。 */
    @Select("""
            SELECT id, account_id, platform, push_client_id
            FROM notification_push_device
            WHERE status = 'ACTIVE'
              AND last_reported_at > #{since}
              AND (CAST(#{cityCode} AS varchar) IS NULL OR city_code = #{cityCode})
              AND (CAST(#{afterId} AS uuid) IS NULL OR id > #{afterId})
            ORDER BY id
            LIMIT #{limit}
            """)
    List<PushDeviceRow> activeDevicesPage(
            @Param("cityCode") String cityCode,
            @Param("afterId") UUID afterId,
            @Param("since") Instant since,
            @Param("limit") int limit
    );

    @Insert("""
            INSERT INTO notification_push_fake_delivery (device_id, account_id, category, title, body,
                                                         target_type, target_id, created_at)
            VALUES (#{deviceId}, #{accountId}, #{category}, #{title}, #{body}, #{targetType}, #{targetId}, #{at})
            """)
    int insertFakeDelivery(
            @Param("deviceId") UUID deviceId,
            @Param("accountId") UUID accountId,
            @Param("category") String category,
            @Param("title") String title,
            @Param("body") String body,
            @Param("targetType") String targetType,
            @Param("targetId") UUID targetId,
            @Param("at") Instant at
    );

    @Select("""
            SELECT delivery.id, delivery.device_id, device.platform, delivery.category, delivery.title, delivery.body,
                   delivery.target_type, delivery.target_id, delivery.created_at
            FROM notification_push_fake_delivery delivery
            JOIN notification_push_device device ON device.id = delivery.device_id
            WHERE delivery.account_id = #{accountId}
            ORDER BY delivery.created_at DESC, delivery.id DESC
            LIMIT #{limit}
            """)
    List<FakePushDeliveryRow> findFakeDeliveries(@Param("accountId") UUID accountId, @Param("limit") int limit);
}
