package com.lowaltitude.inspection.trade.application;

import java.util.UUID;

import com.lowaltitude.inspection.account.application.CustomerAccount;
import com.lowaltitude.inspection.trade.application.TradeViews.EnterpriseOrderPageView;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 企业订单（S5-7，契约 openapi-trade.yaml 0.6.0 的 listEnterpriseOrders）：本人所在企业以企业身份付款的订单，
 * 只给这家企业当前的成员看。成员校验和经办人名称经 {@link TradeEnterpriseAccess} 问身份模块。
 */
@RestController
public final class TradeEnterpriseOrderController {

    private final TradeOrderService service;
    private final TradeEnterpriseAccess access;

    public TradeEnterpriseOrderController(TradeOrderService service, TradeEnterpriseAccess access) {
        this.service = service;
        this.access = access;
    }

    @GetMapping("/api/v1/trade/enterprise-orders")
    public EnterpriseOrderPageView list(
            @RequestParam UUID enterpriseId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication
    ) {
        return service.listEnterpriseOrders(CustomerAccount.of(authentication), enterpriseId, page, size, access);
    }
}
