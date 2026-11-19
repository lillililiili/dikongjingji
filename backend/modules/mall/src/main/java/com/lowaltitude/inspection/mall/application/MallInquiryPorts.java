package com.lowaltitude.inspection.mall.application;

import java.util.UUID;

/** 询价用到的别的模块的能力，由启动模块接上：商家能不能在线收款（统一交易模块）。商城不读交易的表。 */
public final class MallInquiryPorts {

    private MallInquiryPorts() {
    }

    public interface MerchantPayments {

        /** 这家机构登记了有效的收款账户。 */
        boolean canCollect(UUID organizationId);
    }
}
