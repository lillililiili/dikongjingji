package com.lowaltitude.inspection.mall.application;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * 询价和商品详情用到的别的模块的能力，由启动模块接上：商家能不能在线收款（统一交易模块）、商家的资质审核过没有（身份模块，R6-4）。
 * 商城不读交易的表，报价里的商家资质也不直接读身份的表。
 */
public final class MallInquiryPorts {

    private MallInquiryPorts() {
    }

    public interface MerchantPayments {

        /** 这家机构登记了有效的收款账户。 */
        boolean canCollect(UUID organizationId);
    }

    public interface MerchantQualifications {

        /** 这些商家里平台审核过资质的那些；不认识、停用、没审核的不在结果里。 */
        Set<UUID> reviewed(Collection<UUID> organizationIds);
    }
}
