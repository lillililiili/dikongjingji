package com.lowaltitude.inspection.trade.application;

import java.util.UUID;

/**
 * 付款主体（S5-7，契约 openapi-trade.yaml 0.6.0）：下单时买家的当前身份，由身份模块经启动模块接上。
 * 交易只存快照，不做企业结算；closing 为真表示账号在注销冷静期（S5-8），不能下单和付款。
 * 没接身份模块时（测试）一律是个人、可以下单。
 */
public interface TradePayerPort {

    Payer payerFor(UUID buyerId);

    static TradePayerPort personal() {
        return buyerId -> Payer.PERSONAL;
    }

    /** type：PERSONAL 或 ENTERPRISE；企业时 organizationId、name 是企业机构和当时的名称。 */
    record Payer(String type, UUID organizationId, String name, boolean closing) {

        public static final Payer PERSONAL = new Payer("PERSONAL", null, null, false);

        public boolean enterprise() {
            return "ENTERPRISE".equals(type);
        }
    }
}
