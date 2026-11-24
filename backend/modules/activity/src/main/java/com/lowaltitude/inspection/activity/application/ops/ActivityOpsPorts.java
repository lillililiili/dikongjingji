package com.lowaltitude.inspection.activity.application.ops;

import java.util.Optional;
import java.util.UUID;

/**
 * 运营端“活动与场次”要问别的模块的两件事，由启动模块接（ActivityOpsPortsConfiguration）：
 * 主办 / 承办机构是不是在用的机构（身份模块），城市在不在平台城市目录里（巡检模块的城市目录）。本模块不读它们的表。
 */
public interface ActivityOpsPorts {

    /** 在用的机构；不存在或已停用时为空。 */
    interface Organizations {
        Optional<Organization> find(UUID organizationId);
    }

    /**
     * type：CUSTOMER / PROVIDER / PLATFORM（身份模块的机构类型）。listingSuspended：资质没审核通过、不能上架
     * （身份迁移 V013）。
     */
    record Organization(UUID organizationId, String name, String type, boolean listingSuspended) {

        public Organization(UUID organizationId, String name, String type) {
            this(organizationId, name, type, false);
        }
    }

    /** 可选城市的名称；编码不对、不存在或不可选时为空。 */
    interface Cities {
        Optional<String> cityName(String cityCode);
    }
}
