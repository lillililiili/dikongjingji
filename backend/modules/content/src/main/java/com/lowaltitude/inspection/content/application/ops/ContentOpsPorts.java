package com.lowaltitude.inspection.content.application.ops;

import java.util.Optional;
import java.util.UUID;

/**
 * 运营端“发现内容”要问别的模块的事，由启动模块接（ContentOpsPortsConfiguration）；本模块不读它们的表。
 */
public interface ContentOpsPorts {

    /** 身份模块：在用的机构；不存在或已停用时为空。type 是 CUSTOMER / PROVIDER / PLATFORM。 */
    interface Organizations {
        Optional<Organization> find(UUID organizationId);
    }

    record Organization(UUID organizationId, String name, String type) {
    }

    /** 巡检模块的平台城市目录：可选城市的名称；编码不对、不存在或不可选时为空。 */
    interface Cities {
        Optional<String> cityName(String cityCode);
    }

    /** 商城模块：这件商品在这个城市有没有在售（专题的商家内容只挂本城在卖的商品）。 */
    interface Products {
        boolean soldIn(UUID productId, String cityCode);
    }
}
