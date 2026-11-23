package com.lowaltitude.inspection.mall.application;

import java.util.Optional;
import java.util.UUID;

/**
 * 运营端“商品与供给”用到的别的模块和外部服务，由启动模块接上：城市目录（巡检模块）、经营机构（身份模块）、
 * 商品图片的对象存储。商家能不能收款沿用 {@link MallInquiryPorts.MerchantPayments}（交易模块）。
 */
public final class MallOpsPorts {

    private MallOpsPorts() {
    }

    public interface Cities {

        /** 平台城市目录里可选城市的名称；不认识的为空。 */
        Optional<String> cityName(String cityCode);
    }

    public interface Merchants {

        /** 在用的经营机构（PROVIDER）的名称；不存在、停用或不是经营机构时为空。 */
        Optional<String> activeProviderName(UUID organizationId);
    }

    /**
     * 商品图片的对象存储。真实环境接平台的公共读桶或 CDN；开发环境是本机目录（bootstrap 的 LocalMallMediaStore）。
     * 没配置时 put 抛 {@link MediaStoreUnavailableException}。
     */
    public interface MediaStore {

        void put(String key, byte[] content, String contentType);

        /** 这个键对外的受控地址。 */
        String publicUrl(String key);

        /** 这个地址是这里给出的受控地址（开发环境的本机地址也算）。 */
        boolean isControlled(String url);
    }

    public static final class MediaStoreUnavailableException extends RuntimeException {

        public MediaStoreUnavailableException(String message) {
            super(message);
        }

        public MediaStoreUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
