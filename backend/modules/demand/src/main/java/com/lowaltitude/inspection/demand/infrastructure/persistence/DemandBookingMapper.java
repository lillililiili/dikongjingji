package com.lowaltitude.inspection.demand.infrastructure.persistence;

import java.util.UUID;
import org.apache.ibatis.annotations.Update;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface DemandBookingMapper {
    @Select("""
            SELECT q.id AS quote_id, q.quote_version, q.provider_id, org.organization_name AS provider_name,
                   q.amount_minor, q.method AS summary
            FROM demand_quote q JOIN identity_organization org ON org.id = q.provider_id
            WHERE q.id = #{quoteId} AND q.demand_id = #{demandId}
              AND q.quote_version = #{version} AND q.status = 'CHOSEN'
            """)
    Quote findChosen(@Param("demandId") UUID demandId, @Param("quoteId") UUID quoteId, @Param("version") int version);

    @Select("SELECT reservation_id, request_hash FROM demand_inspection_booking WHERE demand_id = #{demandId}")
    Link findLink(@Param("demandId") UUID demandId);

    @Select("""
            SELECT demand_id FROM demand_inspection_booking
            WHERE account_id = #{accountId} AND idempotency_key = #{key}
            """)
    UUID findDemandByKey(@Param("accountId") UUID accountId, @Param("key") UUID key);

    // 需求详情有新的预约入口，对应的 ETag 和更新时间也应随之变化。
    @Update("""
            WITH linked AS (
                INSERT INTO demand_inspection_booking (demand_id, reservation_id, quote_id, quote_version, provider_id,
                    provider_name, amount_minor, summary, account_id, idempotency_key, request_hash)
                VALUES (#{demandId}, #{reservationId}, #{q.quoteId}, #{q.quoteVersion}, #{q.providerId},
                    #{q.providerName}, #{q.amountMinor}, #{q.summary}, #{accountId}, #{key}, #{hash})
                RETURNING demand_id
            )
            UPDATE demand SET version = version + 1, updated_at = CURRENT_TIMESTAMP
            WHERE id = (SELECT demand_id FROM linked)
            """)
    void insert(@Param("demandId") UUID demandId, @Param("reservationId") UUID reservationId, @Param("q") Quote quote,
                @Param("accountId") UUID accountId, @Param("key") UUID key, @Param("hash") String hash);

    record Quote(UUID quoteId, int quoteVersion, UUID providerId, String providerName, long amountMinor, String summary) { }
    record Link(UUID reservationId, String requestHash) { }
}
