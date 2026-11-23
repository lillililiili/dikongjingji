package com.lowaltitude.inspection.demand.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 需求、交付物、邀请、报价、状态变化记录的读写（F1）。全部用绑定参数；客户的读写都以发布账号为条件，
 * 服务商的读写以服务商机构为条件（能不能看到由服务按平台匹配、邀请和自己的报价判断）。“当前时间”由调用方传入。
 * 加锁顺序：需求单 → 报价。
 */
public interface DemandMapper {

    // ---- 新建（同一个账号的提交排队，幂等键在锁内查） ----

    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended('demand:' || CAST(#{accountId} AS text), 0))
            ) locked
            """)
    Integer lockAccount(@Param("accountId") UUID accountId);

    @Select("""
            SELECT id, request_hash
            FROM demand
            WHERE account_id = #{accountId}
              AND idempotency_key = #{idempotencyKey}
            """)
    KeyRow findByKey(@Param("accountId") UUID accountId, @Param("idempotencyKey") UUID idempotencyKey);

    @Insert("""
            INSERT INTO demand (
                id, account_id, organization_id, organization_name, type, city_code, city_name, address, scope_text,
                due_date, budget_min_minor, budget_max_minor, note, quote_scope, idempotency_key, request_hash,
                created_at, updated_at
            )
            VALUES (
                #{demandId}, #{accountId}, #{organizationId}, #{organizationName}, #{type}, #{cityCode}, #{cityName},
                #{address}, #{scopeText}, #{dueDate}, #{budgetMinMinor}, #{budgetMaxMinor}, #{note}, #{quoteScope},
                #{idempotencyKey}, #{requestHash}, #{at}, #{at}
            )
            """)
    int insertDemand(
            @Param("demandId") UUID demandId,
            @Param("accountId") UUID accountId,
            @Param("organizationId") UUID organizationId,
            @Param("organizationName") String organizationName,
            @Param("type") String type,
            @Param("cityCode") String cityCode,
            @Param("cityName") String cityName,
            @Param("address") String address,
            @Param("scopeText") String scopeText,
            @Param("dueDate") LocalDate dueDate,
            @Param("budgetMinMinor") Long budgetMinMinor,
            @Param("budgetMaxMinor") Long budgetMaxMinor,
            @Param("note") String note,
            @Param("quoteScope") String quoteScope,
            @Param("idempotencyKey") UUID idempotencyKey,
            @Param("requestHash") String requestHash,
            @Param("at") OffsetDateTime at
    );

    /** 只改自己的草稿。 */
    @Update("""
            UPDATE demand
            SET type = #{type},
                city_code = #{cityCode},
                city_name = #{cityName},
                address = #{address},
                scope_text = #{scopeText},
                due_date = #{dueDate},
                budget_min_minor = #{budgetMinMinor},
                budget_max_minor = #{budgetMaxMinor},
                note = #{note},
                quote_scope = #{quoteScope},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{demandId}
              AND account_id = #{accountId}
              AND status = 'DRAFT'
            """)
    int updateDraft(
            @Param("demandId") UUID demandId,
            @Param("accountId") UUID accountId,
            @Param("type") String type,
            @Param("cityCode") String cityCode,
            @Param("cityName") String cityName,
            @Param("address") String address,
            @Param("scopeText") String scopeText,
            @Param("dueDate") LocalDate dueDate,
            @Param("budgetMinMinor") Long budgetMinMinor,
            @Param("budgetMaxMinor") Long budgetMaxMinor,
            @Param("note") String note,
            @Param("quoteScope") String quoteScope,
            @Param("at") OffsetDateTime at
    );

    @Delete("DELETE FROM demand_deliverable WHERE demand_id = #{demandId}")
    int deleteDeliverables(@Param("demandId") UUID demandId);

    @Insert("""
            INSERT INTO demand_deliverable (demand_id, code, sort_order)
            VALUES (#{demandId}, #{code}, #{sortOrder})
            """)
    int insertDeliverable(@Param("demandId") UUID demandId, @Param("code") String code, @Param("sortOrder") int sortOrder);

    @Delete("DELETE FROM demand_invitation WHERE demand_id = #{demandId}")
    int deleteInvitations(@Param("demandId") UUID demandId);

    @Insert("""
            INSERT INTO demand_invitation (demand_id, provider_id, sort_order)
            VALUES (#{demandId}, #{providerId}, #{sortOrder})
            """)
    int insertInvitation(
            @Param("demandId") UUID demandId,
            @Param("providerId") UUID providerId,
            @Param("sortOrder") int sortOrder
    );

    /** 草稿 → 待报价，记下提交时间和报价截止时间。 */
    @Update("""
            UPDATE demand
            SET status = 'OPEN',
                submitted_at = #{at},
                quote_deadline = #{deadline},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{demandId}
              AND status = 'DRAFT'
            """)
    int submit(@Param("demandId") UUID demandId, @Param("deadline") OffsetDateTime deadline, @Param("at") OffsetDateTime at);

    /** 只删自己的草稿（交付物、邀请、记录随之删掉）。 */
    @Delete("""
            DELETE FROM demand
            WHERE id = #{demandId}
              AND account_id = #{accountId}
              AND status = 'DRAFT'
            """)
    int deleteDraft(@Param("accountId") UUID accountId, @Param("demandId") UUID demandId);

    // ---- 读需求 ----

    String DEMAND_COLUMNS = """
            SELECT d.id,
                   d.account_id,
                   d.organization_id,
                   d.organization_name,
                   d.type,
                   d.city_code,
                   d.city_name,
                   d.address,
                   d.scope_text,
                   d.due_date,
                   d.budget_min_minor,
                   d.budget_max_minor,
                   d.note,
                   d.quote_scope,
                   d.status,
                   d.submitted_at,
                   d.quote_deadline,
                   d.chosen_quote_id,
                   d.chosen_quote_version,
                   d.chosen_at,
                   d.closed_at,
                   d.close_reason,
                   d.service_order_id,
                   d.created_at,
                   d.updated_at,
                   d.version
            FROM demand d
            """;

    @Select(DEMAND_COLUMNS + """
            WHERE d.id = #{demandId}
              AND d.account_id = #{accountId}
            """)
    DemandRow findDemand(@Param("accountId") UUID accountId, @Param("demandId") UUID demandId);

    @Select(DEMAND_COLUMNS + """
            WHERE d.id = #{demandId}
              AND d.account_id = #{accountId}
            FOR UPDATE
            """)
    DemandRow lockDemand(@Param("accountId") UUID accountId, @Param("demandId") UUID demandId);

    /** 我的需求：status 为空时全部（含草稿），否则只要这一种状态。新的在前（草稿按最近保存）。 */
    String ACCOUNT_FILTER = """
            WHERE d.account_id = #{accountId}
              AND (CAST(#{status} AS varchar) IS NULL OR d.status = #{status})
            """;

    @Select("SELECT count(*) FROM demand d " + ACCOUNT_FILTER)
    long countDemands(@Param("accountId") UUID accountId, @Param("status") String status);

    @Select(DEMAND_COLUMNS + ACCOUNT_FILTER + """
            ORDER BY d.updated_at DESC, d.id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<DemandRow> findDemandPage(
            @Param("accountId") UUID accountId,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    @Select("""
            <script>
            SELECT demand_id, code
            FROM demand_deliverable
            WHERE demand_id IN
            <foreach collection="demandIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY demand_id, sort_order, code
            </script>
            """)
    List<DeliverableRow> findDeliverables(@Param("demandIds") List<UUID> demandIds);

    @Select("""
            <script>
            SELECT demand_id, provider_id
            FROM demand_invitation
            WHERE demand_id IN
            <foreach collection="demandIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY demand_id, sort_order, provider_id
            </script>
            """)
    List<InvitationRow> findInvitations(@Param("demandIds") List<UUID> demandIds);

    // ---- 需求的状态 ----

    /** 待报价 ↔ 报价中：有没有有效报价变了。 */
    @Update("""
            UPDATE demand
            SET status = #{toStatus},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{demandId}
              AND status = #{fromStatus}
            """)
    int changeStatus(
            @Param("demandId") UUID demandId,
            @Param("fromStatus") String fromStatus,
            @Param("toStatus") String toStatus,
            @Param("at") OffsetDateTime at
    );

    /** 关闭：只关还在待报价、报价中的。 */
    @Update("""
            UPDATE demand
            SET status = 'CLOSED',
                close_reason = #{reason},
                closed_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{demandId}
              AND status IN ('OPEN', 'QUOTED')
            """)
    int close(@Param("demandId") UUID demandId, @Param("reason") String reason, @Param("at") OffsetDateTime at);

    /** 选定：只改还在待报价、报价中，版本也对得上的那一份。 */
    @Update("""
            UPDATE demand
            SET status = 'CHOSEN',
                chosen_quote_id = #{quoteId},
                chosen_quote_version = #{quoteVersion},
                chosen_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{demandId}
              AND status IN ('OPEN', 'QUOTED')
              AND version = #{version}
            """)
    int choose(
            @Param("demandId") UUID demandId,
            @Param("quoteId") UUID quoteId,
            @Param("quoteVersion") int quoteVersion,
            @Param("version") int version,
            @Param("at") OffsetDateTime at
    );

    /** 定时任务：过了报价截止时间、还没有有效报价的需求，跳过别的实例正在处理的行。 */
    @Select(DEMAND_COLUMNS + """
            WHERE d.status = 'OPEN'
              AND d.quote_deadline <= #{now}
            ORDER BY d.quote_deadline ASC, d.id ASC
            LIMIT #{limit}
            FOR UPDATE SKIP LOCKED
            """)
    List<DemandRow> lockExpiredOpen(@Param("now") OffsetDateTime now, @Param("limit") int limit);

    @Insert("""
            INSERT INTO demand_event (demand_id, quote_id, event_type, actor_id, quote_version, amount_minor, occurred_at)
            VALUES (#{demandId}, #{quoteId}, #{eventType}, #{actorId}, #{quoteVersion}, #{amountMinor}, #{at})
            """)
    int insertEvent(
            @Param("demandId") UUID demandId,
            @Param("quoteId") UUID quoteId,
            @Param("eventType") String eventType,
            @Param("actorId") UUID actorId,
            @Param("quoteVersion") Integer quoteVersion,
            @Param("amountMinor") Long amountMinor,
            @Param("at") OffsetDateTime at
    );

    // ---- 服务商 ----

    /** 服务商找需求时先锁需求单（报价和选定都先锁它）。 */
    @Select(DEMAND_COLUMNS + """
            WHERE d.id = #{demandId}
              AND d.status <> 'DRAFT'
            FOR UPDATE
            """)
    DemandRow lockSubmittedDemand(@Param("demandId") UUID demandId);

    @Select(DEMAND_COLUMNS + """
            WHERE d.id = #{demandId}
              AND d.status <> 'DRAFT'
            """)
    DemandRow findSubmittedDemand(@Param("demandId") UUID demandId);

    @Select("""
            SELECT count(*)
            FROM demand_invitation
            WHERE demand_id = #{demandId}
              AND provider_id = #{providerId}
            """)
    int countInvitation(@Param("demandId") UUID demandId, @Param("providerId") UUID providerId);

    /**
     * 服务商能看到的需求：还在收报价的、平台匹配并且在它服务的城市，或者邀请了它的；以及它报过价的（看结果）。
     * 草稿永远看不到。在 &lt;script&gt; 里，所以不写小于号。
     */
    String PROVIDER_FILTER = """
            WHERE d.status IN ('OPEN', 'QUOTED', 'CHOSEN', 'CLOSED')
              AND (
                  (d.status IN ('OPEN', 'QUOTED') AND (
                      <if test="cities != null and cities.size() > 0">
                      (d.quote_scope = 'MATCH' AND d.city_code IN
                      <foreach collection="cities" item="city" open="(" separator="," close=")">#{city}</foreach>)
                      OR
                      </if>
                      EXISTS (SELECT 1 FROM demand_invitation i
                              WHERE i.demand_id = d.id AND i.provider_id = #{providerId})))
                  OR EXISTS (SELECT 1 FROM demand_quote q WHERE q.demand_id = d.id AND q.provider_id = #{providerId})
              )
            <if test="status != null">
              AND d.status = #{status}
            </if>
            """;

    @Select("<script>SELECT count(*) FROM demand d " + PROVIDER_FILTER + "</script>")
    long countProviderDemands(@Param("providerId") UUID providerId, @Param("cities") List<String> cities,
                              @Param("status") String status);

    @Select("<script>" + DEMAND_COLUMNS + PROVIDER_FILTER + """
            ORDER BY d.submitted_at DESC, d.id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DemandRow> findProviderDemands(
            @Param("providerId") UUID providerId,
            @Param("cities") List<String> cities,
            @Param("status") String status,
            @Param("limit") int limit,
            @Param("offset") int offset
    );

    // ---- 报价 ----

    String QUOTE_COLUMNS = """
            SELECT q.id,
                   q.demand_id,
                   q.provider_id,
                   q.status,
                   q.quote_version,
                   q.amount_minor,
                   q.method,
                   q.schedule_text,
                   q.duration_days,
                   q.deliverables_text,
                   q.valid_until,
                   q.first_submitted_at,
                   q.revised_at,
                   q.team_text,
                   q.night_work_text,
                   q.report_hours,
                   q.archive_days,
                   q.first_visit_date
            FROM demand_quote q
            """;

    @Select(QUOTE_COLUMNS + """
            WHERE q.demand_id = #{demandId}
            ORDER BY q.first_submitted_at ASC, q.id ASC
            """)
    List<QuoteRow> findQuotes(@Param("demandId") UUID demandId);

    @Select("""
            <script>
            """ + QUOTE_COLUMNS + """
            WHERE q.demand_id IN
            <foreach collection="demandIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY q.first_submitted_at ASC, q.id ASC
            </script>
            """)
    List<QuoteRow> findQuotesOf(@Param("demandIds") List<UUID> demandIds);

    @Select(QUOTE_COLUMNS + """
            WHERE q.demand_id = #{demandId}
              AND q.provider_id = #{providerId}
            FOR UPDATE
            """)
    QuoteRow lockProviderQuote(@Param("demandId") UUID demandId, @Param("providerId") UUID providerId);

    @Select("""
            <script>
            """ + QUOTE_COLUMNS + """
            WHERE q.provider_id = #{providerId}
              AND q.demand_id IN
            <foreach collection="demandIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<QuoteRow> findProviderQuotes(@Param("providerId") UUID providerId, @Param("demandIds") List<UUID> demandIds);

    @Select("""
            INSERT INTO demand_quote (
                demand_id, provider_id, amount_minor, method, schedule_text, duration_days, deliverables_text,
                valid_until, first_submitted_at, revised_at, submitted_by, created_at, updated_at
            )
            VALUES (
                #{demandId}, #{providerId}, #{amountMinor}, #{method}, #{scheduleText}, #{durationDays},
                #{deliverablesText}, #{validUntil}, #{at}, #{at}, #{submittedBy}, #{at}, #{at}
            )
            RETURNING id
            """)
    @Options(flushCache = Options.FlushCachePolicy.TRUE)
    UUID insertQuote(
            @Param("demandId") UUID demandId,
            @Param("providerId") UUID providerId,
            @Param("amountMinor") long amountMinor,
            @Param("method") String method,
            @Param("scheduleText") String scheduleText,
            @Param("durationDays") int durationDays,
            @Param("deliverablesText") String deliverablesText,
            @Param("validUntil") OffsetDateTime validUntil,
            @Param("submittedBy") UUID submittedBy,
            @Param("at") OffsetDateTime at
    );

    /** 改价，或撤回后重新报价：内容覆盖、版本号加 1、重新生效。已选中、已结束的不改。 */
    @Update("""
            UPDATE demand_quote
            SET status = 'ACTIVE',
                quote_version = quote_version + 1,
                amount_minor = #{amountMinor},
                method = #{method},
                schedule_text = #{scheduleText},
                duration_days = #{durationDays},
                deliverables_text = #{deliverablesText},
                valid_until = #{validUntil},
                revised_at = #{at},
                submitted_by = #{submittedBy},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{quoteId}
              AND status IN ('ACTIVE', 'WITHDRAWN')
            """)
    int reviseQuote(
            @Param("quoteId") UUID quoteId,
            @Param("amountMinor") long amountMinor,
            @Param("method") String method,
            @Param("scheduleText") String scheduleText,
            @Param("durationDays") int durationDays,
            @Param("deliverablesText") String deliverablesText,
            @Param("validUntil") OffsetDateTime validUntil,
            @Param("submittedBy") UUID submittedBy,
            @Param("at") OffsetDateTime at
    );

    @Update("""
            UPDATE demand_quote
            SET status = 'WITHDRAWN',
                revised_at = #{at},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{quoteId}
              AND status = 'ACTIVE'
            """)
    int withdrawQuote(@Param("quoteId") UUID quoteId, @Param("at") OffsetDateTime at);

    @Select("""
            SELECT count(*)
            FROM demand_quote
            WHERE demand_id = #{demandId}
              AND status = 'ACTIVE'
            """)
    int countActiveQuotes(@Param("demandId") UUID demandId);

    /** 选定：被选的报价 → 已选中（只改仍有效、版本对得上的那一份）。 */
    @Update("""
            UPDATE demand_quote
            SET status = 'CHOSEN',
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{quoteId}
              AND demand_id = #{demandId}
              AND status = 'ACTIVE'
              AND quote_version = #{quoteVersion}
            """)
    int markChosen(
            @Param("demandId") UUID demandId,
            @Param("quoteId") UUID quoteId,
            @Param("quoteVersion") int quoteVersion,
            @Param("at") OffsetDateTime at
    );

    /** 这份需求其余还有效的报价 → 未选中（选定后）或已关闭（需求取消、关闭后）。 */
    @Update("""
            UPDATE demand_quote
            SET status = #{toStatus},
                version = version + 1,
                updated_at = #{at}
            WHERE demand_id = #{demandId}
              AND status = 'ACTIVE'
            """)
    int endActiveQuotes(
            @Param("demandId") UUID demandId,
            @Param("toStatus") String toStatus,
            @Param("at") OffsetDateTime at
    );

    // ---- 报价的对比项和明细（1.3.0） ----

    /** 报价或改价之后写对比项（和内容在同一个事务里）。 */
    @Update("""
            UPDATE demand_quote
            SET team_text = #{team},
                night_work_text = #{nightWork},
                report_hours = #{reportHours},
                archive_days = #{archiveDays},
                first_visit_date = #{firstVisitDate}
            WHERE id = #{quoteId}
            """)
    int updateQuoteDetails(
            @Param("quoteId") UUID quoteId,
            @Param("team") String team,
            @Param("nightWork") String nightWork,
            @Param("reportHours") Integer reportHours,
            @Param("archiveDays") Integer archiveDays,
            @Param("firstVisitDate") LocalDate firstVisitDate
    );

    @Delete("DELETE FROM demand_quote_line WHERE quote_id = #{quoteId}")
    int deleteQuoteLines(@Param("quoteId") UUID quoteId);

    @Insert("""
            INSERT INTO demand_quote_line (quote_id, sort_order, name, quantity, unit, unit_amount_minor, amount_minor)
            VALUES (#{quoteId}, #{sortOrder}, #{name}, #{quantity}, #{unit}, #{unitAmountMinor}, #{amountMinor})
            """)
    int insertQuoteLine(
            @Param("quoteId") UUID quoteId,
            @Param("sortOrder") int sortOrder,
            @Param("name") String name,
            @Param("quantity") Integer quantity,
            @Param("unit") String unit,
            @Param("unitAmountMinor") Long unitAmountMinor,
            @Param("amountMinor") long amountMinor
    );

    @Select("""
            <script>
            SELECT quote_id, sort_order, name, quantity, unit, unit_amount_minor, amount_minor
            FROM demand_quote_line
            WHERE quote_id IN
            <foreach collection="quoteIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY quote_id, sort_order
            </script>
            """)
    List<QuoteLineRow> findQuoteLines(@Param("quoteIds") List<UUID> quoteIds);

    // ---- 选定后的服务订单（1.3.0）、计数 ----

    @Update("""
            UPDATE demand
            SET service_order_id = #{serviceOrderId}
            WHERE id = #{demandId}
              AND status = 'CHOSEN'
              AND service_order_id IS NULL
            """)
    int linkServiceOrder(@Param("demandId") UUID demandId, @Param("serviceOrderId") UUID serviceOrderId);

    @Select("""
            SELECT status, count(*) AS count
            FROM demand
            WHERE account_id = #{accountId}
            GROUP BY status
            """)
    List<StatusCountRow> countByStatus(@Param("accountId") UUID accountId);

    // ---- 行 ----

    record KeyRow(UUID id, String requestHash) {
    }

    record DemandRow(
            UUID id,
            UUID accountId,
            UUID organizationId,
            String organizationName,
            String type,
            String cityCode,
            String cityName,
            String address,
            String scopeText,
            LocalDate dueDate,
            Long budgetMinMinor,
            Long budgetMaxMinor,
            String note,
            String quoteScope,
            String status,
            OffsetDateTime submittedAt,
            OffsetDateTime quoteDeadline,
            UUID chosenQuoteId,
            Integer chosenQuoteVersion,
            OffsetDateTime chosenAt,
            OffsetDateTime closedAt,
            String closeReason,
            UUID serviceOrderId,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            int version
    ) {
    }

    record DeliverableRow(UUID demandId, String code) {
    }

    record InvitationRow(UUID demandId, UUID providerId) {
    }

    record QuoteRow(
            UUID id,
            UUID demandId,
            UUID providerId,
            String status,
            int quoteVersion,
            long amountMinor,
            String method,
            String scheduleText,
            int durationDays,
            String deliverablesText,
            OffsetDateTime validUntil,
            OffsetDateTime firstSubmittedAt,
            OffsetDateTime revisedAt,
            String teamText,
            String nightWorkText,
            Integer reportHours,
            Integer archiveDays,
            LocalDate firstVisitDate
    ) {
    }

    record QuoteLineRow(
            UUID quoteId,
            int sortOrder,
            String name,
            Integer quantity,
            String unit,
            Long unitAmountMinor,
            long amountMinor
    ) {
    }

    record StatusCountRow(String status, long count) {
    }
}
