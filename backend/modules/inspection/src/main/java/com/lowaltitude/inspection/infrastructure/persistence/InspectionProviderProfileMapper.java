package com.lowaltitude.inspection.infrastructure.persistence;

import java.util.UUID;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 服务商资料的开通（S6-01）：巡检迁移 V004 的 inspection_provider_profile 只由这里和开发种子写。只碰这一张表，
 * 机构类型、资质由身份模块在调用前检查（经端口，模块之间不互读表）。全部用绑定参数。
 */
public interface InspectionProviderProfileMapper {

    @Select("SELECT status FROM inspection_provider_profile WHERE provider_id = #{providerId}")
    String findStatus(@Param("providerId") UUID providerId);

    @Select("SELECT status FROM inspection_provider_profile WHERE provider_id = #{providerId} FOR UPDATE")
    String lockStatus(@Param("providerId") UUID providerId);

    /**
     * 新开通：在用；图标用平台的首字母图标 PROVIDER_INITIAL（和开发种子一样，App 没有机构 logo 时显示的那个），
     * 资质说明和团队介绍留空（主页上显示的资料由机构在工作台提交、平台审核）。
     */
    @Insert("""
            INSERT INTO inspection_provider_profile (provider_id, logo_asset_code, status)
            VALUES (#{providerId}, 'PROVIDER_INITIAL', 'ACTIVE')
            ON CONFLICT (provider_id) DO NOTHING
            """)
    int insertActive(@Param("providerId") UUID providerId);

    @Update("""
            UPDATE inspection_provider_profile
            SET status = 'ACTIVE',
                version = version + 1,
                updated_at = now()
            WHERE provider_id = #{providerId}
              AND status = 'DISABLED'
            """)
    int reactivate(@Param("providerId") UUID providerId);
}
