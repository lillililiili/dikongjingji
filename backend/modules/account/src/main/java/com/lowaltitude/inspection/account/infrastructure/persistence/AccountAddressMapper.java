package com.lowaltitude.inspection.account.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 收货地址的读写。每条语句都以所属账号为条件，全部用绑定参数。 */
public interface AccountAddressMapper {

    String COLUMNS = """
            SELECT a.id,
                   a.recipient_name,
                   a.phone,
                   a.province_code,
                   a.province_name,
                   a.city_code,
                   a.city_name,
                   a.detail,
                   a.is_default
            FROM account_address a
            WHERE a.owner_id = #{ownerId}
              AND a.status = 'ACTIVE'
            """;

    /** 默认地址在最前，其余按添加先后。 */
    @Select(COLUMNS + """
            ORDER BY a.is_default DESC, a.created_at ASC, a.id ASC
            """)
    List<AddressRow> findAll(@Param("ownerId") UUID ownerId);

    @Select(COLUMNS + """
              AND a.id = #{addressId}
            """)
    AddressRow findOne(@Param("ownerId") UUID ownerId, @Param("addressId") UUID addressId);

    @Select(COLUMNS + """
              AND a.is_default
            """)
    AddressRow findDefault(@Param("ownerId") UUID ownerId);

    /**
     * 同一个账号的写操作排队：事务级的咨询锁，事务结束自动释放。
     * 不加锁时，两个同时到的请求都能通过“数量没到上限”的检查，或者同时换默认而撞上唯一约束。
     */
    @Select("""
            SELECT 1 FROM (
                SELECT pg_advisory_xact_lock(hashtextextended(#{lockName} || CAST(#{ownerId} AS text), 0))
            ) locked
            """)
    Integer lockOwner(@Param("lockName") String lockName, @Param("ownerId") UUID ownerId);

    @Select("""
            SELECT count(*)
            FROM account_address
            WHERE owner_id = #{ownerId}
              AND status = 'ACTIVE'
            """)
    int count(@Param("ownerId") UUID ownerId);

    /** 换默认地址前先把原来的默认去掉，两条语句在同一个事务里。 */
    @Update("""
            UPDATE account_address
            SET is_default = false,
                version = version + 1,
                updated_at = #{at}
            WHERE owner_id = #{ownerId}
              AND status = 'ACTIVE'
              AND is_default
            """)
    int clearDefault(@Param("ownerId") UUID ownerId, @Param("at") Instant at);

    @Select("""
            INSERT INTO account_address (
                owner_id, recipient_name, phone,
                province_code, province_name, city_code, city_name,
                detail, is_default, created_at, updated_at
            )
            VALUES (
                #{ownerId}, #{recipientName}, #{phone},
                #{provinceCode}, #{provinceName}, #{cityCode}, #{cityName},
                #{detail}, #{isDefault}, #{at}, #{at}
            )
            RETURNING id
            """)
    UUID insert(
            @Param("ownerId") UUID ownerId,
            @Param("recipientName") String recipientName,
            @Param("phone") String phone,
            @Param("provinceCode") String provinceCode,
            @Param("provinceName") String provinceName,
            @Param("cityCode") String cityCode,
            @Param("cityName") String cityName,
            @Param("detail") String detail,
            @Param("isDefault") boolean isDefault,
            @Param("at") Instant at
    );

    @Update("""
            UPDATE account_address
            SET recipient_name = #{recipientName},
                phone = #{phone},
                province_code = #{provinceCode},
                province_name = #{provinceName},
                city_code = #{cityCode},
                city_name = #{cityName},
                detail = #{detail},
                is_default = #{isDefault},
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{addressId}
              AND owner_id = #{ownerId}
              AND status = 'ACTIVE'
            """)
    int update(
            @Param("ownerId") UUID ownerId,
            @Param("addressId") UUID addressId,
            @Param("recipientName") String recipientName,
            @Param("phone") String phone,
            @Param("provinceCode") String provinceCode,
            @Param("provinceName") String provinceName,
            @Param("cityCode") String cityCode,
            @Param("cityName") String cityName,
            @Param("detail") String detail,
            @Param("isDefault") boolean isDefault,
            @Param("at") Instant at
    );

    @Update("""
            UPDATE account_address
            SET status = 'DELETED',
                is_default = false,
                version = version + 1,
                updated_at = #{at}
            WHERE id = #{addressId}
              AND owner_id = #{ownerId}
              AND status = 'ACTIVE'
            """)
    int delete(
            @Param("ownerId") UUID ownerId,
            @Param("addressId") UUID addressId,
            @Param("at") Instant at
    );

    /**
     * 注销到期（S5-8）：这个账号的地址全部删除，并把收货人、手机号、详细地址清成占位（已删除的地址也一样），
     * 不再能对应到人。手机号要满足表上的格式约束，用 13000000000 占位。
     */
    @Update("""
            UPDATE account_address
            SET status = 'DELETED',
                is_default = false,
                recipient_name = '已注销用户',
                phone = '13000000000',
                detail = '已删除',
                version = version + 1,
                updated_at = #{at}
            WHERE owner_id = #{ownerId}
            """)
    int eraseAll(@Param("ownerId") UUID ownerId, @Param("at") Instant at);

    /** 删除默认地址后，把最早添加的一条设为默认，地址簿里只要还有地址就总有一条默认。 */
    @Update("""
            UPDATE account_address
            SET is_default = true,
                version = version + 1,
                updated_at = #{at}
            WHERE id = (
                SELECT id
                FROM account_address
                WHERE owner_id = #{ownerId}
                  AND status = 'ACTIVE'
                ORDER BY created_at ASC, id ASC
                LIMIT 1
            )
              AND NOT EXISTS (
                SELECT 1
                FROM account_address
                WHERE owner_id = #{ownerId}
                  AND status = 'ACTIVE'
                  AND is_default
            )
            """)
    int promoteOldestToDefault(@Param("ownerId") UUID ownerId, @Param("at") Instant at);

    record AddressRow(
            UUID id,
            String recipientName,
            String phone,
            String provinceCode,
            String provinceName,
            String cityCode,
            String cityName,
            String detail,
            boolean isDefault
    ) {
    }
}
