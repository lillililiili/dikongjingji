package com.lowaltitude.inspection.experience.application.ops;

import java.util.Optional;
import java.util.UUID;

/**
 * 运营端“场馆与设备”要问别的模块的事，由启动模块接（VenueOpsPortsConfiguration）：本模块不读它们的表。
 */
public final class VenueOpsPorts {

    private VenueOpsPorts() {
    }

    /** 平台城市目录（巡检模块）：可选城市的名称；编码不对、不存在或不可选时为空。 */
    @FunctionalInterface
    public interface Cities {
        Optional<String> cityName(String cityCode);
    }

    /** 身份模块：在用的服务商机构的名称（场馆运营机构只能是服务商机构）；不存在、停用、不是服务商时为空。 */
    @FunctionalInterface
    public interface Operators {
        Optional<String> providerName(UUID organizationId);

        /** 这家机构资质没审核通过、被挂起上架（身份迁移 V013）。 */
        default boolean listingSuspended(UUID organizationId) {
            return false;
        }
    }

    /** 商城模块：这件商品在这个城市有没有在售（馆内设备关联商品用，App 商品详情的“先体验”按城市找场馆）。 */
    @FunctionalInterface
    public interface Products {
        boolean soldInCity(UUID productId, String cityCode);

        /** 没接商城时：只校验 ID 格式，不确认商品存在。 */
        static Products unchecked() {
            return (productId, cityCode) -> true;
        }
    }
}
