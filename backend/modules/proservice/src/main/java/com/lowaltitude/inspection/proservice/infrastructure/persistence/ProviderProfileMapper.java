package com.lowaltitude.inspection.proservice.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 服务商主页资料（S4-4，专业服务 V003）：服务商提交、平台运营审核、App 主页读审核通过的那一份。全部用绑定参数；
 * 服务商只读写自己机构的一行（provider_id 来自令牌），审核带版本号。
 */
public interface ProviderProfileMapper {

    String COLUMNS = """
            SELECT provider_id, status, submitted_legal_entity, submitted_service_modes, submitted_authorization_until,
                   submitted_deliverables, submitted_local_team_label, submitted_at, published_legal_entity,
                   published_service_modes, published_authorization_until, published_deliverables,
                   published_local_team_label, reject_reason, reviewed_at, version
            FROM pro_provider_profile
            """;

    @Select(COLUMNS + "WHERE provider_id = #{providerId}")
    ProfileRow find(@Param("providerId") UUID providerId);

    @Select(COLUMNS + "WHERE provider_id = #{providerId} FOR UPDATE")
    ProfileRow lock(@Param("providerId") UUID providerId);

    @Insert("""
            INSERT INTO pro_provider_profile (
                provider_id, status, submitted_legal_entity, submitted_service_modes, submitted_authorization_until,
                submitted_deliverables, submitted_local_team_label, submitted_by, submitted_at, created_at, updated_at
            )
            VALUES (
                #{providerId}, 'PENDING', #{legalEntity}, #{serviceModes}, #{authorizationUntil}, #{deliverables},
                #{localTeamLabel}, #{submittedBy}, #{at}, #{at}, #{at}
            )
            ON CONFLICT (provider_id) DO NOTHING
            """)
    int insert(
            @Param("providerId") UUID providerId,
            @Param("legalEntity") String legalEntity,
            @Param("serviceModes") String serviceModes,
            @Param("authorizationUntil") LocalDate authorizationUntil,
            @Param("deliverables") String deliverables,
            @Param("localTeamLabel") String localTeamLabel,
            @Param("submittedBy") UUID submittedBy,
            @Param("at") OffsetDateTime at
    );

    /** 提交一份新的去审核（替换正在审核或被驳回的那一份）；主页上的那一份不动。 */
    @Update("""
            UPDATE pro_provider_profile
            SET status = 'PENDING',
                submitted_legal_entity = #{legalEntity},
                submitted_service_modes = #{serviceModes},
                submitted_authorization_until = #{authorizationUntil},
                submitted_deliverables = #{deliverables},
                submitted_local_team_label = #{localTeamLabel},
                submitted_by = #{submittedBy},
                submitted_at = #{at},
                reject_reason = NULL,
                version = version + 1,
                updated_at = #{at}
            WHERE provider_id = #{providerId} AND version = #{version}
            """)
    int resubmit(
            @Param("providerId") UUID providerId,
            @Param("legalEntity") String legalEntity,
            @Param("serviceModes") String serviceModes,
            @Param("authorizationUntil") LocalDate authorizationUntil,
            @Param("deliverables") String deliverables,
            @Param("localTeamLabel") String localTeamLabel,
            @Param("submittedBy") UUID submittedBy,
            @Param("version") int version,
            @Param("at") OffsetDateTime at
    );

    /** 审核通过：提交的那一份成为主页上的。 */
    @Update("""
            UPDATE pro_provider_profile
            SET status = 'APPROVED',
                published_legal_entity = submitted_legal_entity,
                published_service_modes = submitted_service_modes,
                published_authorization_until = submitted_authorization_until,
                published_deliverables = submitted_deliverables,
                published_local_team_label = submitted_local_team_label,
                reject_reason = NULL,
                reviewed_by = #{reviewedBy},
                reviewed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE provider_id = #{providerId} AND version = #{version} AND status = 'PENDING'
            """)
    int approve(@Param("providerId") UUID providerId, @Param("reviewedBy") UUID reviewedBy,
                @Param("version") int version, @Param("at") OffsetDateTime at);

    @Update("""
            UPDATE pro_provider_profile
            SET status = 'REJECTED',
                reject_reason = #{reason},
                reviewed_by = #{reviewedBy},
                reviewed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE provider_id = #{providerId} AND version = #{version} AND status = 'PENDING'
            """)
    int reject(@Param("providerId") UUID providerId, @Param("reason") String reason,
               @Param("reviewedBy") UUID reviewedBy, @Param("version") int version, @Param("at") OffsetDateTime at);

    @Select("""
            <script>
            SELECT count(*) FROM pro_provider_profile
            <if test="status != null">WHERE status = #{status}</if>
            </script>
            """)
    long count(@Param("status") String status);

    /** 运营端列表：待审核在前，再按提交时间（早的在前）。 */
    @Select("<script>" + COLUMNS + """
            <if test="status != null">WHERE status = #{status}</if>
            ORDER BY CASE WHEN status = 'PENDING' THEN 0 ELSE 1 END, submitted_at, provider_id
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<ProfileRow> findPage(@Param("status") String status, @Param("limit") int limit,
                              @Param("offset") int offset);

    @Insert("""
            INSERT INTO pro_provider_profile_change (provider_id, action, summary, operator_id, operator_name, changed_at)
            VALUES (#{providerId}, #{action}, #{summary}, #{operatorId}, #{operatorName}, #{at})
            """)
    int insertChange(
            @Param("providerId") UUID providerId,
            @Param("action") String action,
            @Param("summary") String summary,
            @Param("operatorId") UUID operatorId,
            @Param("operatorName") String operatorName,
            @Param("at") OffsetDateTime at
    );

    @Select("""
            SELECT id AS change_id, provider_id, action, summary, operator_name, changed_at
            FROM pro_provider_profile_change
            WHERE provider_id = #{providerId}
            ORDER BY changed_at DESC, id DESC
            LIMIT #{limit}
            """)
    List<ChangeRow> findChanges(@Param("providerId") UUID providerId, @Param("limit") int limit);

    record ProfileRow(
            UUID providerId,
            String status,
            String submittedLegalEntity,
            String submittedServiceModes,
            LocalDate submittedAuthorizationUntil,
            String submittedDeliverables,
            String submittedLocalTeamLabel,
            OffsetDateTime submittedAt,
            String publishedLegalEntity,
            String publishedServiceModes,
            LocalDate publishedAuthorizationUntil,
            String publishedDeliverables,
            String publishedLocalTeamLabel,
            String rejectReason,
            OffsetDateTime reviewedAt,
            int version
    ) {
    }

    record ChangeRow(UUID changeId, UUID providerId, String action, String summary, String operatorName,
                     OffsetDateTime changedAt) {
    }
}
